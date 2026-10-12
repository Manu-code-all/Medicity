package com.medicity.clinic;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The clinics the directory can filter by. Public, like the doctors list:
 * a clinic's name, address and contact details, and how many doctors can be
 * booked there. {@code /me} (the clinic's own workspace) is a more specific
 * route and needs a staff account.
 */
@RestController
@RequestMapping("/api/v1/clinics")
@RequiredArgsConstructor
@Tag(name = "Clinics")
@SecurityRequirements
public class ClinicDirectoryController {

    private final ClinicService clinics;

    @GetMapping
    @Operation(summary = "Clinics with at least one bookable doctor")
    public List<ClinicService.Listed> list() {
        return clinics.directory();
    }

    @GetMapping("/{clinicId}")
    @Operation(summary = "One listed clinic")
    public ClinicService.Listed one(@PathVariable UUID clinicId) {
        return clinics.listed(clinicId);
    }
}
