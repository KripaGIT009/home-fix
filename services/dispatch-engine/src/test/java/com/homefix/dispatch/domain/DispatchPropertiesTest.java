package com.homefix.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for the Dispatch Engine correctness properties 18–19 (design.md "Correctness
 * Properties", Requirements 8.3, 8.4, 19.5). Each property runs a minimum of 100 tries and is
 * tagged with the required {@code Feature: homefix-platform, Property N} label.
 *
 * <p>These complement the example-based {@link MatchingWeights}/{@link MatchingWeightsStore} tests
 * by asserting the scoring-formula and weight-validation rules hold universally across generated
 * weight sets and provider component scores.
 */
class DispatchPropertiesTest {

    /** Floating-point tolerance for the score comparison (unitless ratios; a few ULPs are fine). */
    private static final double EPSILON = 1e-9;

    // ============================================================================================
    // Property 18: Dispatch matching score formula
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 18: Dispatch matching score formula")
    void matchingScoreEqualsWeightedSumOfFiveComponents(
            @ForAll("validWeights") MatchingWeights weights,
            @ForAll("candidateLists") List<ProviderCandidate> candidates) {

        for (ProviderCandidate candidate : candidates) {
            ScoreComponents c = candidate.components();
            double expected = c.distanceScore() * weights.distanceWeight()
                    + c.availabilityScore() * weights.availabilityWeight()
                    + c.ratingScore() * weights.ratingWeight()
                    + c.skillScore() * weights.skillWeight()
                    + c.performanceScore() * weights.performanceWeight();

            assertThat(candidate.score(weights)).isCloseTo(expected, org.assertj.core.data.Offset.offset(EPSILON));
        }
    }

    // ============================================================================================
    // Property 19: Dispatch weight validation
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 19: Dispatch weight validation")
    void weightUpdateAcceptedIffEachInRangeAndSumIsExactlyOne(
            @ForAll("weightCandidate") BigDecimal distance,
            @ForAll("weightCandidate") BigDecimal availability,
            @ForAll("weightCandidate") BigDecimal rating,
            @ForAll("weightCandidate") BigDecimal skill,
            @ForAll("weightCandidate") BigDecimal performance) {

        MatchingWeightsStore store = new MatchingWeightsStore();
        MatchingWeights before = store.current();

        boolean eachInRange = inUnitInterval(distance) && inUnitInterval(availability)
                && inUnitInterval(rating) && inUnitInterval(skill) && inUnitInterval(performance);
        BigDecimal sum = distance.add(availability).add(rating).add(skill).add(performance);
        boolean expectedAccepted = eachInRange && sum.compareTo(BigDecimal.ONE) == 0;

        Throwable failure = catchThrowable(() -> {
            MatchingWeights candidate =
                    MatchingWeights.ofExact(distance, availability, rating, skill, performance);
            store.update(candidate);
        });

        if (expectedAccepted) {
            // Accepted: no validation error, and the active weights now reflect the submission.
            assertThat(failure).as("valid weight set %s should be accepted", sum).isNull();
            MatchingWeights after = store.current();
            assertThat(after.distanceWeight()).isEqualTo(distance.doubleValue());
            assertThat(after.availabilityWeight()).isEqualTo(availability.doubleValue());
            assertThat(after.ratingWeight()).isEqualTo(rating.doubleValue());
            assertThat(after.skillWeight()).isEqualTo(skill.doubleValue());
            assertThat(after.performanceWeight()).isEqualTo(performance.doubleValue());
        } else {
            // Rejected: a validation error, and the previously-active weights are unchanged.
            assertThat(failure)
                    .as("invalid weight set (eachInRange=%s, sum=%s) should be rejected", eachInRange, sum)
                    .isInstanceOf(WeightValidationException.class);
            assertThat(store.current()).isEqualTo(before);
        }
    }

    private static boolean inUnitInterval(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(BigDecimal.ONE) <= 0;
    }

    // ============================================================================================
    // Generators
    // ============================================================================================

    /**
     * Component score in [0.0, 1.0] with a modest number of decimals; the constructor rejects
     * anything outside the unit interval, matching the platform's normalised-score contract.
     */
    private Arbitrary<Double> unitScore() {
        return Arbitraries.doubles().between(0.0, 1.0).ofScale(4);
    }

    private Arbitrary<ProviderCandidate> candidates() {
        return Combinators.combine(unitScore(), unitScore(), unitScore(), unitScore(), unitScore())
                .as((d, a, r, s, p) -> new ProviderCandidate(
                        UUID.randomUUID(), new ScoreComponents(d, a, r, s, p)));
    }

    @Provide
    Arbitrary<List<ProviderCandidate>> candidateLists() {
        return candidates().list().ofMinSize(1).ofMaxSize(30);
    }

    /**
     * A valid weight set that sums to exactly 1.0: draw four weights that leave headroom, then set
     * the fifth to the exact remainder so the {@link BigDecimal} sum is precisely 1.0.
     */
    @Provide
    Arbitrary<MatchingWeights> validWeights() {
        return Combinators.combine(fraction(), fraction(), fraction(), fraction())
                .as((a, b, c, d) -> new BigDecimal[]{a, b, c, d})
                .filter(parts -> {
                    BigDecimal partial = parts[0].add(parts[1]).add(parts[2]).add(parts[3]);
                    return partial.compareTo(BigDecimal.ONE) <= 0;
                })
                .map(parts -> {
                    BigDecimal partial = parts[0].add(parts[1]).add(parts[2]).add(parts[3]);
                    BigDecimal fifth = BigDecimal.ONE.subtract(partial);
                    return MatchingWeights.ofExact(parts[0], parts[1], parts[2], parts[3], fifth);
                });
    }

    /** A non-negative fraction in [0.0, 0.25] with 2 decimals, keeping the running sum <= 1.0. */
    private Arbitrary<BigDecimal> fraction() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("0.25")).ofScale(2);
    }

    /**
     * A candidate weight for a single slot. Spans below zero, within range, and above one, at a
     * coarse scale so five of them realistically sum to exactly 1.0 sometimes and miss most times —
     * exercising both the accept and reject branches of Property 19.
     */
    @Provide
    Arbitrary<BigDecimal> weightCandidate() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-0.20"), new BigDecimal("1.20"))
                .ofScale(1);
    }
}
