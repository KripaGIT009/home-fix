package com.homefix.provider.eligibility;

/**
 * Great-circle helpers for dispatch eligibility (Requirement 8.2).
 *
 * <p>Distances use the haversine formula on a spherical Earth of mean radius
 * {@value #EARTH_RADIUS_KM} km. Over the 1–100 km service radii involved the error against the
 * WGS84 ellipsoid is well under 0.5%, far below what matters for "is this provider close enough".
 */
public final class GeoMath {

    /** IUGG mean Earth radius, in kilometres. */
    public static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoMath() {
    }

    /** Haversine distance in kilometres between two WGS84 points given in decimal degrees. */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        // Clamp: rounding can push a for antipodal points a hair above 1, and sqrt(1 - a) to NaN.
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1 - a)));
        return EARTH_RADIUS_KM * c;
    }

    /**
     * The smallest latitude/longitude box containing every point within {@code radiusKm} of the
     * centre, used as an index-friendly SQL pre-filter ahead of the exact haversine check.
     *
     * <p>The longitude half-width is {@code asin(sin(r) / cos(lat))}, not the commonly used
     * {@code r / cos(lat)}: the latter is slightly too narrow, so a provider right at the edge of
     * the circle, east or west of the customer, would be dropped by SQL before the exact check
     * ever saw them. When the circle reaches a pole, or the box would cross the antimeridian, the
     * longitude bound is dropped altogether (the full {@code [-180, 180]} range) rather than split
     * into two ranges; the haversine check still decides, the pre-filter is only less selective.
     */
    public static BoundingBox boundingBox(double lat, double lon, double radiusKm) {
        double angular = radiusKm / EARTH_RADIUS_KM;
        double dLat = Math.toDegrees(angular);
        double minLat = Math.max(-90.0, lat - dLat);
        double maxLat = Math.min(90.0, lat + dLat);

        double cosLat = Math.cos(Math.toRadians(lat));
        double ratio = cosLat <= 0 ? Double.POSITIVE_INFINITY : Math.sin(angular) / cosLat;
        if (minLat <= -90.0 || maxLat >= 90.0 || ratio >= 1.0) {
            return new BoundingBox(minLat, maxLat, -180.0, 180.0);
        }
        double dLon = Math.toDegrees(Math.asin(ratio));
        double minLon = lon - dLon;
        double maxLon = lon + dLon;
        if (minLon < -180.0 || maxLon > 180.0) {
            return new BoundingBox(minLat, maxLat, -180.0, 180.0);
        }
        return new BoundingBox(minLat, maxLat, minLon, maxLon);
    }

    /** An inclusive latitude/longitude box in decimal degrees. */
    public record BoundingBox(double minLat, double maxLat, double minLon, double maxLon) {

        public boolean contains(double lat, double lon) {
            return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
        }
    }
}
