package com.medicity.chemist;

/**
 * The latitude/longitude box that contains a circle, used to let the database
 * discard distant shops through an index before computing exact distances.
 */
record PharmacyLocation(double minLat, double maxLat, double minLng, double maxLng) {

    private static final double KM_PER_DEGREE_LATITUDE = 111.195;   // earth radius 6371.0088 km

    static PharmacyLocation boxAround(double lat, double lng, double radiusKm) {
        double latDelta = radiusKm / KM_PER_DEGREE_LATITUDE;
        double minLat = Math.max(-90, lat - latDelta);
        double maxLat = Math.min(90, lat + latDelta);

        // A degree of longitude shrinks toward the poles, so the box widens with
        // latitude. Use the widest edge (the one nearer the pole): a box sized for
        // the centre would be too narrow there and drop shops inside the radius.
        double widestLat = Math.min(89.9, Math.max(Math.abs(minLat), Math.abs(maxLat)));
        double lngDelta = radiusKm / (KM_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(widestLat)));

        // Across the antimeridian, or when the box would span the whole globe,
        // the box cannot be expressed as one BETWEEN. Leave longitude unbounded:
        // still correct, because the exact distance test runs afterwards.
        if (lngDelta >= 180 || lng - lngDelta < -180 || lng + lngDelta > 180) {
            return new PharmacyLocation(minLat, maxLat, -180, 180);
        }
        return new PharmacyLocation(minLat, maxLat, lng - lngDelta, lng + lngDelta);
    }
}
