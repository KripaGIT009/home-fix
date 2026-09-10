package com.homefix.dispatch.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for the matching-score formula and weight-sum validation
 * (Requirements 8.3, 8.4, 19.5; Properties 18, 19). These are example-based; the dedicated PBT
 * task (Task 42) covers the properties across the full input space.
 */
class MatchingWeightsTest {

    @Test
    void defaultWeightsSumToOne() {
        MatchingWeights w = MatchingWeights.DEFAULT;
        assertThat(w.distanceWeight()).isEqualTo(0.30);
        assertThat(w.availabilityWeight()).isEqualTo(0.25);
        assertThat(w.ratingWeight()).isEqualTo(0.20);
        assertThat(w.skillWeight()).isEqualTo(0.15);
        assertThat(w.performanceWeight()).isEqualTo(0.10);
    }

    @Test
    void scoreIsWeightedSumOfFiveComponentsWithDefaultWeights() {
        // components all distinct so a mis-wired weight would change the result.
        ScoreComponents c = new ScoreComponents(0.9, 0.8, 0.7, 0.6, 0.5);

        double expected = 0.9 * 0.30 + 0.8 * 0.25 + 0.7 * 0.20 + 0.6 * 0.15 + 0.5 * 0.10;
        assertThat(MatchingWeights.DEFAULT.score(c)).isEqualTo(expected, within(1e-12));
    }

    @Test
    void scoreUsesCustomWeights() {
        // Put all weight on the rating component: score must equal the rating component exactly.
        MatchingWeights ratingOnly = MatchingWeights.of(0.0, 0.0, 1.0, 0.0, 0.0);
        ScoreComponents c = new ScoreComponents(0.1, 0.2, 0.73, 0.4, 0.5);

        assertThat(ratingOnly.score(c)).isEqualTo(0.73, within(1e-12));
    }

    @Test
    void scoreWithCustomWeightsEqualsExplicitWeightedSum() {
        MatchingWeights w = MatchingWeights.of(0.10, 0.10, 0.20, 0.30, 0.30);
        ScoreComponents c = new ScoreComponents(1.0, 0.5, 0.25, 0.75, 0.0);

        double expected = 1.0 * 0.10 + 0.5 * 0.10 + 0.25 * 0.20 + 0.75 * 0.30 + 0.0 * 0.30;
        assertThat(w.score(c)).isEqualTo(expected, within(1e-12));
    }

    @Test
    void acceptsCustomWeightsThatSumToExactlyOne() {
        MatchingWeights w = MatchingWeights.of(0.5, 0.2, 0.1, 0.1, 0.1);
        assertThat(w.distanceWeight()).isEqualTo(0.5);
    }

    @Test
    void rejectsWeightsThatSumAboveOne() {
        assertThatThrownBy(() -> MatchingWeights.of(0.30, 0.25, 0.20, 0.15, 0.20))
                .isInstanceOf(WeightValidationException.class)
                .hasMessageContaining("sum to exactly 1.0");
    }

    @Test
    void rejectsWeightsThatSumBelowOne() {
        assertThatThrownBy(() -> MatchingWeights.of(0.30, 0.25, 0.20, 0.15, 0.05))
                .isInstanceOf(WeightValidationException.class)
                .hasMessageContaining("sum to exactly 1.0");
    }

    @Test
    void rejectsNegativeWeight() {
        assertThatThrownBy(() -> MatchingWeights.of(-0.10, 0.35, 0.30, 0.25, 0.20))
                .isInstanceOf(WeightValidationException.class)
                .hasMessageContaining("[0.0, 1.0]");
    }

    @Test
    void rejectsWeightAboveOne() {
        assertThatThrownBy(() -> MatchingWeights.of(1.10, 0.0, 0.0, 0.0, 0.0))
                .isInstanceOf(WeightValidationException.class)
                .hasMessageContaining("[0.0, 1.0]");
    }

    @Test
    void sumValidationIsExactAndNotFooledByBinaryFloatingPoint() {
        // 0.1 + 0.2 + 0.3 + 0.2 + 0.2 == 1.0 exactly as decimals, even though 0.1+0.2 != 0.3 in binary.
        MatchingWeights w = MatchingWeights.ofExact(
                new BigDecimal("0.1"), new BigDecimal("0.2"), new BigDecimal("0.3"),
                new BigDecimal("0.2"), new BigDecimal("0.2"));
        assertThat(w.ratingWeight()).isEqualTo(0.3);
    }

    @Test
    void exactFactoryRejectsSumOfOnePointZeroOne() {
        assertThatThrownBy(() -> MatchingWeights.ofExact(
                new BigDecimal("0.30"), new BigDecimal("0.25"), new BigDecimal("0.20"),
                new BigDecimal("0.15"), new BigDecimal("0.11")))
                .isInstanceOf(WeightValidationException.class)
                .hasMessageContaining("1.01");
    }
}
