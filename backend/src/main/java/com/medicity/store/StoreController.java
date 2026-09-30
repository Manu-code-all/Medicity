package com.medicity.store;

import com.medicity.security.AppUserPrincipal;
import com.medicity.security.AuthService.TokenPair;
import com.medicity.store.StoreDirectory.NearbyStore;
import com.medicity.store.StoreService.StoreDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Chemists' stores: finding them, a chemist's own profile, and the
 * administrator's licence check.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Stores")
public class StoreController {

    private final StoreService storeService;
    private final StoreDirectory directory;
    private final StoreRepository storeRepository;
    private final Clock clock;

    /** Under /auth so it is reachable without a token, like patient sign-up. */
    @PostMapping("/api/v1/auth/register/chemist")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Register a chemist account and its store",
            description = "The store receives no patient questions until an administrator verifies its licence.")
    public TokenPair register(@Valid @RequestBody ChemistRegistration request) {
        return storeService.register(request.email(), request.password(), request.fullName(),
                request.store().toDetails());
    }

    @GetMapping("/api/v1/stores/nearby")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Verified stores within a radius, nearest first")
    public List<NearbyStore> nearby(@RequestParam double lat, @RequestParam double lng,
                                    @RequestParam(defaultValue = "3000") int radiusM) {
        return directory.near(lat, lng, radiusM);
    }

    @GetMapping("/api/v1/stores/me")
    @PreAuthorize("hasRole('CHEMIST')")
    @Operation(summary = "The caller's store")
    public StoreResponse mine(@AuthenticationPrincipal AppUserPrincipal principal) {
        return StoreResponse.from(storeService.requireOwn(principal.getId()), clock.instant());
    }

    @PutMapping("/api/v1/stores/me")
    @PreAuthorize("hasRole('CHEMIST')")
    @Operation(summary = "Edit the caller's store; the licence number cannot change")
    public StoreResponse update(@AuthenticationPrincipal AppUserPrincipal principal,
                                @Valid @RequestBody StoreRequest request) {
        return StoreResponse.from(storeService.update(principal.getId(), request.toDetails()), clock.instant());
    }

    @GetMapping("/api/v1/admin/stores/pending")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Stores waiting for their licence to be checked, oldest first")
    public List<StoreResponse> pending() {
        Instant now = clock.instant();
        return storeRepository.findUnverified().stream().map(s -> StoreResponse.from(s, now)).toList();
    }

    @PostMapping("/api/v1/admin/stores/{storeId}/verify")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Record that the store's drug licence was checked; it starts receiving questions")
    public StoreResponse verify(@PathVariable UUID storeId) {
        return StoreResponse.from(storeService.verify(storeId), clock.instant());
    }

    // --- request and response shapes -------------------------------------

    public record ChemistRegistration(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 5, max = 128, message = "Password must be at least 5 characters") String password,
            @NotBlank @Size(max = 120) String fullName,
            @NotNull @Valid StoreRequest store
    ) {}

    /** Limits mirror V14's columns and CHECK constraints, so a bad value is a 400, not a 500. */
    public record StoreRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9/ -]+$",
                    message = "Letters, digits, spaces, '/' and '-' only") String licenceNumber,
            @NotBlank @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "Phone must be 10-15 digits") String phone,
            @NotBlank @Size(max = 200) String addressLine,
            @NotBlank @Size(max = 80) String city,
            @DecimalMin("-90") @DecimalMax("90") double latitude,
            @DecimalMin("-180") @DecimalMax("180") double longitude,
            LocalTime opensAt,
            LocalTime closesAt,
            boolean open24h,
            @Min(2) @Max(4) int holdHours
    ) {
        StoreDetails toDetails() {
            return new StoreDetails(name, licenceNumber, phone, addressLine, city, latitude, longitude,
                    opensAt, closesAt, open24h, holdHours);
        }
    }

    public record StoreResponse(
            UUID id, String name, String licenceNumber, String phone, String addressLine, String city,
            BigDecimal latitude, BigDecimal longitude, LocalTime opensAt, LocalTime closesAt, boolean open24h,
            int holdHours, boolean verified, Instant verifiedAt, boolean openNow,
            String ownerName, String ownerEmail, Instant registeredAt
    ) {
        static StoreResponse from(Store s, Instant now) {
            return new StoreResponse(s.getId(), s.getName(), s.getLicenceNumber(), s.getPhone(), s.getAddressLine(),
                    s.getCity(), s.getLatitude(), s.getLongitude(), s.getOpensAt(), s.getClosesAt(), s.isOpen24h(),
                    s.getHoldHours(), s.isVerified(), s.getVerifiedAt(), s.isOpenAt(now),
                    s.getOwner().getFullName(), s.getOwner().getEmail(), s.getCreatedAt());
        }
    }
}
