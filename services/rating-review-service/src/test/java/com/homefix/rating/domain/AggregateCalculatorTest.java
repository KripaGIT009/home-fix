package com.homefix.rating.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the weighted aggregate rating (Requirement 15.4, Property 16).
 *
 * <p>Recent reviews (&le; 90 days) are weighted 1.5x; older reviews 1.0x; the result is rounded to
 * 2 decimal places. Flagged/inactive reviews do not contribute (Property 17).
 */
class AggregateCalculatorTest {

    private static final Instant NOW = Instant.parse("2024-06-01T00:00:00Z");
    private static final UUID PROVIDER = UUID.randomUUID();

    private final AggregateCalculator calculator = new AggregateCalculator(
            Duration.ofDays(90), new BigDecimal("1.5"), new BigDecimal("1.0"));

    private Review reviewAgedDays(int overall, long daysAgo, boolean flagged) {
        Review r = Review.customerReview(UUID.randomUUID(), UUID.randomUUID(), PROVIDER,
                overall, overall, overall, overall, overall, null, null, flagged,
                NOW.minus(Duration.ofDays(daysAgo)));
        return r;
    }

    @Test
    void noReviewsYieldsNoAggregate() {
        assertThat(calculator.aggregate(List.of(), NOW)).isNull();
    }

    @Test
    void allRecentReducesToPlainAverage() {
        // Uniform weight cancels out: (1.5*5 + 1.5*3) / (1.5+1.5) = 4.00
        List<Review> reviews = List.of(reviewAgedDays(5, 10, false), reviewAgedDays(3, 20, false));
        assertThat(calculator.aggregate(reviews, NOW)).isEqualByComparingTo("4.00");
    }

    @Test
    void mixedRecencyAppliesHigherWeightToRecent() {
        // recent(<=90d): rating 5 ; older(>90d): rating 3
        // (1.5*5 + 1.0*3) / (1.5 + 1.0) = 10.5 / 2.5 = 4.20
        List<Review> reviews = List.of(reviewAgedDays(5, 30, false), reviewAgedDays(3, 200, false));
        assertThat(calculator.aggregate(reviews, NOW)).isEqualByComparingTo("4.20");
    }

    @Test
    void boundaryAt90DaysCountsAsRecent() {
        // Exactly 90 days ago is within the recent window (inclusive).
        // (1.5*2 + 1.5*4) / (1.5+1.5) = 3.00
        List<Review> reviews = List.of(reviewAgedDays(2, 90, false), reviewAgedDays(4, 10, false));
        assertThat(calculator.aggregate(reviews, NOW)).isEqualByComparingTo("3.00");
    }

    @Test
    void justPast90DaysCountsAsOlder() {
        // one recent 5 (30d) + one older 1 (91d): (1.5*5 + 1.0*1) / (1.5+1.0) = 8.5/2.5 = 3.40
        List<Review> reviews = List.of(reviewAgedDays(5, 30, false), reviewAgedDays(1, 91, false));
        assertThat(calculator.aggregate(reviews, NOW)).isEqualByComparingTo("3.40");
    }

    @Test
    void resultIsRoundedToTwoDecimalPlaces() {
        // recent 5,4 + older 4: (1.5*9 + 1.0*4) / (3.0 + 1.0) = 17.5/4 = 4.375 -> 4.38 (HALF_UP)
        List<Review> reviews = List.of(
                reviewAgedDays(5, 10, false),
                reviewAgedDays(4, 20, false),
                reviewAgedDays(4, 120, false));
        assertThat(calculator.aggregate(reviews, NOW)).isEqualByComparingTo("4.38");
    }

    @Test
    void flaggedReviewsAreExcluded() {
        // A flagged 1-star recent review must not drag the aggregate down.
        List<Review> reviews = List.of(
                reviewAgedDays(5, 10, false),
                reviewAgedDays(1, 5, true)); // flagged -> excluded
        assertThat(calculator.aggregate(reviews, NOW)).isEqualByComparingTo("5.00");
    }
}
