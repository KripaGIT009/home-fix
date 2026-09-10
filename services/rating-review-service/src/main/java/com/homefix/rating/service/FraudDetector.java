package com.homefix.rating.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.homefix.rating.config.RatingProperties;

/**
 * Detects the three suspicious patterns that flag a review for Admin moderation (Requirement 15.5,
 * Property 17):
 *
 * <ol>
 *   <li><b>Same-IP burst</b> — two or more reviews from the same IP within 1 hour (15.5a).</li>
 *   <li><b>Rating deviation</b> — the star rating deviates more than 2 standard deviations from the
 *       provider's historical mean (15.5b).</li>
 *   <li><b>Fresh account</b> — the reviewer's account was created within 24 hours of submission
 *       (15.5c).</li>
 * </ol>
 *
 * <p>Pure logic: it consumes a {@link FraudSignals} snapshot and returns the set of triggers that
 * fired. Any non-empty result means the review must be held out of the aggregate until an Admin
 * approves it.
 */
public class FraudDetector {

    /** The individual suspicious patterns that can flag a review. */
    public enum Trigger {
        SAME_IP_BURST,
        RATING_DEVIATION,
        FRESH_ACCOUNT
    }

    private final RatingProperties.Fraud config;

    public FraudDetector(RatingProperties.Fraud config) {
        this.config = config;
    }

    /** @return the triggers that fired; empty means the review is not flagged. */
    public Set<Trigger> evaluate(FraudSignals signals) {
        Set<Trigger> triggers = EnumSet.noneOf(Trigger.class);

        if (signals.sourceIp() != null && signals.recentSameIpCount() >= config.getSameIpThreshold()) {
            triggers.add(Trigger.SAME_IP_BURST);
        }

        if (deviatesBeyondThreshold(signals.overallRating(), signals.providerHistory())) {
            triggers.add(Trigger.RATING_DEVIATION);
        }

        if (isFreshAccount(signals)) {
            triggers.add(Trigger.FRESH_ACCOUNT);
        }

        return triggers;
    }

    /** Convenience: whether any trigger fires. */
    public boolean isFraudulent(FraudSignals signals) {
        return !evaluate(signals).isEmpty();
    }

    /**
     * True when {@code rating} lies more than the configured number of standard deviations from the
     * historical mean. With fewer than two prior data points the standard deviation is undefined (or
     * zero), so the check is skipped — a provider's first reviews cannot deviate from a
     * not-yet-established mean.
     */
    private boolean deviatesBeyondThreshold(int rating, List<Integer> history) {
        List<Integer> values = history == null ? List.of() : history;
        if (values.size() < 2) {
            return false;
        }
        double mean = values.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double variance = values.stream()
                .mapToDouble(v -> {
                    double d = v - mean;
                    return d * d;
                })
                .sum() / values.size();
        double sd = Math.sqrt(variance);
        if (sd == 0.0) {
            // No historical spread: any exact-match rating is fine; a differing rating is suspicious.
            return rating != Math.round(mean);
        }
        double deviations = Math.abs(rating - mean) / sd;
        return deviations > config.getDeviationSdThreshold();
    }

    private boolean isFreshAccount(FraudSignals signals) {
        if (signals.reviewerCreatedAt() == null) {
            return false;
        }
        Duration age = Duration.between(signals.reviewerCreatedAt(), signals.submittedAt());
        return age.compareTo(config.getMinAccountAge()) < 0;
    }

    /** Human-readable reasons for the audit trail / logs. */
    public static List<String> describe(Set<Trigger> triggers) {
        List<String> reasons = new ArrayList<>();
        for (Trigger t : triggers) {
            switch (t) {
                case SAME_IP_BURST -> reasons.add("multiple reviews from the same IP within the burst window");
                case RATING_DEVIATION -> reasons.add("rating deviates beyond threshold from provider historical mean");
                case FRESH_ACCOUNT -> reasons.add("reviewer account created within the minimum age window");
            }
        }
        return reasons;
    }
}
