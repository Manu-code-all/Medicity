package com.medicity.chemist;

import com.medicity.common.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * "Which chemists are near me?"
 *
 * <p>Requires a sign-in even though shop details are not secret: the request
 * carries the caller's location, and an open endpoint would invite bulk
 * scraping of every shop's phone number by sweeping coordinates. Nothing about
 * the caller's position is stored or logged.
 */
@RestController
@RequestMapping("/api/v1/pharmacies")
@RequiredArgsConstructor
@Tag(name = "Pharmacies")
public class NearbyPharmacyController {

    static final double DEFAULT_RADIUS_KM = 5;
    static final double MAX_RADIUS_KM = 50;
    static final int MAX_RESULTS = 50;

    private final PharmacyRepository pharmacyRepository;
    private final Clock clock;

    @GetMapping("/nearby")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Active pharmacies near a point, nearest first")
    public List<NearbyPharmacyResponse> nearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "" + DEFAULT_RADIUS_KM) double radiusKm,
            @RequestParam(defaultValue = "20") int limit) {

        // Written as "is inside the valid range", negated, so that NaN (which
        // compares false against everything) is rejected along with the
        // out-of-range values instead of slipping past a "< lo || > hi" test.
        if (!(lat >= -90 && lat <= 90) || !(lng >= -180 && lng <= 180)) {
            throw new BadRequestException("INVALID_LOCATION",
                    "Latitude must be between -90 and 90, and longitude between -180 and 180");
        }
        if (!(radiusKm > 0 && radiusKm <= MAX_RADIUS_KM)) {
            throw new BadRequestException("INVALID_RADIUS",
                    "Radius must be more than 0 and at most " + (int) MAX_RADIUS_KM + " km");
        }
        int cappedLimit = Math.min(Math.max(limit, 1), MAX_RESULTS);

        PharmacyLocation box = PharmacyLocation.boxAround(lat, lng, radiusKm);
        Instant now = clock.instant();

        return pharmacyRepository.findNearby(lat, lng, radiusKm,
                        box.minLat(), box.maxLat(), box.minLng(), box.maxLng(), cappedLimit)
                .stream()
                .map(row -> NearbyPharmacyResponse.from(row, now))
                .toList();
    }

    public record NearbyPharmacyResponse(
            UUID id,
            String name,
            String phone,
            String addressLine,
            String city,
            String pincode,
            double latitude,
            double longitude,
            /** Straight-line distance, rounded to 10 m. Not a walking distance. */
            double distanceKm,
            String opensAt,
            String closesAt,
            boolean openNow
    ) {
        static NearbyPharmacyResponse from(PharmacyRepository.NearbyRow row, Instant now) {
            boolean open = Pharmacy.isOpen(LocalTime.parse(row.getOpensAt()),
                    LocalTime.parse(row.getClosesAt()), row.getTimezone(), now);
            return new NearbyPharmacyResponse(row.getId(), row.getName(), row.getPhone(),
                    row.getAddressLine(), row.getCity(), row.getPincode(),
                    row.getLatitude(), row.getLongitude(),
                    Math.round(row.getDistanceKm() * 100.0) / 100.0,
                    row.getOpensAt(), row.getClosesAt(), open);
        }
    }
}
