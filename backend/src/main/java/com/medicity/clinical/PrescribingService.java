package com.medicity.clinical;

import com.medicity.audit.AuditLog;
import com.medicity.outbox.Outbox;
import com.medicity.scan.ScanService;
import com.medicity.pharmacy.DispensationRepository;
import com.medicity.common.ConflictException;
import com.medicity.common.Constraints;
import com.medicity.common.ForbiddenException;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.pharmacy.Medicine;
import com.medicity.pharmacy.MedicineRepository;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Issuing and correcting prescriptions.
 *
 * <p>Prescriptions are never edited. A change is a <em>correction</em>: a new
 * prescription whose {@code supersedesId} points at the one it replaces. The
 * original stays in the table for the medico-legal record; patients and the
 * pharmacy only ever see the latest version.
 *
 * <p>Two rules could race and are therefore left to the database:
 * <ul>
 *   <li>one original per visit ({@code uq_presc_original_per_appointment}, V8)
 *       — two tabs issuing at once cannot create two independent prescriptions;</li>
 *   <li>one correction per prescription ({@code uq_presc_supersedes}, V3)
 *       — two simultaneous corrections cannot fork the history into two branches.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PrescribingService {

    private static final String UQ_ORIGINAL = "uq_presc_original_per_appointment";
    private static final String UQ_SUPERSEDES = "uq_presc_supersedes";
    private static final String UQ_ITEM_MEDICINE = "uq_presc_item_medicine";
    private static final String UQ_SCAN = "uq_prescription_scan";

    private final VisitService visitService;
    private final PrescriptionRepository prescriptionRepository;
    private final DoctorRepository doctorRepository;
    private final MedicineRepository medicineRepository;
    private final AuditLog auditLog;
    private final Outbox outbox;
    private final DispensationRepository dispensationRepository;
    private final ScanService scanService;
    private final Clock clock;

    /** The first prescription for a completed visit. */
    @Transactional
    public Prescription issue(UUID appointmentId, UUID doctorId, PrescriptionDraft draft) {
        Appointment visit = visitService.requireOwnVisit(appointmentId, doctorId);
        if (visit.getStatus() != AppointmentStatus.COMPLETED) {
            throw new ValidationException("VISIT_NOT_COMPLETED",
                    "Mark the visit as completed before prescribing");
        }

        if (draft.scanId() != null) {
            // The doctor confirmed a draft read from their photo; the photo must
            // be theirs, from this visit.
            scanService.attach(draft.scanId(), appointmentId, doctorId);
        }
        Prescription saved = save(build(visit, doctorId, null, draft, draft.scanId()));
        auditLog.recordChange("PRESCRIPTION_ISSUED", "PRESCRIPTION", saved.getId(),
                Map.of("appointmentId", appointmentId, "patientId", visit.getPatient().getId(),
                        "items", draft.items().size(), "fromPhoto", draft.scanId() != null));
        outbox.publish(Outbox.PRESCRIPTION_ISSUED, saved.getId(), Map.of(
                "patientUserId", visit.getPatient().accountUserId(),
                "doctorName", visit.getSlot().getDoctor().getUser().getFullName(),
                "diagnosis", draft.diagnosis()));
        return saved;
    }

    /** Replaces a prescription. Only its author may correct it, and only the latest version. */
    @Transactional
    public Prescription correct(UUID prescriptionId, UUID doctorId, PrescriptionDraft draft) {
        Prescription original = prescriptionRepository.findWithItems(prescriptionId)
                .orElseThrow(() -> new NotFoundException("Prescription", prescriptionId));

        if (!original.getDoctor().getId().equals(doctorId)) {
            auditLog.recordIndependently("ACCESS_DENIED", "PRESCRIPTION", prescriptionId,
                    AuditLog.Outcome.DENIED, Map.of("operation", "correct"));
            throw new ForbiddenException("Only the prescribing doctor can correct this prescription");
        }
        // Fast, friendly answer for the common case. Not the guard: two
        // simultaneous corrections both pass this, and the unique index decides.
        if (prescriptionRepository.existsBySupersedesId(prescriptionId)) {
            throw new ConflictException("ALREADY_CORRECTED",
                    "This prescription has already been corrected. Correct the latest version instead.");
        }

        Appointment visit = visitService.requireOwnVisit(original.getAppointment().getId(), doctorId);
        Prescription saved = save(build(visit, doctorId, prescriptionId, draft, null));
        auditLog.recordChange("PRESCRIPTION_CORRECTED", "PRESCRIPTION", saved.getId(),
                Map.of("supersedes", prescriptionId, "patientId", visit.getPatient().getId()));
        // If the pharmacy already handed out the original, the patient may be
        // holding medicines the correction replaces; both need to know.
        outbox.publish(Outbox.PRESCRIPTION_CORRECTED, saved.getId(), Map.of(
                "patientUserId", visit.getPatient().accountUserId(),
                "patientName", visit.getPatient().displayName(),
                "doctorName", visit.getSlot().getDoctor().getUser().getFullName(),
                "diagnosis", draft.diagnosis(),
                "supersedes", prescriptionId,
                "originalDispensed", dispensationRepository.existsByPrescriptionId(prescriptionId)));
        return saved;
    }

    private Prescription build(Appointment visit, UUID doctorId, UUID supersedes, PrescriptionDraft draft, UUID scanId) {
        requireDistinctMedicines(draft);

        Map<UUID, Medicine> medicines = medicineRepository.findAllById(
                        draft.items().stream().map(PrescriptionDraft.Item::medicineId).toList()).stream()
                .collect(Collectors.toMap(Medicine::getId, Function.identity()));

        Doctor doctor = doctorRepository.getReferenceById(doctorId);
        Prescription prescription = Prescription.builder()
                .appointment(visit)
                .doctor(doctor)
                .patient(visit.getPatient())
                .diagnosis(draft.diagnosis().trim())
                .notes(draft.notes() == null || draft.notes().isBlank() ? null : draft.notes().trim())
                .supersedesId(supersedes)
                .scanId(scanId)
                .issuedAt(clock.instant())
                .build();

        for (PrescriptionDraft.Item item : draft.items()) {
            Medicine medicine = medicines.get(item.medicineId());
            if (medicine == null || !medicine.isActive()) {
                throw new ValidationException("UNKNOWN_MEDICINE",
                        "Medicine %s is not in the catalogue".formatted(item.medicineId()));
            }
            prescription.getItems().add(PrescriptionItem.builder()
                    .prescription(prescription)
                    .medicine(medicine)
                    .dosage(item.dosage().trim())
                    .frequency(item.frequency().trim())
                    .durationDays(item.durationDays())
                    .quantity(item.quantity())
                    .substitutionAllowed(item.substitutionAllowed())
                    .build());
        }
        return prescription;
    }

    private Prescription save(Prescription prescription) {
        try {
            return prescriptionRepository.saveAndFlush(prescription);
        } catch (DataIntegrityViolationException e) {
            String constraint = Constraints.nameOf(e);
            if (UQ_ORIGINAL.equalsIgnoreCase(constraint)) {
                throw new ConflictException("PRESCRIPTION_EXISTS",
                        "This visit already has a prescription. Correct it instead of issuing a new one.");
            }
            if (UQ_SUPERSEDES.equalsIgnoreCase(constraint)) {
                throw new ConflictException("ALREADY_CORRECTED",
                        "Someone corrected this prescription a moment ago. Reload to see the latest version.");
            }
            if (UQ_SCAN.equalsIgnoreCase(constraint)) {
                throw new ConflictException("PHOTO_ALREADY_USED", "That photo already issued a prescription");
            }
            if (UQ_ITEM_MEDICINE.equalsIgnoreCase(constraint)) {
                throw new ValidationException("DUPLICATE_MEDICINE", "Each medicine may appear only once");
            }
            throw e;
        }
    }

    private static void requireDistinctMedicines(PrescriptionDraft draft) {
        Set<UUID> seen = new HashSet<>();
        for (PrescriptionDraft.Item item : draft.items()) {
            if (!seen.add(item.medicineId())) {
                throw new ValidationException("DUPLICATE_MEDICINE",
                        "Each medicine may appear only once; adjust the quantity instead");
            }
        }
    }

    /**
     * What a doctor submits. Validated at the HTTP boundary; see the controller.
     * {@code scanId}: the photo this was typed from, when the doctor started
     * from a photographed slip; ignored for corrections.
     */
    public record PrescriptionDraft(String diagnosis, String notes, List<Item> items, UUID scanId) {
        public PrescriptionDraft(String diagnosis, String notes, List<Item> items) {
            this(diagnosis, notes, items, null);
        }

        public record Item(UUID medicineId, String dosage, String frequency, int durationDays, int quantity,
                           boolean substitutionAllowed) {}
    }
}
