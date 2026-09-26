package com.medicity.patient;

import com.medicity.clinical.Prescription;
import com.medicity.clinical.PrescriptionItem;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.doctor.Doctor;
import com.medicity.pharmacy.Dispensation;
import com.medicity.pharmacy.DispensationRepository;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentRepository;
import com.medicity.scheduling.AppointmentStatus;
import com.medicity.security.AppUserPrincipal;
import com.medicity.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
    private final AppointmentRepository appointmentRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final DispensationRepository dispensationRepository;
    private final Clock clock;

    @GetMapping
    @Operation(summary = "The caller's patient profile")
    public ProfileResponse profile(@AuthenticationPrincipal AppUserPrincipal principal) {
        Patient patient = patientRepository.findWithUserByUserId(principal.getId())
                .orElseThrow(() -> new NotFoundException("Patient profile for user", principal.getId()));
        return ProfileResponse.from(patient);
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
        return result.map(VisitResponse::from);
    }

    @GetMapping("/prescriptions")
    @Operation(summary = "The caller's current prescriptions, newest first")
    public List<PrescriptionResponse> prescriptions(@AuthenticationPrincipal AppUserPrincipal principal) {
        List<Prescription> current = prescriptionRepository.findCurrentForPatient(patientId(principal));
        Map<UUID, Instant> dispensedAt = dispensationRepository
                .findByPrescriptionIdIn(current.stream().map(Prescription::getId).toList()).stream()
                .collect(Collectors.toMap(Dispensation::getPrescriptionId, Dispensation::getDispensedAt));
        return current.stream()
                .map(p -> PrescriptionResponse.from(p, dispensedAt.get(p.getId())))
                .toList();
    }

    private UUID patientId(AppUserPrincipal principal) {
        return patientRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new NotFoundException("Patient profile for user", principal.getId()))
                .getId();
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

    public record ProfileResponse(
            String fullName,
            String email,
            String phone,
            LocalDate dateOfBirth,
            String gender,
            String bloodGroup,
            String addressLine,
            String city,
            String emergencyContact,
            Instant memberSince
    ) {
        static ProfileResponse from(Patient p) {
            User u = p.getUser();
            return new ProfileResponse(
                    u.getFullName(), u.getEmail(), u.getPhone(),
                    p.getDateOfBirth(), p.getGender().name(), p.getBloodGroup(),
                    p.getAddressLine(), p.getCity(), p.getEmergencyContact(),
                    u.getCreatedAt());
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
            String cancelReason
    ) {
        public static VisitResponse from(Appointment a) {
            Doctor d = a.getSlot().getDoctor();
            return new VisitResponse(
                    a.getId(), a.getStatus().name(), a.getScheduledAt(), a.getSlot().getEndsAt(),
                    a.getReason(), d.getId(), d.getUser().getFullName(), d.getSpecialization(),
                    a.getCancelledAt(), a.getCancelReason());
        }
    }

    public record PrescriptionResponse(
            UUID id,
            UUID appointmentId,
            Instant issuedAt,
            String doctorName,
            String specialization,
            String diagnosis,
            String notes,
            boolean revised,
            /** When the pharmacy filled it; null if not yet dispensed. */
            Instant dispensedAt,
            List<ItemResponse> items
    ) {
        public static PrescriptionResponse from(Prescription p, Instant dispensedAt) {
            Doctor d = p.getDoctor();
            return new PrescriptionResponse(
                    p.getId(), p.getAppointment().getId(), p.getIssuedAt(),
                    d.getUser().getFullName(), d.getSpecialization(),
                    p.getDiagnosis(), p.getNotes(), p.getSupersedesId() != null, dispensedAt,
                    p.getItems().stream().map(ItemResponse::from).toList());
        }
    }

    public record ItemResponse(
            String medicine,
            String strength,
            String form,
            String dosage,
            String frequency,
            int durationDays,
            int quantity
    ) {
        static ItemResponse from(PrescriptionItem i) {
            return new ItemResponse(
                    i.getMedicine().getName(), i.getMedicine().getStrength(),
                    i.getMedicine().getForm().name(), i.getDosage(), i.getFrequency(),
                    i.getDurationDays(), i.getQuantity());
        }
    }
}
