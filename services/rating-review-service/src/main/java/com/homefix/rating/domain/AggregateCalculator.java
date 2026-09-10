package com.homefix.rating.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/**
 * Computes a provider's weighted aggregate rating (Requirement 15.4, Property 16).
 *
 * <p>The aggregate applies a higher weight to reviews submitted within the recent window (default
 * {@code <= 90 days}) than to older reviews:
 *
 * <pre>
 * aggregate = (sum(recent overall * recentWeight) + sum(older overall * olderWeight))
 *           / (count_recent * recentWeight + count_older * olderWeight)
 * </pre>
 *
 * rounded to 2 decimal places (HALF_UP). Only reviews that count toward the aggregate — active and
 * not flagged (Property 17) — should be passed in; the caller is responsible for that filtering,
 * but as a defensive measure this class also skips any review that does not
 * {@link Review#countsTowardAggregate()}.
 *
 * <p>A provider with no contributing reviews has no aggregate; callers receive {@code null}.
 */
public final class AggregateCalculator {

    private static final int SCALE = 2;

    private final Duration recentWindow;
    private final BigDecimal recentWeight;
    private final BigDecimal olderWeight;

    public AggregateCalculator(Duration recentWindow, BigDecimal recentWeight, BigDecimal olderWeight) {
        this.recentWindow = recentWindow;
        this.recentWeight = recentWeight;
        this.olderWeight = olderWeight;
    }

    /**
     * Weighted aggregate over the supplied reviews as of {@code now}, rounded to 2 dp.
     *
     * @return the aggregate, or {@code null} when no review contributes (avoids a divide-by-zero
     *         and lets the caller treat "no rating yet" distinctly from a low rating)
     */
    public BigDecimal aggregate(Collection<Review> reviews, Instant now) {
        Instant recentCutoff = now.minus(recentWindow);
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightTotal = BigDecimal.ZERO;

        for (Review review : reviews) {
            if (!review.countsTowardAggregate()) {
                continue;
            }
            BigDecimal weight = isRecent(review, recentCutoff) ? recentWeight : olderWeight;
            weightedSum = weightedSum.add(weight.multiply(BigDecimal.valueOf(review.getOverallRating())));
            weightTotal = weightTotal.add(weight);
        }

        if (weightTotal.signum() == 0) {
            return null;
        }
        return weightedSum.divide(weightTotal, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * A review is recent when its submission is on/after the cutoff ({@code now - recentWindow}),
     * i.e. within the last {@code recentWindow} inclusive.
     */
    private boolean isRecent(Review review, Instant recentCutoff) {
        return !review.getSubmittedAt().isBefore(recentCutoff);
    }
}
