package com.medicity.chemist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyRepository extends JpaRepository<Pharmacy, UUID> {

    Optional<Pharmacy> findByUserId(UUID userId);

    /**
     * Every shop for the admin screen, newest first. The owner is fetched in the
     * same query: with open-in-view off, touching the lazy user afterwards would
     * throw, and loading it per row would be one query per shop.
     */
    @Query("SELECT p FROM Pharmacy p JOIN FETCH p.user ORDER BY p.createdAt DESC")
    List<Pharmacy> findAllWithUser();

    @Query("SELECT p FROM Pharmacy p JOIN FETCH p.user WHERE p.id = :id")
    Optional<Pharmacy> findWithUserById(@Param("id") UUID id);

    /**
     * Active shops within {@code radiusKm} of a point, nearest first.
     *
     * <p>The inner query narrows on the indexed latitude/longitude box, which is
     * a superset of the circle; the outer one applies the exact great-circle
     * (haversine) distance. The box bounds are computed by the caller because
     * the longitude span depends on the latitude, and a box that is too small
     * would silently drop shops that are inside the radius.
     *
     * <p>{@code least(1.0, ...)} guards {@code asin} against a value a hair
     * above 1 from floating-point rounding, which would otherwise raise an error
     * for two points on opposite sides of the earth.
     */
    @Query(value = """
            SELECT * FROM (
                SELECT p.id                          AS "id",
                       p.name                        AS "name",
                       p.phone                       AS "phone",
                       p.address_line                AS "addressLine",
                       p.city                        AS "city",
                       p.pincode                     AS "pincode",
                       p.latitude                    AS "latitude",
                       p.longitude                   AS "longitude",
                       to_char(p.opens_at, 'HH24:MI')  AS "opensAt",
                       to_char(p.closes_at, 'HH24:MI') AS "closesAt",
                       p.timezone                    AS "timezone",
                       6371.0088 * 2 * asin(sqrt(least(1.0,
                           sin(radians(p.latitude - :lat) / 2) * sin(radians(p.latitude - :lat) / 2)
                           + cos(radians(:lat)) * cos(radians(p.latitude))
                             * sin(radians(p.longitude - :lng) / 2) * sin(radians(p.longitude - :lng) / 2)
                       ))) AS "distanceKm"
                FROM pharmacies p
                WHERE p.status = 'ACTIVE'
                  AND p.latitude  BETWEEN :minLat AND :maxLat
                  AND p.longitude BETWEEN :minLng AND :maxLng
            ) near
            WHERE near."distanceKm" <= :radiusKm
            ORDER BY near."distanceKm", near."id"
            LIMIT :limit
            """, nativeQuery = true)
    List<NearbyRow> findNearby(@Param("lat") double lat,
                               @Param("lng") double lng,
                               @Param("radiusKm") double radiusKm,
                               @Param("minLat") double minLat,
                               @Param("maxLat") double maxLat,
                               @Param("minLng") double minLng,
                               @Param("maxLng") double maxLng,
                               @Param("limit") int limit);

    /**
     * Projection of {@link #findNearby}; getter names match the column aliases,
     * which are quoted in the SQL because PostgreSQL folds unquoted aliases to
     * lower case and {@code addressLine} would then never be found.
     */
    interface NearbyRow {
        UUID getId();
        String getName();
        String getPhone();
        String getAddressLine();
        String getCity();
        String getPincode();
        double getLatitude();
        double getLongitude();
        String getOpensAt();
        String getClosesAt();
        String getTimezone();
        double getDistanceKm();
    }
}
