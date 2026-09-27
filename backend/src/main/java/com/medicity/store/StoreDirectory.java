package com.medicity.store;

import com.medicity.common.ValidationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * "Which stores are near this point?"
 *
 * <p>Two steps in one query. A bounding box around the point (a range on the
 * {@code idx_stores_location} index) cuts the table to the handful of stores in
 * a square around the patient; the exact great-circle distance is then computed
 * only for those, and the square's corners, outside the circle, are dropped.
 * Without the box, every search would compute a distance to every store in
 * the country.
 *
 * <p>The same {@link #WITHIN} fragment chooses the stores a patient's question
 * goes to, so the stores a patient sees listed and the stores that receive the
 * question are the same by construction.
 */
@Component
public class StoreDirectory {

    /** The largest radius a patient may search or ask within. */
    public static final int MAX_RADIUS_M = 10_000;
    /** Metres per degree of latitude; a degree of longitude shrinks by cos(latitude). */
    private static final double METRES_PER_DEGREE = 111_320;

    /**
     * Stores that can receive questions, within a radius of a point. The alias
     * {@code s} must be the stores table. Takes the seven parameters of
     * {@link Area#withinParameters()}.
     *
     * <p>The casts keep the index usable: the columns are numeric, and compared
     * with a bound double they would be converted to double row by row, which
     * an index on the numeric values cannot serve.
     */
    public static final String WITHIN = """
            s.active AND s.verified_at IS NOT NULL
            AND s.latitude BETWEEN CAST(? AS numeric) AND CAST(? AS numeric)
            AND s.longitude BETWEEN CAST(? AS numeric) AND CAST(? AS numeric)
            AND store_distance_m(?, ?, s.latitude, s.longitude) <= ?
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public StoreDirectory(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<NearbyStore> near(double lat, double lng, int radiusM) {
        Area area = Area.around(lat, lng, radiusM);
        return jdbc.query("""
                SELECT s.id, s.name, s.phone, s.address_line, s.city, s.latitude, s.longitude,
                       s.opens_at, s.closes_at, s.open_24h, s.time_zone, s.hold_hours,
                       store_distance_m(?, ?, s.latitude, s.longitude) AS distance_m
                FROM stores s
                WHERE %s
                ORDER BY distance_m, s.name
                LIMIT 50
                """.formatted(WITHIN), (rs, i) -> {
                    boolean open24h = rs.getBoolean("open_24h");
                    LocalTime opens = rs.getTime("opens_at").toLocalTime();
                    LocalTime closes = rs.getTime("closes_at").toLocalTime();
                    ZoneId zone = ZoneId.of(rs.getString("time_zone"));
                    return new NearbyStore(
                            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("phone"),
                            rs.getString("address_line"), rs.getString("city"),
                            rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
                            (int) Math.round(rs.getDouble("distance_m")),
                            opens, closes, open24h,
                            StoreHours.isOpen(open24h, opens, closes, ZonedDateTime.now(clock.withZone(zone))),
                            rs.getInt("hold_hours"));
                }, area.selectParameters());
    }

    /** The search circle and the square around it. */
    public record Area(double lat, double lng, int radiusM,
                       double minLat, double maxLat, double minLng, double maxLng) {

        public static Area around(double lat, double lng, int radiusM) {
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                throw new ValidationException("INVALID_LOCATION", "That is not a location on Earth");
            }
            if (radiusM < 100 || radiusM > MAX_RADIUS_M) {
                throw new ValidationException("INVALID_RADIUS", "Search between 100 m and 10 km");
            }
            double dLat = radiusM / METRES_PER_DEGREE;
            // Near the poles cos(latitude) approaches 0; the box just gets wide there.
            double dLng = radiusM / (METRES_PER_DEGREE * Math.max(Math.cos(Math.toRadians(lat)), 0.01));
            return new Area(lat, lng, radiusM, lat - dLat, lat + dLat, lng - dLng, lng + dLng);
        }

        /** The seven parameters {@link #WITHIN} takes, in order. */
        public Object[] withinParameters() {
            return new Object[]{minLat, maxLat, minLng, maxLng, lat, lng, radiusM};
        }

        /** For a query that selects the distance first: its (lat, lng), then {@link #withinParameters()}. */
        Object[] selectParameters() {
            return new Object[]{lat, lng, minLat, maxLat, minLng, maxLng, lat, lng, radiusM};
        }
    }

    public record NearbyStore(
            UUID id, String name, String phone, String addressLine, String city,
            BigDecimal latitude, BigDecimal longitude,
            int distanceM, LocalTime opensAt, LocalTime closesAt, boolean open24h, boolean openNow,
            int holdHours) {}
}
