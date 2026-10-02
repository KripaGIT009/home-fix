package com.homefix.provider.eligibility;

import static com.homefix.provider.eligibility.ProfileFixture.ARA_LAT;
import static com.homefix.provider.eligibility.ProfileFixture.ARA_LON;
import static com.homefix.provider.eligibility.ProfileFixture.provider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.homefix.provider.domain.ProviderProfile;

/**
 * The per-provider eligibility rules and the five score components (Requirements 8.2, 8.3), as a
 * pure function — no repository, no clock, no Verification Service.
 */
class EligibilityEvaluatorTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Friday 2 October 2026, 10:00 IST. */
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 2, 10, 0, 0, 0, IST);

    private static EligibilityQuery query(double radiusKm, boolean emergency, String... tags) {
        return new EligibilityQuery(UUID.randomUUID(), ARA_LAT, ARA_LON, radiusKm, emergency, List.of(tags));
    }

    private static Optional<ProviderMatch> evaluate(ProviderProfile p, EligibilityQuery q) {
        return EligibilityEvaluator.evaluate(p, q, NOW);
    }

    @Test
    void eligibleProvider_isScoredWithEveryComponentInTheUnitInterval() {
        ProviderProfile p = provider().kmNorthOfAra(5).radiusKm(10).rating("4.6").build();

        ProviderMatch match = evaluate(p, query(15, true, "plumbing")).orElseThrow();

        assertThat(match.providerId()).isEqualTo(p.getId());
        assertThat(match.distanceKm()).isCloseTo(5.0, within(1e-6));
        // Effective radius is the provider's 10 km, not the 15 km search: 1 - 5/10.
        assertThat(match.distanceScore()).isCloseTo(0.5, within(1e-6));
        assertThat(match.availabilityScore()).isEqualTo(1.0);
        assertThat(match.ratingScore()).isCloseTo(0.92, within(1e-9));
        assertThat(match.skillScore()).isEqualTo(1.0);
        assertThat(match.performanceScore()).isEqualTo(EligibilityEvaluator.NEUTRAL_PERFORMANCE_SCORE);
        for (double score : new double[] {match.distanceScore(), match.availabilityScore(),
                match.ratingScore(), match.skillScore(), match.performanceScore()}) {
            assertThat(score).isBetween(0.0, 1.0);
        }
    }

    @Nested
    class Distance {

        @Test
        void providerAtTheCustomersDoor_scoresOne() {
            ProviderMatch match = evaluate(provider().build(), query(10, false, "plumbing")).orElseThrow();
            assertThat(match.distanceScore()).isEqualTo(1.0);
        }

        @Test
        void beyondTheProvidersOwnRadius_isIneligibleEvenInsideTheSearchRadius() {
            ProviderProfile p = provider().kmNorthOfAra(5).radiusKm(4).build();
            assertThat(evaluate(p, query(20, false, "plumbing"))).isEmpty();
        }

        @Test
        void beyondTheSearchRadius_isIneligibleEvenInsideTheProvidersRadius() {
            ProviderProfile p = provider().kmNorthOfAra(5).radiusKm(50).build();
            assertThat(evaluate(p, query(4, false, "plumbing"))).isEmpty();
        }

        @Test
        void effectiveRadiusIsTheSmallerOfTheTwo() {
            ProviderProfile p = provider().kmNorthOfAra(3).radiusKm(50).build();
            ProviderMatch match = evaluate(p, query(6, false, "plumbing")).orElseThrow();
            assertThat(match.distanceScore()).isCloseTo(0.5, within(1e-6));
        }

        @Test
        void providerWithoutABaseLocation_isNeverEligible() {
            ProviderProfile p = provider().noLocation().build();
            assertThat(evaluate(p, query(100, false, "plumbing"))).isEmpty();
        }
    }

    @Nested
    class Flags {

        @Test
        void underReview_isIneligible() {
            assertThat(evaluate(provider().underReview().build(), query(10, false, "plumbing"))).isEmpty();
        }

        @Test
        void emergencySearch_requiresEmergencyAvailability() {
            ProviderProfile p = provider().emergencyAvailable(false).build();
            assertThat(evaluate(p, query(10, true, "plumbing"))).isEmpty();
        }

        @Test
        void scheduledSearch_admitsProvidersWhoAreNotEmergencyAvailable() {
            ProviderProfile p = provider().emergencyAvailable(false).build();
            assertThat(evaluate(p, query(10, false, "plumbing"))).isPresent();
        }
    }

    @Nested
    class Skills {

        @Test
        void noSharedTag_isIneligible() {
            ProviderProfile p = provider().tags("cleaning").build();
            assertThat(evaluate(p, query(10, false, "plumbing", "electrical"))).isEmpty();
        }

        @Test
        void skillScore_isTheFractionOfRequiredTagsCarried() {
            ProviderProfile p = provider().tags("plumbing", "carpentry").build();
            ProviderMatch match = evaluate(p, query(10, false, "plumbing", "electrical")).orElseThrow();
            assertThat(match.skillScore()).isEqualTo(0.5);
        }

        @Test
        void tagsMatchCaseInsensitivelyAndDuplicatesCountOnce() {
            ProviderProfile p = provider().tags(" Plumbing ").build();
            ProviderMatch match = evaluate(p, query(10, false, "PLUMBING", "plumbing")).orElseThrow();
            assertThat(match.skillScore()).isEqualTo(1.0);
        }

        @Test
        void noRequiredTags_matchesNobody() {
            assertThat(evaluate(provider().build(), query(10, false))).isEmpty();
        }
    }

    @Nested
    class Rating {

        @Test
        void unratedProvider_scoresZero() {
            ProviderMatch match = evaluate(provider().rating("0").build(), query(10, false, "plumbing"))
                    .orElseThrow();
            assertThat(match.ratingScore()).isEqualTo(0.0);
        }

        @Test
        void ratingIsClampedToOne() {
            assertThat(EligibilityEvaluator.ratingScore(new java.math.BigDecimal("5.4"))).isEqualTo(1.0);
            assertThat(EligibilityEvaluator.ratingScore(null)).isEqualTo(0.0);
        }
    }

    @Nested
    class Availability {

        private final DayOfWeek today = NOW.getDayOfWeek();

        @Test
        void noScheduleAtAll_isTreatedAsAlwaysAvailable() {
            ProviderMatch match = evaluate(provider().build(), query(10, false, "plumbing")).orElseThrow();
            assertThat(match.availabilityScore()).isEqualTo(1.0);
        }

        @Test
        void outsideEverySlot_isIneligible() {
            ProviderProfile p = provider().slot(today, 14, 18).slot(today.plus(1), 9, 12).build();
            assertThat(evaluate(p, query(10, false, "plumbing"))).isEmpty();
        }

        @Test
        void slotEndingExactlyNow_doesNotCover() {
            // Slots are half-open [start, end): 10:00 is outside a 6-10 slot.
            ProviderProfile p = provider().slot(today, 6, 10).build();
            assertThat(evaluate(p, query(10, false, "plumbing"))).isEmpty();
        }

        @Test
        void slotWithMoreThanTheHorizonLeft_scoresOne() {
            ProviderProfile p = provider().slot(today, 9, 18).build();
            ProviderMatch match = evaluate(p, query(10, false, "plumbing")).orElseThrow();
            assertThat(match.availabilityScore()).isEqualTo(1.0);
        }

        @Test
        void slotEndingSoon_scoresTheShareOfTheHorizonLeft() {
            ProviderProfile p = provider().slot(today, 9, 11).build();
            ZonedDateTime at1030 = NOW.withMinute(30);
            ProviderMatch match = EligibilityEvaluator.evaluate(p, query(10, false, "plumbing"), at1030)
                    .orElseThrow();
            // 30 minutes of a 2-hour horizon.
            assertThat(match.availabilityScore()).isCloseTo(0.25, within(1e-9));
        }

        @Test
        void backToBackSlots_countAsOneRun() {
            ProviderProfile p = provider().slot(today, 9, 11).slot(today, 11, 12).build();
            ZonedDateTime at1030 = NOW.withMinute(30);
            ProviderMatch match = EligibilityEvaluator.evaluate(p, query(10, false, "plumbing"), at1030)
                    .orElseThrow();
            // 30 min + 60 min of the next slot = 90 of 120.
            assertThat(match.availabilityScore()).isCloseTo(0.75, within(1e-9));
        }

        @Test
        void runContinuesAcrossMidnightIntoTheNextDay() {
            ProviderProfile p = provider().slot(today, 23, 24).slot(today.plus(1), 0, 6).build();
            ZonedDateTime at2330 = NOW.withHour(23).withMinute(30);
            ProviderMatch match = EligibilityEvaluator.evaluate(p, query(10, false, "plumbing"), at2330)
                    .orElseThrow();
            assertThat(match.availabilityScore()).isEqualTo(1.0);

            ProviderProfile lateOnly = provider().slot(today, 23, 24).build();
            ProviderMatch alone = EligibilityEvaluator.evaluate(lateOnly, query(10, false, "plumbing"), at2330)
                    .orElseThrow();
            assertThat(alone.availabilityScore()).isCloseTo(0.25, within(1e-9));
        }
    }
}
