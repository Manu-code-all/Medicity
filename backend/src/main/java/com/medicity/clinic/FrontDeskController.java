package com.medicity.clinic;

import com.medicity.queue.QueueService;
import com.medicity.patient.Patient;
import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The front desk of the caller's clinic: receptionists, and the clinic
 * administrator who may do the same. Nothing here takes a clinic id; see
 * {@link ClinicAccess}.
 */
@RestController
@RequestMapping("/api/v1/clinics/me/desk")
@PreAuthorize("hasAnyRole('RECEPTIONIST','CLINIC_ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Clinic front desk")
public class FrontDeskController {

    private static final String PHONE = "^$|^\\+?[0-9]{10,15}$";

    private final ClinicAccess access;
    private final FrontDeskService desk;

    @GetMapping("/today")
    @Operation(summary = "The clinic's doctors, the day's visits and today's walk-in lines",
            description = "`date` (the clinic's own calendar) defaults to today. Walk-in lines are always today's.")
    public FrontDeskService.Today today(@AuthenticationPrincipal AppUserPrincipal principal,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return desk.today(access.resolve(principal), date);
    }

    @PostMapping("/visits/{appointmentId}/arrived")
    @Operation(summary = "Mark a booked patient as arrived")
    public FrontDeskService.VisitLine arrived(@AuthenticationPrincipal AppUserPrincipal principal,
                                              @PathVariable UUID appointmentId) {
        return desk.markArrived(access.resolve(principal), appointmentId);
    }

    @PostMapping("/visits/{appointmentId}/no-show")
    @Operation(summary = "Mark a booked patient as not having come")
    public FrontDeskService.VisitLine noShow(@AuthenticationPrincipal AppUserPrincipal principal,
                                             @PathVariable UUID appointmentId) {
        return desk.markNoShow(access.resolve(principal), appointmentId);
    }

    @PostMapping("/visits/{appointmentId}/cancel")
    @Operation(summary = "Cancel a visit for a patient who phoned in")
    public FrontDeskService.VisitLine cancel(@AuthenticationPrincipal AppUserPrincipal principal,
                                             @PathVariable UUID appointmentId,
                                             @Valid @RequestBody(required = false) CancelRequest request) {
        return desk.cancel(access.resolve(principal), appointmentId, request == null ? null : request.reason());
    }

    @PostMapping("/visits")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Book a slot of one of the clinic's doctors for a patient who phoned in")
    public FrontDeskService.VisitLine book(@AuthenticationPrincipal AppUserPrincipal principal,
                                           @Valid @RequestBody BookRequest request) {
        return desk.book(access.resolve(principal), request.slotId(), request.patient().toRef(), request.reason());
    }

    @PostMapping("/walk-ins")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a walk-in token for one of the clinic's doctors")
    public QueueService.Token walkIn(@AuthenticationPrincipal AppUserPrincipal principal,
                                     @Valid @RequestBody WalkInRequest request) {
        return desk.addWalkIn(access.resolve(principal), request.doctorId(), request.patient().toRef(), request.reason());
    }

    @GetMapping("/patients")
    @Operation(summary = "Find a patient: this clinic's recorded patients by name or phone, or a Medicity account by its whole email")
    public List<FrontDeskService.PatientHit> patients(@AuthenticationPrincipal AppUserPrincipal principal,
                                                      @RequestParam("q") String q) {
        return desk.searchPatients(access.resolve(principal), q);
    }

    @PostMapping("/patients")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record someone who has no Medicity account, for this clinic's desk")
    public FrontDeskService.PatientHit registerPatient(@AuthenticationPrincipal AppUserPrincipal principal,
                                                       @Valid @RequestBody NewPatientRequest request) {
        return desk.registerPatient(access.resolve(principal), request.toNew());
    }

    // --- request shapes ----------------------------------------------------------------

    public record CancelRequest(@Size(max = 300) String reason) {}

    public record NewPatientRequest(
            @NotBlank @Size(max = 120) String fullName,
            @Pattern(regexp = PHONE, message = "Phone must be 10-15 digits") String phone,
            @Min(0) @Max(120) int ageYears,
            Patient.Gender gender
    ) {
        FrontDeskService.NewPatient toNew() {
            return new FrontDeskService.NewPatient(fullName, phone, ageYears, gender);
        }
    }

    /** Exactly one of the three. */
    public record PatientRequest(
            UUID patientId,
            @Email @Size(max = 254) String email,
            @Valid NewPatientRequest newPatient
    ) {
        FrontDeskService.PatientRef toRef() {
            return new FrontDeskService.PatientRef(patientId, email, newPatient == null ? null : newPatient.toNew());
        }
    }

    public record BookRequest(@NotNull UUID slotId, @NotNull @Valid PatientRequest patient,
                              @Size(max = 500) String reason) {}

    public record WalkInRequest(@NotNull UUID doctorId, @NotNull @Valid PatientRequest patient,
                                @Size(max = 300) String reason) {}
}
