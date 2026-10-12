package com.medicity.clinic;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** The platform administrator sets clinics up and switches them on or off. */
@RestController
@RequestMapping("/api/v1/admin/clinics")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Administration")
public class PlatformClinicController {

    private final ClinicService clinics;

    @GetMapping
    @Operation(summary = "Every clinic, active or not, with its head counts")
    public List<ClinicService.ClinicRow> list() {
        return clinics.all();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a clinic and its first administrator",
            description = "The administrator's account gets a temporary password, returned once, that must be changed at first sign-in.")
    public ClinicService.Created create(@Valid @RequestBody CreateRequest request) {
        return clinics.create(new ClinicService.NewClinic(request.clinic().toUpdate(), request.adminFullName(),
                request.adminEmail(), request.adminPhone()));
    }

    @PutMapping("/{clinicId}/active")
    @Operation(summary = "Switch a clinic on or off: an inactive clinic is not listed and its staff cannot work")
    public ClinicService.Profile setActive(@PathVariable UUID clinicId, @Valid @RequestBody ActiveRequest request) {
        return clinics.setActive(clinicId, request.active());
    }

    public record CreateRequest(
            @NotNull @Valid ClinicController.ProfileRequest clinic,
            @NotBlank @Size(max = 120) String adminFullName,
            @NotBlank @Email @Size(max = 254) String adminEmail,
            @Pattern(regexp = "^$|^\\+?[0-9]{10,15}$", message = "Phone must be 10-15 digits") String adminPhone
    ) {}

    public record ActiveRequest(boolean active) {}
}
