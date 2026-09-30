package com.medicity.doctor;

import com.medicity.doctor.DoctorHoursService.Window;
import com.medicity.doctor.DoctorSignUpService.Registration;
import com.medicity.security.AppUserPrincipal;
import com.medicity.security.AuthService.TokenPair;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** A doctor's own account: signing up, the profile, weekly hours; and the administrator's check. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Doctor accounts")
public class DoctorAccountController {

    private final DoctorSignUpService signUp;
    private final DoctorHoursService hours;
    private final DoctorRepository doctors;

    /** Under /auth so it is reachable without a token, like patient and chemist sign-up. */
    @PostMapping("/api/v1/auth/register/doctor")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Register as a doctor",
            description = "The doctor is not listed or bookable until an administrator checks the registration number.")
    public TokenPair register(@Valid @RequestBody DoctorRegistration r) {
        return signUp.register(new Registration(r.email(), r.password(), r.fullName(), r.phone(), r.specialization(),
                r.medicalCouncil(), r.registrationNumber(), r.qualification(), r.yearsExperience(),
                r.consultationFee(), r.bio()));
    }

    @GetMapping("/api/v1/doctors/me/profile")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "The caller's doctor profile, including whether it is verified")
    public ProfileResponse profile(@AuthenticationPrincipal AppUserPrincipal principal) {
        // Fetched with the account: the response reads its name and email after the query returns.
        Doctor d = doctors.findWithUserByUserId(principal.getId()).orElseThrow();
        return ProfileResponse.from(d);
    }

    @GetMapping("/api/v1/doctors/me/hours")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "The caller's weekly hours")
    public List<Window> hours(@AuthenticationPrincipal AppUserPrincipal principal) {
        return hours.hours(principal.getId());
    }

    @PutMapping("/api/v1/doctors/me/hours")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Replace the caller's weekly hours and open slots for the next four weeks",
            description = "Future slots nobody has booked are replaced; booked ones are kept.")
    public HoursSaved saveHours(@AuthenticationPrincipal AppUserPrincipal principal,
                                @Valid @RequestBody HoursRequest request) {
        List<Window> windows = request.days().stream()
                .map(d -> new Window(d.weekday(), d.startsAt(), d.endsAt(), d.slotMinutes())).toList();
        int opened = hours.replace(principal.getId(), windows);
        return new HoursSaved(hours.hours(principal.getId()), opened);
    }

    @GetMapping("/api/v1/doctors/me/leave")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "The caller's upcoming days off, each with the visits already booked on it")
    public List<DoctorHoursService.Leave> leave(@AuthenticationPrincipal AppUserPrincipal principal) {
        return hours.leave(principal.getId());
    }

    @PostMapping("/api/v1/doctors/me/leave")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Mark a day off",
            description = "No appointments are opened that day and unbooked ones are removed. "
                    + "Visits already booked are kept and counted in the answer.")
    public DoctorHoursService.Leave addLeave(@AuthenticationPrincipal AppUserPrincipal principal,
                                             @Valid @RequestBody LeaveRequest request) {
        return hours.addLeave(principal.getId(), request.day(), request.note());
    }

    @DeleteMapping("/api/v1/doctors/me/leave/{day}")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Remove a day off; its appointments open again")
    public HoursSaved removeLeave(@AuthenticationPrincipal AppUserPrincipal principal,
                                  @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        int opened = hours.removeLeave(principal.getId(), day);
        return new HoursSaved(hours.hours(principal.getId()), opened);
    }

    @GetMapping("/api/v1/admin/doctors/pending")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Doctors waiting for their registration number to be checked, oldest first")
    public List<ProfileResponse> pending() {
        return signUp.pending().stream().map(ProfileResponse::from).toList();
    }

    @PostMapping("/api/v1/admin/doctors/{doctorId}/verify")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Record that the registration number was checked; the doctor becomes bookable")
    public ProfileResponse verify(@PathVariable UUID doctorId) {
        return ProfileResponse.from(signUp.verify(doctorId));
    }

    // --- request and response shapes -------------------------------------

    public record DoctorRegistration(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 5, max = 128, message = "Password must be at least 5 characters") String password,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = "^\\+?[0-9 ]{10,16}$", message = "Mobile number must be 10 digits") String phone,
            @NotBlank String specialization,
            @NotBlank @Size(max = 80) String medicalCouncil,
            @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9/ -]+$",
                    message = "Letters, digits, spaces, '/' and '-' only") String registrationNumber,
            @NotBlank @Size(max = 120) String qualification,
            @Min(0) @Max(70) int yearsExperience,
            @NotNull @DecimalMin("0") @DecimalMax("100000") BigDecimal consultationFee,
            @Size(max = 1000) String bio
    ) {}

    /** Up to three sessions on each of the seven days. */
    public record HoursRequest(@NotNull @Size(max = 21) List<@Valid Day> days) {}

    public record LeaveRequest(@NotNull LocalDate day, @Size(max = 120) String note) {}

    public record Day(
            @Min(1) @Max(7) int weekday,
            @NotNull LocalTime startsAt,
            @NotNull LocalTime endsAt,
            @NotNull Integer slotMinutes
    ) {
        @AssertTrue(message = "Appointments can be 10, 15, 20, 30, 45 or 60 minutes")
        public boolean isSlotLengthAllowed() {
            return slotMinutes != null && List.of(10, 15, 20, 30, 45, 60).contains(slotMinutes);
        }
    }

    public record HoursSaved(List<Window> hours, int slotsOpened) {}

    public record ProfileResponse(UUID id, String fullName, String email, String specialization,
                                  String medicalCouncil, String registrationNumber, String qualification,
                                  int yearsExperience, BigDecimal consultationFee, boolean verified,
                                  Instant verifiedAt, Instant registeredAt) {
        static ProfileResponse from(Doctor d) {
            return new ProfileResponse(d.getId(), d.getUser().getFullName(), d.getUser().getEmail(),
                    d.getSpecialization(), d.getMedicalCouncil(), d.getLicenseNumber(), d.getQualification(),
                    d.getYearsExperience(), d.getConsultationFee(), d.isVerified(), d.getVerifiedAt(),
                    d.getCreatedAt());
        }
    }
}
