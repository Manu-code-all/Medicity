package com.medicity.chemist;

import com.medicity.common.ValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Pharmacy onboarding. Self-registration is deliberately not offered: a shop is
 * shown to patients who are unwell, so an administrator checks the drug licence
 * before the shop exists. Taking the role from a public request body would let
 * anyone list a fake chemist.
 */
@RestController
@RequestMapping("/api/v1/admin/pharmacies")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Pharmacy administration")
public class PharmacyAdminController {

    private final PharmacyProvisioningService provisioning;
    private final PharmacyRepository pharmacyRepository;

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Every pharmacy, newest first")
    public List<AdminPharmacyResponse> list() {
        return pharmacyRepository.findAllWithUser().stream()
                .map(AdminPharmacyResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a pharmacist account together with its shop")
    public AdminPharmacyResponse create(@Valid @RequestBody CreatePharmacyRequest r) {
        // Not in the record's constructor: an exception thrown while Jackson builds
        // the record surfaces as a generic "body could not be read", losing the code.
        if (r.opensAt().equals(r.closesAt())) {
            throw new ValidationException("INVALID_HOURS", "Opening and closing time must differ");
        }
        Pharmacy created = provisioning.create(new PharmacyProvisioningService.NewPharmacy(
                r.email(), r.password(), r.ownerName(), r.name(), r.drugLicenceNumber(), r.phone(),
                r.addressLine(), r.city(), r.pincode(), r.latitude(), r.longitude(),
                r.opensAt(), r.closesAt()));
        return AdminPharmacyResponse.from(created);
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Hide a shop from search")
    public AdminPharmacyResponse suspend(@PathVariable UUID id) {
        return AdminPharmacyResponse.from(provisioning.setStatus(id, Pharmacy.Status.SUSPENDED));
    }

    @PostMapping("/{id}/reinstate")
    @Operation(summary = "Show a suspended shop in search again")
    public AdminPharmacyResponse reinstate(@PathVariable UUID id) {
        return AdminPharmacyResponse.from(provisioning.setStatus(id, Pharmacy.Status.ACTIVE));
    }

    public record CreatePharmacyRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 128, message = "Password must be at least 12 characters") String password,
            @NotBlank @Size(max = 120) String ownerName,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 40) String drugLicenceNumber,
            @NotBlank @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "Phone must be 10-15 digits") String phone,
            @NotBlank @Size(max = 200) String addressLine,
            @NotBlank @Size(max = 80) String city,
            @Pattern(regexp = "^$|^[A-Za-z0-9 -]{3,10}$", message = "Pincode must be 3-10 letters or digits") String pincode,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull LocalTime opensAt,
            @NotNull LocalTime closesAt
    ) {}

    public record AdminPharmacyResponse(
            UUID id,
            UUID userId,
            String ownerEmail,
            String name,
            String drugLicenceNumber,
            String phone,
            String addressLine,
            String city,
            double latitude,
            double longitude,
            Pharmacy.Status status
    ) {
        static AdminPharmacyResponse from(Pharmacy p) {
            return new AdminPharmacyResponse(p.getId(), p.getUser().getId(), p.getUser().getEmail(),
                    p.getName(), p.getDrugLicenceNumber(), p.getPhone(), p.getAddressLine(), p.getCity(),
                    p.getLatitude(), p.getLongitude(), p.getStatus());
        }
    }
}
