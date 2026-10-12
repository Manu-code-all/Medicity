package com.medicity.clinic;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The clinic administrator's workspace: "my clinic". No route takes a clinic
 * id; which clinic it is comes from the signed-in account's staff row
 * ({@link ClinicAccess}), and any account id in a path is checked against it.
 */
@RestController
@RequestMapping("/api/v1/clinics/me")
@RequiredArgsConstructor
@Tag(name = "Clinic administration")
public class ClinicController {

    private static final String PHONE = "^$|^\\+?[0-9]{10,15}$";

    private final ClinicAccess access;
    private final ClinicService clinics;

    @GetMapping
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN','RECEPTIONIST')")
    @Operation(summary = "The caller's clinic")
    public ClinicService.Profile profile(@AuthenticationPrincipal AppUserPrincipal principal) {
        return clinics.profile(access.resolve(principal));
    }

    @PutMapping
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    @Operation(summary = "Update the clinic's profile (name, address, location, contact, time zone)")
    public ClinicService.Profile update(@AuthenticationPrincipal AppUserPrincipal principal,
                                        @Valid @RequestBody ProfileRequest request) {
        return clinics.update(access.resolve(principal), request.toUpdate());
    }

    @GetMapping("/staff")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    @Operation(summary = "The clinic's doctors, receptionists and administrators")
    public List<ClinicService.StaffMember> staff(@AuthenticationPrincipal AppUserPrincipal principal) {
        return clinics.staff(access.resolve(principal));
    }

    @PostMapping("/doctors")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a doctor to the clinic",
            description = "Creates the doctor's account with a temporary password, returned once, that must be "
                    + "changed at first sign-in. The doctor is not listed or bookable until the platform "
                    + "administrator has checked the registration number.")
    public ClinicService.Credentials addDoctor(@AuthenticationPrincipal AppUserPrincipal principal,
                                               @Valid @RequestBody DoctorRequest request) {
        return clinics.addDoctor(access.resolve(principal), request.toNew());
    }

    @PostMapping("/receptionists")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a receptionist to the clinic",
            description = "Creates the account with a temporary password, returned once, that must be changed at first sign-in.")
    public ClinicService.Credentials addReceptionist(@AuthenticationPrincipal AppUserPrincipal principal,
                                                     @Valid @RequestBody ReceptionistRequest request) {
        return clinics.addReceptionist(access.resolve(principal),
                new ClinicService.NewReceptionist(request.fullName(), request.email(), request.phone()));
    }

    @PostMapping("/staff/{userId}/reset-password")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    @Operation(summary = "Give a doctor or receptionist of this clinic a new temporary password")
    public ClinicService.Credentials resetPassword(@AuthenticationPrincipal AppUserPrincipal principal,
                                                   @PathVariable UUID userId) {
        return clinics.resetPassword(access.resolve(principal), userId);
    }

    @PostMapping("/staff/{userId}/disable")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Turn off a doctor's or receptionist's sign-in")
    public void disable(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID userId) {
        clinics.setEnabled(access.resolve(principal), userId, false);
    }

    @PostMapping("/staff/{userId}/enable")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Turn a doctor's or receptionist's sign-in back on")
    public void enable(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID userId) {
        clinics.setEnabled(access.resolve(principal), userId, true);
    }

    @GetMapping("/report")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    @Operation(summary = "Visits per day, no-shows, cancellations and walk-ins for the clinic's doctors",
            description = "Inclusive dates in the clinic's time zone, at most 92 days. A visit counts on the day it was scheduled for.")
    public ClinicService.Report report(@AuthenticationPrincipal AppUserPrincipal principal,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return clinics.report(access.resolve(principal), from, to);
    }

    // --- request shapes -------------------------------------------------------------

    public record ProfileRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 200) String address,
            @Size(max = 80) String city,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @Pattern(regexp = PHONE, message = "Phone must be 10-15 digits") String phone,
            @Email @Size(max = 254) String email,
            @Size(max = 40) String timezone
    ) {
        ClinicService.ProfileUpdate toUpdate() {
            return new ClinicService.ProfileUpdate(name, address, city, latitude, longitude, phone, email, timezone);
        }
    }

    public record DoctorRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @Pattern(regexp = PHONE, message = "Phone must be 10-15 digits") String phone,
            @NotBlank @Size(max = 80) String specialization,
            @NotBlank @Size(max = 80) String medicalCouncil,
            @NotBlank @Size(max = 40) String registrationNumber,
            @NotBlank @Size(max = 120) String qualification,
            @Min(0) @Max(70) int yearsExperience,
            @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal consultationFee
    ) {
        ClinicService.NewDoctor toNew() {
            return new ClinicService.NewDoctor(fullName, email, phone, specialization, medicalCouncil,
                    registrationNumber, qualification, yearsExperience, consultationFee);
        }
    }

    public record ReceptionistRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @Pattern(regexp = PHONE, message = "Phone must be 10-15 digits") String phone
    ) {}
}
