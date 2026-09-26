package com.medicity.scheduling;

import com.medicity.audit.AuditLog;
import com.medicity.common.ForbiddenException;
import com.medicity.common.NotFoundException;
import com.medicity.doctor.DoctorRepository;
import com.medicity.patient.PatientRepository;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
@Tag(name = "Appointments")
public class AppointmentController {

    private final BookingService bookingService;
    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final AuditLog auditLog;

    /**
     * Books a slot for the calling patient.
     *
     * <p>The patient id comes from the authenticated principal, never from the
     * request body. Accepting a {@code patientId} field would let any logged-in
     * user book appointments in someone else's name — the classic IDOR.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Book an appointment slot")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Booked"),
            @ApiResponse(responseCode = "409", description = "Slot taken by another patient"),
            @ApiResponse(responseCode = "422", description = "Slot blocked or too close to now")
    })
    public AppointmentResponse book(@AuthenticationPrincipal AppUserPrincipal principal,
                                    @Valid @RequestBody BookRequest request) {

        UUID patientId = patientRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new NotFoundException("Patient profile for user", principal.getId()))
                .getId();

        return AppointmentResponse.from(
                bookingService.book(request.slotId(), patientId, request.reason()));
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
            UUID patientId = patientRepository.findByUserId(principal.getId())
                    .orElseThrow(() -> new NotFoundException("Patient profile", principal.getId()))
                    .getId();
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
            boolean owns = patientRepository.findByUserId(principal.getId())
                    .map(p -> p.getId().equals(appointment.getPatient().getId()))
                    .orElse(false);
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

    public record BookRequest(
            @NotNull UUID slotId,
            @Size(max = 500) String reason
    ) {}

    public record CancelRequest(@Size(max = 300) String reason) {}

    public record AppointmentResponse(
            UUID id,
            UUID slotId,
            UUID patientId,
            String status,
            Instant scheduledAt,
            String reason,
            Instant cancelledAt
    ) {
        static AppointmentResponse from(Appointment a) {
            return new AppointmentResponse(
                    a.getId(),
                    a.getSlot().getId(),
                    a.getPatient().getId(),
                    a.getStatus().name(),
                    a.getScheduledAt(),
                    a.getReason(),
                    a.getCancelledAt());
        }
    }
}
