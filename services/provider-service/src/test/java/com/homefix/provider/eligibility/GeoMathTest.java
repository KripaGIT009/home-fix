package com.homefix.provider.eligibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Haversine distance and the SQL pre-filter's bounding box. The box must never be narrower than
 * the circle: a provider dropped by the pre-filter is never seen by the exact check.
 */
class GeoMathTest {

    @Test
    void oneDegreeOfLatitude_isAboutOneHundredAndElevenKilometres() {
        double km = GeoMath.haversineKm(25.0, 84.0, 26.0, 84.0);
        assertThat(km).isCloseTo(Math.PI * GeoMath.EARTH_RADIUS_KM / 180.0, within(1e-6));
    }

    @Test
    void samePoint_isZeroAndDistanceIsSymmetric() {
        assertThat(GeoMath.haversineKm(25.556, 84.6603, 25.556, 84.6603)).isEqualTo(0.0);
        assertThat(GeoMath.haversineKm(25.556, 84.6603, 25.5941, 85.1376))
                .isCloseTo(GeoMath.haversineKm(25.5941, 85.1376, 25.556, 84.6603), within(1e-9));
    }

    @Test
    void araToPatna_isRoughlyFortyEightKilometres() {
        assertThat(GeoMath.haversineKm(25.5560, 84.6603, 25.5941, 85.1376)).isCloseTo(48.1, within(0.5));
    }

    @Test
    void boundingBox_containsEveryPointOnTheCircle() {
        for (double lat : new double[] {0.0, 25.556, -33.9, 60.0, 75.0}) {
            for (double radiusKm : new double[] {1, 10, 100}) {
                GeoMath.BoundingBox box = GeoMath.boundingBox(lat, 84.6603, radiusKm);
                for (int bearing = 0; bearing < 360; bearing += 5) {
                    double[] point = destination(lat, 84.6603, bearing, radiusKm * (1 - 1e-9));
                    assertThat(box.contains(point[0], point[1]))
                            .as("lat=%s r=%s bearing=%s point=%s,%s box=%s",
                                    lat, radiusKm, bearing, point[0], point[1], box)
                            .isTrue();
                }
            }
        }
    }

    @Test
    void boundingBox_isTightEnoughToBeUseful() {
        GeoMath.BoundingBox box = GeoMath.boundingBox(25.556, 84.6603, 10);
        assertThat(box.maxLat() - box.minLat()).isCloseTo(20 * ProfileFixture.DEG_LAT_PER_KM, within(1e-9));
        assertThat(box.maxLon() - box.minLon()).isLessThan(0.25);
        assertThat(box.contains(25.556 + 0.2, 84.6603)).isFalse();
    }

    @Test
    void boxCrossingTheAntimeridian_dropsTheLongitudeBound() {
        GeoMath.BoundingBox box = GeoMath.boundingBox(10.0, 179.95, 50);
        assertThat(box.minLon()).isEqualTo(-180.0);
        assertThat(box.maxLon()).isEqualTo(180.0);
    }

    @Test
    void boxReachingAPole_dropsTheLongitudeBound() {
        GeoMath.BoundingBox box = GeoMath.boundingBox(89.9, 10.0, 50);
        assertThat(box.maxLat()).isEqualTo(90.0);
        assertThat(box.minLon()).isEqualTo(-180.0);
        assertThat(box.maxLon()).isEqualTo(180.0);
    }

    /** Point {@code km} along {@code bearingDeg} from the start, on the same sphere. */
    private static double[] destination(double lat, double lon, double bearingDeg, double km) {
        double delta = km / GeoMath.EARTH_RADIUS_KM;
        double theta = Math.toRadians(bearingDeg);
        double phi1 = Math.toRadians(lat);
        double lambda1 = Math.toRadians(lon);
        double phi2 = Math.asin(Math.sin(phi1) * Math.cos(delta)
                + Math.cos(phi1) * Math.sin(delta) * Math.cos(theta));
        double lambda2 = lambda1 + Math.atan2(Math.sin(theta) * Math.sin(delta) * Math.cos(phi1),
                Math.cos(delta) - Math.sin(phi1) * Math.sin(phi2));
        return new double[] {Math.toDegrees(phi2), Math.toDegrees(lambda2)};
    }
}
