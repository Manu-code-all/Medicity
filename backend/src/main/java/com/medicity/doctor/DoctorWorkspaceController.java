package com.medicity.doctor;

import com.medicity.audit.AuditLog;
import com.medicity.clinical.PrescribingService;
import com.medicity.clinical.PrescribingService.PrescriptionDraft;
import com.medicity.clinical.Prescription;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.clinical.VisitService;
import com.medicity.common.ForbiddenException;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientPortalController.PrescriptionResponse;
import com.medicity.patient.PatientPortalController.VisitResponse;
import com.medicity.patient.PatientRepository;
import com.medicity.pharmacy.Dispensation;
import com.medicity.pharmacy.DispensationRepository;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentRepository;
import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The doctor's workspace: their calendar, the visits on it, prescribing, and
 * the history of patients they treat.
 *
 * <p>As in the patient portal, no route takes a doctor id. "Me" comes from the
 * token, and every visit or patient id in a path is checked against that
 * doctor's own calendar before anything is returned.
 */
@RestController
@RequestMapping("/api/v1/doctors/me")
@PreAuthorize("hasRole('DOCTOR')")
@RequiredArgsConstructor
@Tag(name = "Doctor workspace")
public class DoctorWorkspaceController {

    /** The widest calendar window one request may ask for. */
    private static final Duration MAX_WINDOW = Duration.ofDays(31);

    private final DoctorRepository doctorRepository;
    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final DispensationRepository dispensationRepository;
    private final VisitService visitService;
    private final PrescribingService prescribingService;
    private final AuditLog auditLog;
    private final Clock clock;

    @GetMapping("/visits")
    @Operation(summary = "My visits between two instants (the client sends its own day boundaries)")
    public List<VisitSummary> visits(@AuthenticationPrincipal AppUserPrincipal principal,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new ValidationException("INVALID_WINDOW", "'to' must be after 'from' and at most 31 days later");
        }
        List<Appointment> visits = appointmentRepository.findForDoctorBetween(doctorId(principal), from, to);

        // Two batch queries for the whole day, not two per visit.
        Map<UUID, Prescription> rxByVisit = prescriptionRepository
                .findCurrentForAppointments(visits.stream().map(Appointment::getId).toList()).stream()
                .collect(Collectors.toMap(p -> p.getAppointment().getId(), Function.identity()));
        Map<UUID, Instant> dispensed = dispensedAt(rxByVisit.values());

        return visits.stream().map(v -> {
            Prescription rx = rxByVisit.get(v.getId());
            return VisitSummary.from(v, rx == null ? null : rx.getId(),
                    rx == null ? null : dispensed.get(rx.getId()), LocalDate.now(clock));
        }).toList();
    }

    @GetMapping("/visits/{appointmentId}")
    @Operation(summary = "One of my visits, with its current prescription")
    public VisitDetail visit(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID appointmentId) {
        Appointment visit = visitService.requireOwnVisit(appointmentId, doctorId(principal));
        return detail(visit);
    }

    @PostMapping("/visits/{appointmentId}/complete")
    @Operation(summary = "Record that the patient was seen")
    public VisitDetail complete(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID appointmentId) {
        visitService.complete(appointmentId, doctorId(principal));
        return detail(visitService.requireOwnVisit(appointmentId, doctorId(principal)));
    }

    @PostMapping("/visits/{appointmentId}/no-show")
    @Operation(summary = "Record that the patient did not come")
    public VisitDetail noShow(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID appointmentId) {
        visitService.markNoShow(appointmentId, doctorId(principal));
        return detail(visitService.requireOwnVisit(appointmentId, doctorId(principal)));
    }

    @PostMapping("/visits/{appointmentId}/prescriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Issue the prescription for a completed visit")
    public VisitDetail prescribe(@AuthenticationPrincipal AppUserPrincipal principal,
                                 @PathVariable UUID appointmentId,
                                 @Valid @RequestBody PrescriptionRequest request) {
        prescribingService.issue(appointmentId, doctorId(principal), request.toDraft());
        return detail(visitService.requireOwnVisit(appointmentId, doctorId(principal)));
    }

    @PostMapping("/prescriptions/{prescriptionId}/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Correct a prescription; the correction replaces it")
    public VisitDetail correct(@AuthenticationPrincipal AppUserPrincipal principal,
                               @PathVariable UUID prescriptionId,
                               @Valid @RequestBody PrescriptionRequest request) {
        Prescription correction = prescribingService.correct(prescriptionId, doctorId(principal), request.toDraft());
        return detail(visitService.requireOwnVisit(correction.getAppointment().getId(), doctorId(principal)));
    }

    /**
     * A patient's history, across every doctor they have seen. Allowed only if
     * the patient has been on this doctor's calendar: a doctor may review the
     * history of their own patients, not browse the hospital's records. Every
     * view is audited.
     */
    @GetMapping("/patients/{patientId}/history")
    @Operation(summary = "History of a patient I treat")
    public PatientHistory patientHistory(@AuthenticationPrincipal AppUserPrincipal principal,
                                         @PathVariable UUID patientId) {
        UUID doctorId = doctorId(principal);
        if (!appointmentRepository.existsBySlotDoctorIdAndPatientId(doctorId, patientId)) {
            auditLog.recordIndependently("ACCESS_DENIED", "PATIENT", patientId,
                    AuditLog.Outcome.DENIED, Map.of("operation", "history"));
            throw new ForbiddenException("You can only view the history of your own patients");
        }
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new NotFoundException("Patient", patientId));

        List<VisitResponse> visits = appointmentRepository.findForPatient(patientId, PageRequest.of(0, 50))
                .map(VisitResponse::from).getContent();
        List<Prescription> current = prescriptionRepository.findCurrentForPatient(patientId);
        Map<UUID, Instant> dispensed = dispensedAt(current);

        auditLog.recordIndependently("PATIENT_HISTORY_VIEWED", "PATIENT", patientId, AuditLog.Outcome.SUCCESS, null);

        return new PatientHistory(
                PatientBrief.from(patientRepository.findWithUserByUserId(patient.getUser().getId()).orElseThrow(),
                        LocalDate.now(clock)),
                visits,
                current.stream().map(p -> PrescriptionResponse.from(p, dispensed.get(p.getId()))).toList());
    }

    private VisitDetail detail(Appointment visit) {
        List<Prescription> rx = prescriptionRepository.findCurrentForAppointments(List.of(visit.getId()));
        Map<UUID, Instant> dispensed = dispensedAt(rx);
        PrescriptionResponse current = rx.isEmpty() ? null
                : PrescriptionResponse.from(rx.get(0), dispensed.get(rx.get(0).getId()));
        boolean started = !visit.getScheduledAt().isAfter(clock.instant());
        return new VisitDetail(
                visit.getId(), visit.getStatus().name(), visit.getScheduledAt(), visit.getSlot().getEndsAt(),
                visit.getReason(), visit.getCancelReason(), started,
                PatientBrief.from(visit.getPatient(), LocalDate.now(clock)), current);
    }

    private Map<UUID, Instant> dispensedAt(java.util.Collection<Prescription> prescriptions) {
        if (prescriptions.isEmpty()) {
            return Map.of();
        }
        return dispensationRepository.findByPrescriptionIdIn(prescriptions.stream().map(Prescription::getId).toList())
                .stream().collect(Collectors.toMap(Dispensation::getPrescriptionId, Dispensation::getDispensedAt));
    }

    private UUID doctorId(AppUserPrincipal principal) {
        return doctorRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new NotFoundException("Doctor profile for user", principal.getId()))
                .getId();
    }

    // --- request and response shapes -------------------------------------

    public record PrescriptionRequest(
            @NotBlank @Size(max = 500) String diagnosis,
            @Size(max = 2000) String notes,
            @NotEmpty @Size(max = 20) List<@Valid ItemRequest> items
    ) {
        PrescriptionDraft toDraft() {
            return new PrescriptionDraft(diagnosis, notes, items.stream()
                    .map(i -> new PrescriptionDraft.Item(i.medicineId(), i.dosage(), i.frequency(),
                            i.durationDays(), i.quantity()))
                    .toList());
        }
    }

    /** Limits mirror the CHECK constraints in V3, so a bad value is a 400 here, not a 500 there. */
    public record ItemRequest(
            @NotNull UUID medicineId,
            @NotBlank @Size(max = 80) String dosage,
            @NotBlank @Size(max = 80) String frequency,
            @Min(1) @Max(365) int durationDays,
            @Min(1) @Max(1000) int quantity
    ) {}

    public record PatientBrief(UUID id, String fullName, int age, String gender, String bloodGroup) {
        static PatientBrief from(Patient p, LocalDate today) {
            return new PatientBrief(p.getId(), p.getUser().getFullName(),
                    Period.between(p.getDateOfBirth(), today).getYears(), p.getGender().name(), p.getBloodGroup());
        }
    }

    public record VisitSummary(
            UUID id, String status, Instant scheduledAt, Instant endsAt, String reason,
            PatientBrief patient, UUID prescriptionId, Instant dispensedAt
    ) {
        static VisitSummary from(Appointment a, UUID prescriptionId, Instant dispensedAt, LocalDate today) {
            return new VisitSummary(a.getId(), a.getStatus().name(), a.getScheduledAt(), a.getSlot().getEndsAt(),
                    a.getReason(), PatientBrief.from(a.getPatient(), today), prescriptionId, dispensedAt);
        }
    }

    public record VisitDetail(
            UUID id, String status, Instant scheduledAt, Instant endsAt, String reason, String cancelReason,
            /** Whether the start time has passed, so the visit can be closed. */
            boolean started,
            PatientBrief patient,
            PrescriptionResponse prescription
    ) {}

    public record PatientHistory(PatientBrief patient, List<VisitResponse> visits,
                                 List<PrescriptionResponse> prescriptions) {}
}
