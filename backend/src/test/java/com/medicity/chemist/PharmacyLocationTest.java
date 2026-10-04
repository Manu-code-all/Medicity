package com.medicity.chemist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bounding box must never be smaller than the circle it stands in for: a
 * shop inside the radius that the box excludes would be silently missing from
 * results, and no later exact-distance check can bring it back.
 */
@DisplayName("Search bounding box")
class PharmacyLocationTest {

    private static final double EARTH_KM = 6371.0088;

    /** The point {@code km} away from (lat, lng) on the given compass bearing, on a sphere. */
    private static double[] destination(double lat, double lng, double bearingDeg, double km) {
        double d = km / EARTH_KM;
        double b = Math.toRadians(bearingDeg);
        double phi1 = Math.toRadians(lat);
        double lambda1 = Math.toRadians(lng);
        double phi2 = Math.asin(Math.sin(phi1) * Math.cos(d) + Math.cos(phi1) * Math.sin(d) * Math.cos(b));
        double lambda2 = lambda1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(phi1),
                Math.cos(d) - Math.sin(phi1) * Math.sin(phi2));
        return new double[]{Math.toDegrees(phi2), Math.toDegrees(lambda2)};
    }

    @Test
    @DisplayName("contains points exactly on the radius in every direction, at any latitude")
    void containsTheWholeCircle() {
        double[] latitudes = {0, 12.97, 45, 60, 75, -33.9};
        double[] radii = {0.5, 5, 50};
        for (double lat : latitudes) {
            for (double radius : radii) {
                PharmacyLocation box = PharmacyLocation.boxAround(lat, 10.0, radius);
                for (int bearing = 0; bearing < 360; bearing += 5) {
                    double[] p = destination(lat, 10.0, bearing, radius * 0.999);
                    assertThat(p[0]).as("lat at %s,%s bearing %s", lat, radius, bearing)
                            .isBetween(box.minLat(), box.maxLat());
                    assertThat(p[1]).as("lng at %s,%s bearing %s", lat, radius, bearing)
                            .isBetween(box.minLng(), box.maxLng());
                }
            }
        }
    }

    @Test
    @DisplayName("across the antimeridian, longitude is left unbounded rather than wrapped wrongly")
    void antimeridian() {
        PharmacyLocation box = PharmacyLocation.boxAround(10, 179.99, 20);
        assertThat(box.minLng()).isEqualTo(-180);
        assertThat(box.maxLng()).isEqualTo(180);
    }

    @Test
    @DisplayName("near a pole the box stays finite and the latitude is clamped")
    void nearThePole() {
        PharmacyLocation box = PharmacyLocation.boxAround(89.95, 0, 50);
        assertThat(box.maxLat()).isEqualTo(90);
        assertThat(Double.isFinite(box.minLng())).isTrue();
        assertThat(Double.isFinite(box.maxLng())).isTrue();
    }
}
