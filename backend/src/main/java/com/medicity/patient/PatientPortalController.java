package com.medicity.patient;

import com.medicity.clinical.Prescription;
import com.medicity.clinical.PrescriptionItem;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.doctor.Doctor;
import com.medicity.pharmacy.Dispensation;
import com.medicity.pharmacy.DispensationRepository;
import com.medicity.request.ReservationService;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentRepository;
import com.medicity.scheduling.AppointmentStatus;
import com.medicity.security.AppUserPrincipal;
import com.medicity.user.User;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.medicity.review.ReviewService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * The patient portal: a signed-in patient's view of their own records.
 *
 * <p>There is no patient id anywhere in these routes. Every endpoint resolves
 * "me" from the token, so there is no parameter a caller could change to read
 * someone else's history — the IDOR surface is removed rather than guarded.
 */
@RestController
@RequestMapping("/api/v1/patients/me")
@PreAuthorize("hasRole('PATIENT')")
@RequiredArgsConstructor
@Tag(name = "Patient portal")
public class PatientPortalController {

    private final PatientRepository patientRepository;
    private final ActingPatient acting;
    private final AppointmentRepository appointmentRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final DispensationRepository dispensationRepository;
    private final ReservationService reservations;
    private final ReviewService reviewService;
    private final Clock clock;

    @GetMapping
    @Operation(summary = "The caller's patient profile")
    public ProfileResponse profile(@AuthenticationPrincipal AppUserPrincipal principal) {
        return ProfileResponse.from(acting.resolve(principal.getId()), acting.self(principal.getId()));
    }

    @PutMapping
    @Transactional
    @Operation(summary = "Replace the health and contact details of the person being viewed",
            description = "Every field is optional and the body replaces what was there, so an omitted field is cleared. "
                    + "Name, date of birth and gender are not changed here.")
    public ProfileResponse updateProfile(@AuthenticationPrincipal AppUserPrincipal principal,
                                         @Valid @RequestBody ProfileUpdate update) {
        if ((update.homeLatitude() == null) != (update.homeLongitude() == null)) {
            throw new ValidationException("LOCATION_INCOMPLETE", "Send both latitude and longitude, or neither");
        }
        Patient patient = acting.resolve(principal.getId());
        patient.setBloodGroup(blankToNull(update.bloodGroup()));
        patient.setHeightCm(update.heightCm());
        patient.setWeightKg(update.weightKg());
        patient.setAllergies(blankToNull(update.allergies()));
        patient.setChronicConditions(blankToNull(update.chronicConditions()));
        patient.setCurrentMedications(blankToNull(update.currentMedications()));
        patient.setEmergencyContact(blankToNull(update.emergencyContact()));
        patient.setAddressLine(blankToNull(update.addressLine()));
        patient.setCity(blankToNull(update.city()));
        patient.setHomeLatitude(update.homeLatitude());
        patient.setHomeLongitude(update.homeLongitude());
        patientRepository.save(patient);
        return ProfileResponse.from(patient, acting.self(principal.getId()));
    }

    @GetMapping("/summary")
    @Operation(summary = "Totals and the next visit, for the portal overview")
    public SummaryResponse summary(@AuthenticationPrincipal AppUserPrincipal principal) {
        UUID patientId = patientId(principal);

        Map<AppointmentStatus, Long> byStatus = new EnumMap<>(AppointmentStatus.class);
        appointmentRepository.countByStatusForPatient(patientId)
                .forEach(c -> byStatus.put(c.getStatus(), c.getTotal()));

        // One page of size 1 yields both the next visit and the upcoming total.
        Page<Appointment> upcoming = appointmentRepository.findUpcomingForPatient(
                patientId, clock.instant(), PageRequest.of(0, 1));

        return new SummaryResponse(
                upcoming.getTotalElements(),
                byStatus.getOrDefault(AppointmentStatus.COMPLETED, 0L),
                byStatus.getOrDefault(AppointmentStatus.CANCELLED, 0L),
                byStatus.getOrDefault(AppointmentStatus.NO_SHOW, 0L),
                prescriptionRepository.countCurrentForPatient(patientId),
                upcoming.stream().findFirst().map(VisitResponse::from).orElse(null));
    }

    @GetMapping("/appointments")
    @Operation(summary = "The caller's visits: upcoming (soonest first) or past (newest first)")
    public Page<VisitResponse> appointments(@AuthenticationPrincipal AppUserPrincipal principal,
                                            @RequestParam(defaultValue = "upcoming") String scope,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        UUID patientId = patientId(principal);
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Instant now = clock.instant();

        Page<Appointment> result = Scope.parse(scope) == Scope.UPCOMING
                ? appointmentRepository.findUpcomingForPatient(patientId, now, pageable)
                : appointmentRepository.findPastForPatient(patientId, now, pageable);
        Set<UUID> reviewed = reviewService.reviewedAmong(result.map(Appointment::getId).getContent());
        return result.map(a -> VisitResponse.from(a, reviewed.contains(a.getId())));
    }

    @GetMapping("/prescriptions")
    @Operation(summary = "The caller's current prescriptions, newest first")
    public List<PrescriptionResponse> prescriptions(@AuthenticationPrincipal AppUserPrincipal principal) {
        List<Prescription> current = prescriptionRepository.findCurrentForPatient(patientId(principal));
        Map<UUID, Instant> dispensedAt = dispensationRepository
                .findByPrescriptionIdIn(current.stream().map(Prescription::getId).toList()).stream()
                .collect(Collectors.toMap(Dispensation::getPrescriptionId, Dispensation::getDispensedAt));
        Map<UUID, ReservationService.Collected> collected = reservations.lastCollected(
                current.stream().map(Prescription::getId).toList());
        return current.stream()
                .map(p -> PrescriptionResponse.from(p, dispensedAt.get(p.getId()), collected.get(p.getId())))
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private UUID patientId(AppUserPrincipal principal) {
        return acting.resolve(principal.getId()).getId();
    }

    public enum Scope {
        UPCOMING, PAST;

        /**
         * Parsed by hand rather than bound as an enum: a binding failure would
         * surface as a type-mismatch exception and reach the catch-all 500
         * handler, when an unknown scope is the caller's mistake.
         */
        static Scope parse(String value) {
            for (Scope s : values()) {
                if (s.name().equalsIgnoreCase(value.trim())) {
                    return s;
                }
            }
            throw new ValidationException("INVALID_SCOPE", "scope must be 'upcoming' or 'past'");
        }
    }

    public record ProfileUpdate(
            @Pattern(regexp = "^$|^(A|B|AB|O)[+-]$", message = "Blood group like O+ or AB-") String bloodGroup,
            @Min(value = 30, message = "Height in centimetres") @Max(value = 260, message = "Height in centimetres") Integer heightCm,
            @DecimalMin(value = "1.0", message = "Weight in kilograms") @DecimalMax(value = "400.0", message = "Weight in kilograms") BigDecimal weightKg,
            @Size(max = 1000) String allergies,
            @Size(max = 1000) String chronicConditions,
            @Size(max = 1000) String currentMedications,
            @Pattern(regexp = "^$|^\\+?[0-9]{10,15}$", message = "Phone must be 10-15 digits") String emergencyContact,
            @Size(max = 200) String addressLine,
            @Size(max = 80) String city,
            @DecimalMin("-90.0") @DecimalMax("90.0") Double homeLatitude,
            @DecimalMin("-180.0") @DecimalMax("180.0") Double homeLongitude
    ) {}

    public record ProfileResponse(
            UUID patientId,
            /** For a family member, their relationship to the account holder; null for the holder. */
            String relationship,
            String fullName,
            String email,
            String phone,
            LocalDate dateOfBirth,
            String gender,
            String bloodGroup,
            String addressLine,
            String city,
            String emergencyContact,
            Integer heightCm,
            BigDecimal weightKg,
            String allergies,
            String chronicConditions,
            String currentMedications,
            Double homeLatitude,
            Double homeLongitude,
            Instant memberSince
    ) {
        /** A family member's contact details are the account holder's: they share the sign-in. */
        static ProfileResponse from(Patient p, Patient holder) {
            User u = holder.getUser();
            return new ProfileResponse(
                    p.getId(), p.getRelationship() == null ? null : p.getRelationship().name(),
                    p.displayName(), u.getEmail(), u.getPhone(),
                    p.getDateOfBirth(), p.getGender().name(), p.getBloodGroup(),
                    p.getAddressLine(), p.getCity(), p.getEmergencyContact(),
                    p.getHeightCm(), p.getWeightKg(), p.getAllergies(), p.getChronicConditions(),
                    p.getCurrentMedications(), p.getHomeLatitude(), p.getHomeLongitude(),
                    p.isFamilyMember() ? p.getCreatedAt() : u.getCreatedAt());
        }
    }

    public record SummaryResponse(
            long upcoming,
            long completed,
            long cancelled,
            long missed,
            long prescriptions,
            VisitResponse nextVisit
    ) {}

    public record VisitResponse(
            UUID id,
            String status,
            Instant scheduledAt,
            Instant endsAt,
            String reason,
            UUID doctorId,
            String doctorName,
            String specialization,
            Instant cancelledAt,
            String cancelReason,
            /** The patient has already reviewed this visit. */
            boolean reviewed,
            String visitType
    ) {
        public static VisitResponse from(Appointment a) {
            return from(a, false);
        }

        public static VisitResponse from(Appointment a, boolean reviewed) {
            Doctor d = a.getSlot().getDoctor();
            return new VisitResponse(
                    a.getId(), a.getStatus().name(), a.getScheduledAt(), a.getSlot().getEndsAt(),
                    a.getReason(), d.getId(), d.getUser().getFullName(), d.getSpecialization(),
                    a.getCancelledAt(), a.getCancelReason(), reviewed, a.getVisitType().name());
        }
    }

    public record PrescriptionResponse(
            UUID id,
            UUID appointmentId,
            Instant issuedAt,
            String doctorName,
            String specialization,
            String diagnosis,
            /** ICD-10, when the doctor chose one. */
            String diagnosisCode,
            String notes,
            boolean revised,
            /** When the pharmacy filled it; null if not yet dispensed. */
            Instant dispensedAt,
            /** When it was last collected from a neighbourhood store, and which; null if never. */
            Instant collectedAt,
            String collectedFrom,
            /** The doctor's handwritten original is attached and can be viewed. */
            boolean hasPhoto,
            List<ItemResponse> items
    ) {
        public static PrescriptionResponse from(Prescription p, Instant dispensedAt) {
            return from(p, dispensedAt, null);
        }

        public static PrescriptionResponse from(Prescription p, Instant dispensedAt, ReservationService.Collected collected) {
            Doctor d = p.getDoctor();
            return new PrescriptionResponse(
                    p.getId(), p.getAppointment().getId(), p.getIssuedAt(),
                    d.getUser().getFullName(), d.getSpecialization(),
                    p.getDiagnosis(), p.getDiagnosisCode(), p.getNotes(), p.getSupersedesId() != null, dispensedAt,
                    collected == null ? null : collected.at(), collected == null ? null : collected.storeName(),
                    p.getScanId() != null, p.getItems().stream().map(ItemResponse::from).toList());
        }
    }

    public record ItemResponse(
            UUID medicineId,
            String medicine,
            String strength,
            String form,
            String dosage,
            String frequency,
            int durationDays,
            int quantity,
            String genericName,
            /** A cheaper brand with the same ingredients may be given instead. */
            boolean substitutionAllowed
    ) {
        static ItemResponse from(PrescriptionItem i) {
            return new ItemResponse(
                    i.getMedicine().getId(), i.getMedicine().getName(), i.getMedicine().getStrength(),
                    i.getMedicine().getForm().name(), i.getDosage(), i.getFrequency(),
                    i.getDurationDays(), i.getQuantity(), i.getMedicine().getGenericName(),
                    i.isSubstitutionAllowed());
        }
    }
}
