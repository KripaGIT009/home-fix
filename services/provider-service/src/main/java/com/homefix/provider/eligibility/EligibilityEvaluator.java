package com.homefix.provider.eligibility;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import com.homefix.provider.domain.AvailabilitySlot;
import com.homefix.provider.domain.ProviderProfile;

/**
 * The per-provider half of dispatch eligibility (Requirement 8.2) and the five scoring components
 * (Requirement 8.3), as a pure function of a profile, a query and the current time.
 *
 * <p>Verification status is deliberately <em>not</em> checked here: it lives in the Verification
 * Service and is asked for once per search, in bulk, by {@link ProviderEligibilityService}.
 *
 * <h2>Eligibility</h2>
 * A provider is eligible when every one of these holds:
 * <ul>
 *   <li>not flagged {@code underReview} (Requirement 4.7);</li>
 *   <li>a base service location is on file — without one there is nothing to measure from;</li>
 *   <li>within {@code min(query radius, provider's service radius)} of the customer, by haversine
 *       distance: dispatch's radius expansion (Requirement 8.8) can widen the search, but never
 *       past the distance a provider has said they will travel (Requirement 4.2);</li>
 *   <li>emergency-available, when the booking is an emergency (Requirement 8.2);</li>
 *   <li>at least one of the required skill tags (Requirement 8.2), compared case-insensitively;</li>
 *   <li>available now: inside one of their weekly availability slots, read in the configured
 *       schedule zone. A provider who has set <em>no</em> slots is treated as always available —
 *       the schedule is optional at onboarding (Requirement 4.5), and reading "no schedule" as
 *       "never available" would silently exclude every provider who skipped that screen.</li>
 * </ul>
 *
 * <h2>Scores, each in [0, 1]</h2>
 * <ul>
 *   <li><b>distance</b> — {@code 1 - distance / effectiveRadius}: 1 at the customer's door,
 *       0 at the edge of the effective radius.</li>
 *   <li><b>availability</b> — how much of the next {@link #AVAILABILITY_HORIZON} the provider's
 *       current availability run still covers (adjacent slots, including across midnight, count
 *       as one run). A provider whose slot ends in ten minutes is eligible but a worse pick than
 *       one free all afternoon. No schedule scores 1.</li>
 *   <li><b>rating</b> — {@code aggregateRating / 5}, clamped. A provider with no reviews yet
 *       carries the default aggregate of 0 and so scores 0 here.</li>
 *   <li><b>skill</b> — the fraction of the required tags the provider carries.</li>
 *   <li><b>performance</b> — {@link #NEUTRAL_PERFORMANCE_SCORE} for everyone. The Provider
 *       Service records no acceptance or completion history to derive it from, and a neutral
 *       constant neither rewards nor penalises anybody until it does.</li>
 * </ul>
 */
public final class EligibilityEvaluator {

    /**
     * Look-ahead window for the availability score. Two hours covers a typical job: catalog
     * durations run from 40 to 180 minutes, most under 90.
     */
    static final Duration AVAILABILITY_HORIZON = Duration.ofHours(2);

    /** Performance component used until job acceptance/completion history exists to derive it. */
    static final double NEUTRAL_PERFORMANCE_SCORE = 0.5;

    private static final BigDecimal MAX_RATING = new BigDecimal("5");

    private EligibilityEvaluator() {
    }

    /**
     * Evaluates one provider against the query at {@code now} (already in the schedule zone).
     *
     * @return the provider's scores when eligible, empty otherwise
     */
    public static Optional<ProviderMatch> evaluate(ProviderProfile profile, EligibilityQuery query,
                                                   ZonedDateTime now) {
        if (profile.isUnderReview() || !profile.hasBaseLocation()) {
            return Optional.empty();
        }
        if (query.emergency() && !profile.isEmergencyAvailable()) {
            return Optional.empty();
        }

        double effectiveRadiusKm = Math.min(query.radiusKm(), profile.getServiceRadiusKm());
        if (effectiveRadiusKm <= 0) {
            return Optional.empty();
        }
        double distanceKm = GeoMath.haversineKm(query.latitude(), query.longitude(),
                profile.getBaseLatitude(), profile.getBaseLongitude());
        if (distanceKm > effectiveRadiusKm) {
            return Optional.empty();
        }

        double skillScore = skillScore(profile.getSkillTags(), query.skillTags());
        if (skillScore <= 0) {
            return Optional.empty();
        }

        OptionalDouble availability = availabilityScore(profile.getAvailabilitySlots(), now);
        if (availability.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new ProviderMatch(
                profile.getId(),
                distanceKm,
                clamp(1.0 - distanceKm / effectiveRadiusKm),
                availability.getAsDouble(),
                ratingScore(profile.getAggregateRating()),
                skillScore,
                NEUTRAL_PERFORMANCE_SCORE));
    }

    /** Fraction of the (already normalised, distinct) required tags the provider carries. */
    static double skillScore(List<String> providerTags, List<String> requiredTags) {
        if (requiredTags.isEmpty()) {
            return 0.0;
        }
        Set<String> carried = new HashSet<>();
        for (String tag : providerTags) {
            if (tag != null && !tag.isBlank()) {
                carried.add(EligibilityQuery.normaliseTag(tag));
            }
        }
        long matched = requiredTags.stream().filter(carried::contains).count();
        return (double) matched / requiredTags.size();
    }

    static double ratingScore(BigDecimal aggregateRating) {
        if (aggregateRating == null) {
            return 0.0;
        }
        return clamp(aggregateRating.doubleValue() / MAX_RATING.doubleValue());
    }

    /**
     * Availability at {@code now}: empty when the provider is outside every slot, otherwise the
     * share of {@link #AVAILABILITY_HORIZON} left in the current run of back-to-back slots.
     *
     * <p>Public because the Tenant team list reads "available now" from the same rule
     * (Requirements MT-3.4, MT-8.4): a Tenant_Admin must see exactly the availability dispatch uses.
     */
    public static OptionalDouble availabilityScore(List<AvailabilitySlot> slots, ZonedDateTime now) {
        if (slots.isEmpty()) {
            return OptionalDouble.of(1.0);
        }
        DayOfWeek day = now.getDayOfWeek();
        int hour = now.getHour();
        AvailabilitySlot current = slots.stream()
                .filter(s -> s.getDayOfWeek() == day && s.getStartHour() <= hour && hour < s.getEndHour())
                .findFirst()
                .orElse(null);
        if (current == null) {
            return OptionalDouble.empty();
        }

        double horizonSeconds = AVAILABILITY_HORIZON.toSeconds();
        double remainingSeconds = current.getEndHour() * 3600.0 - now.toLocalTime().toSecondOfDay();
        DayOfWeek runDay = day;
        int runEnd = current.getEndHour();
        // Follow back-to-back slots (overlap is rejected at write time, adjacency is not), across
        // midnight too. Every slot is at least an hour, so this stops within a few steps.
        while (remainingSeconds < horizonSeconds) {
            DayOfWeek nextDay = runEnd == 24 ? runDay.plus(1) : runDay;
            int nextStart = runEnd == 24 ? 0 : runEnd;
            AvailabilitySlot next = slots.stream()
                    .filter(s -> s.getDayOfWeek() == nextDay && s.getStartHour() == nextStart)
                    .findFirst()
                    .orElse(null);
            if (next == null) {
                break;
            }
            remainingSeconds += (next.getEndHour() - next.getStartHour()) * 3600.0;
            runDay = nextDay;
            runEnd = next.getEndHour();
        }
        return OptionalDouble.of(clamp(remainingSeconds / horizonSeconds));
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || value < 0.0) {
            return 0.0;
        }
        return Math.min(1.0, value);
    }
}
