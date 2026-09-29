package com.medicity.scheduling;

import com.medicity.audit.AuditLog;
import com.medicity.common.ForbiddenException;
import com.medicity.common.Idempotency;
import com.medicity.common.NotFoundException;
import com.medicity.doctor.DoctorRepository;
import com.medicity.patient.ActingPatient;
import com.medicity.security.AppUserPrincipal;
import com.medicity.user.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
@Tag(name = "Appointments")
public class AppointmentController {

    private final BookingService bookingService;
    private final AppointmentRepository appointmentRepository;
    private final ActingPatient acting;
    private final DoctorRepository doctorRepository;
    private final AuditLog auditLog;
    private final Idempotency idempotency;

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /**
     * Books a slot for the calling patient.
     *
     * <p>The patient id comes from the authenticated principal, never from the
     * request body. Accepting a {@code patientId} field would let any logged-in
     * user book appointments in someone else's name — the classic IDOR.
     *
     * <p>With an {@code Idempotency-Key} header, a retry of a booking that
     * already succeeded returns the original 201, marked
     * {@code Idempotent-Replayed: true}, instead of a 409 for a clash with the
     * patient's own new appointment.
     */
    @PostMapping
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Book an appointment slot")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Booked, or a replay of an earlier booking with the same Idempotency-Key"),
            @ApiResponse(responseCode = "409", description = "Slot taken by another patient"),
            @ApiResponse(responseCode = "422", description = "Slot blocked, too close to now, or Idempotency-Key reused for a different request")
    })
    public ResponseEntity<AppointmentResponse> book(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody BookRequest request) {

        UUID patientId = acting.resolve(principal.getId()).getId();
        Supplier<AppointmentResponse> booking = () -> AppointmentResponse.from(
                bookingService.book(request.slotId(), patientId, request.reason(), request.visitType(), request.intake()));

        if (idempotencyKey == null) {
            return ResponseEntity.status(HttpStatus.CREATED).body(booking.get());
        }
        // The patient is part of what the key identifies: the same key and body
        // for another family member is a different request, not a retry.
        Idempotency.Result<AppointmentResponse> result = idempotency.run(principal.getId(), idempotencyKey,
                "POST /api/v1/appointments", new BookingFingerprint(patientId, request), HttpStatus.CREATED.value(),
                AppointmentResponse.class, booking);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replayed", Boolean.toString(result.replayed()))
                .body(result.body());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Cancel an appointment, releasing its slot")
    public AppointmentResponse cancel(@AuthenticationPrincipal AppUserPrincipal principal,
                                      @PathVariable UUID id,
                                      @Valid @RequestBody CancelRequest request) {

        Appointment appointment = appointmentRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new NotFoundException("Appointment", id));

        requireAccess(principal, appointment, "cancel");
        return AppointmentResponse.from(bookingService.cancel(id, request.reason()));
    }

    /**
     * Moves an upcoming visit to another time with the same doctor: the old
     * time is released and the new one booked together, or nothing changes.
     */
    @PostMapping("/{id}/reschedule")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Move a visit to another time with the same doctor")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Moved; the response is the new appointment"),
            @ApiResponse(responseCode = "409", description = "The new time was just taken; the visit is unchanged"),
            @ApiResponse(responseCode = "422", description = "Not an upcoming visit, a different doctor, or too soon")
    })
    public AppointmentResponse reschedule(@AuthenticationPrincipal AppUserPrincipal principal,
                                          @PathVariable UUID id,
                                          @Valid @RequestBody RescheduleRequest request) {

        Appointment appointment = appointmentRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new NotFoundException("Appointment", id));

        requireAccess(principal, appointment, "reschedule");
        return AppointmentResponse.from(bookingService.reschedule(id, request.slotId()));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('PATIENT','DOCTOR')")
    @Operation(summary = "List the caller's own appointments")
    public Page<AppointmentResponse> mine(@AuthenticationPrincipal AppUserPrincipal principal,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {

        // Capped so a client cannot request a single unbounded page and turn one
        // request into a full table scan.
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));

        if (principal.getRole() == Role.PATIENT) {
            UUID patientId = acting.resolve(principal.getId()).getId();
            return appointmentRepository.findForPatient(patientId, pageable)
                    .map(AppointmentResponse::from);
        }

        UUID doctorId = doctorRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new NotFoundException("Doctor profile", principal.getId()))
                .getId();
        return appointmentRepository.findForDoctor(doctorId, pageable)
                .map(AppointmentResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Fetch one appointment")
    public AppointmentResponse get(@AuthenticationPrincipal AppUserPrincipal principal,
                                   @PathVariable UUID id) {

        Appointment appointment = appointmentRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new NotFoundException("Appointment", id));

        requireAccess(principal, appointment, "read");

        // A patient reading their own record is routine. Anyone else reading it
        // (the treating doctor, an admin) is the access an audit exists to show.
        if (principal.getRole() != Role.PATIENT) {
            auditLog.recordIndependently("APPOINTMENT_VIEWED", "APPOINTMENT", id,
                    AuditLog.Outcome.SUCCESS, Map.of("patientId", appointment.getPatient().getId()));
        }
        return AppointmentResponse.from(appointment);
    }

    /**
     * Row-level authorization.
     *
     * <p>Role alone is not enough here: every patient has {@code ROLE_PATIENT},
     * so {@code @PreAuthorize("hasRole('PATIENT')")} would happily let patient A
     * read patient B's appointment by guessing an id. Access is therefore decided
     * per row, by ownership.
     */
    private void requireAccess(AppUserPrincipal principal, Appointment appointment, String operation) {
        if (principal.getRole() == Role.ADMIN) {
            return;
        }
        if (principal.getRole() == Role.PATIENT) {
            // The account holder's own visits and their family members'.
            boolean owns = principal.getId().equals(appointment.getPatient().accountUserId());
            if (owns) {
                return;
            }
        }
        if (principal.getRole() == Role.DOCTOR) {
            boolean treats = doctorRepository.findByUserId(principal.getId())
                    .map(d -> d.getId().equals(appointment.getSlot().getDoctor().getId()))
                    .orElse(false);
            if (treats) {
                return;
            }
        }
        // Recorded in its own transaction: the exception below rolls back this
        // request, and a denial is precisely the event worth keeping.
        auditLog.recordIndependently("ACCESS_DENIED", "APPOINTMENT", appointment.getId(),
                AuditLog.Outcome.DENIED, Map.of("operation", operation));

        // Same response as "not found" would give, so probing ids cannot confirm
        // which appointments exist.
        throw new ForbiddenException("You do not have access to this appointment");
    }

    /** What an Idempotency-Key identifies: whom the booking is for, and what was asked. */
    record BookingFingerprint(UUID patientId, BookRequest request) {}

    public record BookRequest(
            @NotNull UUID slotId,
            @Size(max = 500) String reason,
            /** In person unless the patient chose video. */
            VisitType visitType,
            /** The body guide's answers, when the patient chose to share them with the doctor. */
            @Valid Intake intake
    ) {}

    public record CancelRequest(@Size(max = 300) String reason) {}

    public record RescheduleRequest(@NotNull UUID slotId) {}

    public record AppointmentResponse(
            UUID id,
            UUID slotId,
            UUID patientId,
            String status,
            Instant scheduledAt,
            String reason,
            Instant cancelledAt,
            UUID rescheduledFrom,
            String visitType
    ) {
        static AppointmentResponse from(Appointment a) {
            return new AppointmentResponse(
                    a.getId(),
                    a.getSlot().getId(),
                    a.getPatient().getId(),
                    a.getStatus().name(),
                    a.getScheduledAt(),
                    a.getReason(),
                    a.getCancelledAt(),
                    a.getRescheduledFrom(),
                    a.getVisitType().name());
        }
    }
}
