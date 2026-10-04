package com.medicity.chemist;

import com.medicity.audit.AuditLog;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

/**
 * A pharmacist's view of their own shop.
 *
 * <p>No shop id in the routes: "my shop" comes from the token, so one
 * pharmacist cannot name another's shop to edit. The drug licence and the
 * ACTIVE/SUSPENDED status are absent from the update on purpose: both are
 * facts the administrator vouches for, not the shop.
 */
@RestController
@RequestMapping("/api/v1/pharmacies/me")
@PreAuthorize("hasRole('PHARMACIST')")
@RequiredArgsConstructor
@Tag(name = "Pharmacist")
public class PharmacistController {

    private final PharmacyRepository pharmacyRepository;
    private final AuditLog auditLog;

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "The caller's shop")
    public ShopResponse myShop(@AuthenticationPrincipal AppUserPrincipal principal) {
        return ShopResponse.from(shopOf(principal));
    }

    @PutMapping
    @Transactional
    @Operation(summary = "Update the caller's shop details and opening hours")
    public ShopResponse update(@AuthenticationPrincipal AppUserPrincipal principal,
                               @Valid @RequestBody UpdateShopRequest request) {
        if (request.opensAt().equals(request.closesAt())) {
            throw new ValidationException("INVALID_HOURS", "Opening and closing time must differ");
        }
        Pharmacy shop = shopOf(principal);
        shop.setName(request.name().trim());
        shop.setPhone(request.phone());
        shop.setAddressLine(request.addressLine().trim());
        shop.setCity(request.city().trim());
        shop.setPincode(request.pincode() == null || request.pincode().isBlank() ? null : request.pincode().trim());
        shop.setLatitude(request.latitude());
        shop.setLongitude(request.longitude());
        shop.setOpensAt(request.opensAt());
        shop.setClosesAt(request.closesAt());
        pharmacyRepository.saveAndFlush(shop);

        auditLog.recordChange("PHARMACY_UPDATED", "PHARMACY", shop.getId(), Map.of("by", "pharmacist"));
        return ShopResponse.from(shop);
    }

    private Pharmacy shopOf(AppUserPrincipal principal) {
        return pharmacyRepository.findByUserId(principal.getId())
                .orElseThrow(() -> new NotFoundException("Pharmacy for user", principal.getId()));
    }

    public record UpdateShopRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "Phone must be 10-15 digits") String phone,
            @NotBlank @Size(max = 200) String addressLine,
            @NotBlank @Size(max = 80) String city,
            @Pattern(regexp = "^$|^[A-Za-z0-9 -]{3,10}$", message = "Pincode must be 3-10 letters or digits") String pincode,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull LocalTime opensAt,
            @NotNull LocalTime closesAt
    ) {}

    public record ShopResponse(
            UUID id,
            String name,
            String drugLicenceNumber,
            String phone,
            String addressLine,
            String city,
            String pincode,
            double latitude,
            double longitude,
            LocalTime opensAt,
            LocalTime closesAt,
            Pharmacy.Status status
    ) {
        static ShopResponse from(Pharmacy p) {
            return new ShopResponse(p.getId(), p.getName(), p.getDrugLicenceNumber(), p.getPhone(),
                    p.getAddressLine(), p.getCity(), p.getPincode(), p.getLatitude(), p.getLongitude(),
                    p.getOpensAt(), p.getClosesAt(), p.getStatus());
        }
    }
}
