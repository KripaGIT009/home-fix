package com.homefix.rating.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Property-based tests for the Rating &amp; Review Service correctness properties 15–17 (design.md
 * "Correctness Properties", Requirement 15). Each property runs a minimum of 100 tries and is
 * tagged with the required {@code Feature: homefix-platform, Property N} label.
 *
 * <p>These complement the example-based domain tests by asserting the review-window,
 * weighted-aggregate, and flagged-exclusion rules hold universally across generated inputs.
 */
class RatingReviewPropertiesTest {

    /** Review window from PAYMENT_COMPLETED (Requirement 15.1, 15.2; matches RatingProperties). */
    private static final Duration REVIEW_WINDOW = Duration.ofDays(7);
    /** Recent-review window and multipliers (Requirement 15.4; matches RatingProperties). */
    private static final Duration RECENT_WINDOW = Duration.ofDays(90);
    private static final BigDecimal RECENT_WEIGHT = new BigDecimal("1.5");
    private static final BigDecimal OLDER_WEIGHT = new BigDecimal("1.0");
    private static final int SCALE = 2;

    private static final Instant PAYMENT_COMPLETED = Instant.parse("2024-06-01T00:00:00Z");

    private final AggregateCalculator calculator =
            new AggregateCalculator(RECENT_WINDOW, RECENT_WEIGHT, OLDER_WEIGHT);

    // ============================================================================================
    // Property 15: Review window enforcement
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 15: Review window enforcement")
    void reviewAcceptedIffBeforeExpiryOtherwiseRejected(
            @ForAll("offsetSecondsAroundWindow") long offsetSeconds) {

        Instant expiresAt = PAYMENT_COMPLETED.plus(REVIEW_WINDOW);
        ReviewPrompt prompt = ReviewPrompt.open(UUID.randomUUID(), UUID.randomUUID(),
                ReviewerRole.CUSTOMER, UUID.randomUUID(), UUID.randomUUID(),
                PAYMENT_COMPLETED, expiresAt);

        Instant submissionTime = expiresAt.plusSeconds(offsetSeconds);

        boolean accepted = prompt.isOpenAt(submissionTime);
        boolean expectedAccepted = submissionTime.isBefore(expiresAt);

        assertThat(accepted)
                .as("submission at %s vs expiry %s", submissionTime, expiresAt)
                .isEqualTo(expectedAccepted);

        // Restate the acceptance criterion directly: accepted iff now < PAYMENT_COMPLETED + 7 days.
        assertThat(accepted)
                .isEqualTo(submissionTime.isBefore(PAYMENT_COMPLETED.plus(REVIEW_WINDOW)));
    }

    // ============================================================================================
    // Property 16: Weighted aggregate rating correctness
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 16: Weighted aggregate rating correctness")
    void aggregateEqualsRecencyWeightedAverageRoundedToTwoDecimals(
            @ForAll("reviewSpecLists") List<ReviewSpec> specs) {

        UUID provider = UUID.randomUUID();
        List<Review> reviews = new ArrayList<>();
        for (ReviewSpec spec : specs) {
            reviews.add(spec.toReview(provider));
        }

        // Expected: independent weighted average using 1.5x for recent (<=90d) and 1.0x for older,
        // over only the reviews that contribute (not flagged) — flagged exclusion is Property 17,
        // asserted separately; here we hold it constant so the recency weighting is isolated.
        Instant recentCutoff = PAYMENT_COMPLETED.minus(RECENT_WINDOW);
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightTotal = BigDecimal.ZERO;
        int contributing = 0;
        for (ReviewSpec spec : specs) {
            if (spec.flagged) {
                continue;
            }
            contributing++;
            boolean recent = !spec.submittedAt().isBefore(recentCutoff);
            BigDecimal weight = recent ? RECENT_WEIGHT : OLDER_WEIGHT;
            weightedSum = weightedSum.add(weight.multiply(BigDecimal.valueOf(spec.overall)));
            weightTotal = weightTotal.add(weight);
        }

        BigDecimal actual = calculator.aggregate(reviews, PAYMENT_COMPLETED);

        if (contributing == 0) {
            // No contributing review => no aggregate (documented null contract).
            assertThat(actual).isNull();
            return;
        }
        BigDecimal expected = weightedSum.divide(weightTotal, SCALE, RoundingMode.HALF_UP);
        assertThat(actual).isNotNull();
        assertThat(actual).isEqualByComparingTo(expected);
        // Rounded to exactly 2 decimal places.
        assertThat(actual.scale()).isEqualTo(SCALE);
    }

    // ============================================================================================
    // Property 17: Flagged reviews excluded from aggregate
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 17: Flagged reviews excluded from aggregate")
    void flaggedReviewsAreExcludedFromAggregate(
            @ForAll("nonEmptyReviewSpecLists") List<ReviewSpec> countingSpecs,
            @ForAll("possiblyEmptyReviewSpecLists") List<ReviewSpec> flaggedSpecs) {

        UUID provider = UUID.randomUUID();

        // The contributing set: forced not-flagged (and active).
        List<Review> contributing = new ArrayList<>();
        for (ReviewSpec spec : countingSpecs) {
            contributing.add(spec.withFlagged(false).toReview(provider));
        }

        // The full set: the same contributing reviews plus additional flagged (not-approved) ones.
        List<Review> full = new ArrayList<>(contributing);
        for (ReviewSpec spec : flaggedSpecs) {
            full.add(spec.withFlagged(true).toReview(provider));
        }

        BigDecimal withoutFlagged = calculator.aggregate(contributing, PAYMENT_COMPLETED);
        BigDecimal withFlagged = calculator.aggregate(full, PAYMENT_COMPLETED);

        // Adding flagged-but-not-approved reviews must not change the aggregate at all.
        assertThat(withFlagged).isEqualByComparingTo(withoutFlagged);
    }

    // ============================================================================================
    // Generators + helper spec
    // ============================================================================================

    /**
     * A recipe for a review relative to the fixed {@code PAYMENT_COMPLETED} instant: a 1–5 overall
     * rating, an age in days (spanning both sides of the 90-day recency cutoff), and a flag state.
     */
    record ReviewSpec(int overall, long ageDays, boolean flagged) {

        Instant submittedAt() {
            return PAYMENT_COMPLETED.minus(Duration.ofDays(ageDays));
        }

        ReviewSpec withFlagged(boolean value) {
            return new ReviewSpec(overall, ageDays, value);
        }

        Review toReview(UUID provider) {
            return Review.customerReview(UUID.randomUUID(), UUID.randomUUID(), provider,
                    overall, overall, overall, overall, overall, null, null, flagged,
                    submittedAt());
        }
    }

    private Arbitrary<ReviewSpec> reviewSpecs() {
        Arbitrary<Integer> overall = Arbitraries.integers().between(1, 5);
        // 0..200 days spans well past the 90-day recency cutoff on both sides.
        Arbitrary<Long> ageDays = Arbitraries.longs().between(0, 200);
        Arbitrary<Boolean> flagged = Arbitraries.of(true, false);
        return Combinators.combine(overall, ageDays, flagged).as(ReviewSpec::new);
    }

    @Provide
    Arbitrary<List<ReviewSpec>> reviewSpecLists() {
        return reviewSpecs().list().ofMinSize(1).ofMaxSize(40);
    }

    @Provide
    Arbitrary<List<ReviewSpec>> nonEmptyReviewSpecLists() {
        return reviewSpecs().list().ofMinSize(1).ofMaxSize(20);
    }

    @Provide
    Arbitrary<List<ReviewSpec>> possiblyEmptyReviewSpecLists() {
        return reviewSpecs().list().ofMinSize(0).ofMaxSize(20);
    }

    /**
     * Offsets in seconds relative to the expiry instant, biased to straddle the boundary: negative
     * = before expiry (accept), zero and positive = at/after expiry (reject). A broad +/-7-day
     * range is mixed with values tightly around the boundary (-2..+2 s) to probe the edge.
     */
    @Provide
    Arbitrary<Long> offsetSecondsAroundWindow() {
        Arbitrary<Long> broad = Arbitraries.longs().between(-604800, 604800);
        Arbitrary<Long> nearBoundary = Arbitraries.longs().between(-2, 2);
        return Arbitraries.oneOf(broad, nearBoundary);
    }
}
