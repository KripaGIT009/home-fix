package com.homefix.rating.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.homefix.rating.config.RatingProperties;
import com.homefix.rating.service.FraudDetector.Trigger;

/**
 * Unit tests for fraud detection (Requirement 15.5, Property 17): same-IP burst, rating deviation
 * beyond 2 SD, and freshly-created reviewer accounts. Any trigger flags the review for moderation.
 */
class FraudDetectorTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");
    private static final String IP = "203.0.113.7";

    private final FraudDetector detector = new FraudDetector(new RatingProperties.Fraud());

    private FraudSignals signals(int sameIpCount, int rating, List<Integer> history, Instant created) {
        return new FraudSignals(IP, NOW, sameIpCount, rating, history, created);
    }

    @Test
    void cleanReviewIsNotFlagged() {
        // Single review from IP, rating near the mean, mature account.
        FraudSignals s = signals(1, 4, List.of(4, 4, 5, 3), NOW.minusSeconds(60 * 60 * 24 * 30));
        assertThat(detector.evaluate(s)).isEmpty();
        assertThat(detector.isFraudulent(s)).isFalse();
    }

    @Test
    void sameIpBurstFlags() {
        // 2 reviews from same IP within the window (threshold = 2).
        FraudSignals s = signals(2, 4, List.of(4, 4, 4), NOW.minusSeconds(60 * 60 * 24 * 30));
        assertThat(detector.evaluate(s)).contains(Trigger.SAME_IP_BURST);
    }

    @Test
    void ratingDeviationBeyondTwoSdFlags() {
        // Provider history tightly clustered at 5; a 1-star rating is far more than 2 SD away.
        List<Integer> history = List.of(5, 5, 5, 5, 5, 4);
        FraudSignals s = signals(1, 1, history, NOW.minusSeconds(60 * 60 * 24 * 30));
        assertThat(detector.evaluate(s)).contains(Trigger.RATING_DEVIATION);
    }

    @Test
    void ratingWithinTwoSdNotFlaggedForDeviation() {
        // Spread history; a mid rating is within 2 SD.
        List<Integer> history = List.of(1, 2, 3, 4, 5);
        FraudSignals s = signals(1, 3, history, NOW.minusSeconds(60 * 60 * 24 * 30));
        assertThat(detector.evaluate(s)).doesNotContain(Trigger.RATING_DEVIATION);
    }

    @Test
    void insufficientHistorySkipsDeviationCheck() {
        // Fewer than two prior data points: SD undefined, no deviation flag.
        FraudSignals s = signals(1, 1, List.of(5), NOW.minusSeconds(60 * 60 * 24 * 30));
        assertThat(detector.evaluate(s)).doesNotContain(Trigger.RATING_DEVIATION);
    }

    @Test
    void freshAccountFlags() {
        // Account created 5 hours before submission (< 24h).
        FraudSignals s = signals(1, 4, List.of(4, 4, 4), NOW.minusSeconds(60 * 60 * 5));
        assertThat(detector.evaluate(s)).contains(Trigger.FRESH_ACCOUNT);
    }

    @Test
    void matureAccountNotFlaggedForFreshness() {
        FraudSignals s = signals(1, 4, List.of(4, 4, 4), NOW.minusSeconds(60 * 60 * 48));
        assertThat(detector.evaluate(s)).doesNotContain(Trigger.FRESH_ACCOUNT);
    }

    @Test
    void unknownAccountAgeSkipsFreshnessCheck() {
        FraudSignals s = signals(1, 4, List.of(4, 4, 4), null);
        assertThat(detector.evaluate(s)).doesNotContain(Trigger.FRESH_ACCOUNT);
    }

    @Test
    void multipleTriggersCanFireTogether() {
        Set<Trigger> triggers = detector.evaluate(
                signals(3, 1, List.of(5, 5, 5, 5), NOW.minusSeconds(60 * 60 * 2)));
        assertThat(triggers).contains(Trigger.SAME_IP_BURST, Trigger.RATING_DEVIATION, Trigger.FRESH_ACCOUNT);
    }
}
