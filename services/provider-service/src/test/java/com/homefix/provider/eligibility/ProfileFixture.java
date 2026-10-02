package com.homefix.provider.eligibility;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.homefix.provider.domain.AvailabilitySlot;
import com.homefix.provider.domain.ProviderProfile;

/**
 * Fluent builder for {@link ProviderProfile}s in eligibility tests. Defaults describe a provider
 * who is eligible for a plumbing search at Ara: based there, 10 km radius, emergency
 * available, rated 4.0, tagged {@code plumbing}, no schedule (always available).
 */
final class ProfileFixture {

    /** Ara, Bihar — the service area the apps hard-code. */
    static final double ARA_LAT = 25.5560;
    static final double ARA_LON = 84.6603;

    /** Degrees of latitude per kilometre on the haversine sphere. */
    static final double DEG_LAT_PER_KM = 180.0 / (Math.PI * GeoMath.EARTH_RADIUS_KM);

    private final UUID id = UUID.randomUUID();
    private Double lat = ARA_LAT;
    private Double lon = ARA_LON;
    private int radiusKm = 10;
    private boolean emergency = true;
    private boolean underReview;
    private BigDecimal rating = new BigDecimal("4.0");
    private List<String> tags = List.of("plumbing");
    private final List<AvailabilitySlot> slots = new ArrayList<>();

    static ProfileFixture provider() {
        return new ProfileFixture();
    }

    /** Places the provider {@code km} kilometres due north of Ara. */
    ProfileFixture kmNorthOfAra(double km) {
        this.lat = ARA_LAT + km * DEG_LAT_PER_KM;
        this.lon = ARA_LON;
        return this;
    }

    ProfileFixture noLocation() {
        this.lat = null;
        this.lon = null;
        return this;
    }

    ProfileFixture radiusKm(int radiusKm) {
        this.radiusKm = radiusKm;
        return this;
    }

    ProfileFixture emergencyAvailable(boolean emergency) {
        this.emergency = emergency;
        return this;
    }

    ProfileFixture underReview() {
        this.underReview = true;
        return this;
    }

    ProfileFixture rating(String rating) {
        this.rating = new BigDecimal(rating);
        return this;
    }

    ProfileFixture tags(String... tags) {
        this.tags = List.of(tags);
        return this;
    }

    ProfileFixture slot(java.time.DayOfWeek day, int startHour, int endHour) {
        this.slots.add(new AvailabilitySlot(day, startHour, endHour));
        return this;
    }

    ProviderProfile build() {
        ProviderProfile p = ProviderProfile.createWithId(id);
        p.setBaseLocation(lat, lon);
        p.setServiceRadiusKm(radiusKm);
        p.setEmergencyAvailable(emergency);
        p.replaceSkillTags(tags);
        p.replaceAvailability(slots);
        if (underReview) {
            // The only way into review is a rating below the threshold (Requirement 4.7).
            p.applyAggregateRating(new BigDecimal("1.0"), new BigDecimal("3.0"));
        } else {
            p.applyAggregateRating(rating, BigDecimal.ZERO);
        }
        return p;
    }
}
