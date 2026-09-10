package com.homefix.admin.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for dispatch matching-weight validation (Requirement 19.5, Property 19): each weight
 * must be in [0.0, 1.0] and the five must sum to exactly 1.0; otherwise the update is rejected
 * with an error naming the failed condition, and the store is left unchanged.
 */
class DispatchWeightsTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void acceptsWeightsInRangeSummingToExactlyOne() {
        assertThatCode(() -> DispatchWeights.ofExact(
                bd("0.30"), bd("0.25"), bd("0.20"), bd("0.15"), bd("0.10")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWhenSumBelowOne() {
        assertThatThrownBy(() -> DispatchWeights.ofExact(
                bd("0.20"), bd("0.25"), bd("0.20"), bd("0.15"), bd("0.10")))
                .isInstanceOf(WeightValidationException.class)
                .hasMessageContaining("sum to exactly 1.0");
    }

    @Test
    void rejectsWhenSumAboveOne() {
        assertThatThrownBy(() -> DispatchWeights.ofExact(
                bd("0.40"), bd("0.25"), bd("0.20"), bd("0.15"), bd("0.10")))
                .isInstanceOf(WeightValidationException.class)
                .hasMessageContaining("sum to exactly 1.0");
    }

    @Test
    void rejectsWeightBelowZero() {
        assertThatThrownBy(() -> DispatchWeights.ofExact(
                bd("-0.10"), bd("0.35"), bd("0.30"), bd("0.25"), bd("0.20")))
                .isInstanceOf(WeightValidationException.class)
                .hasMessageContaining("distanceWeight")
                .hasMessageContaining("[0.0, 1.0]");
    }

    @Test
    void rejectsWeightAboveOne() {
        assertThatThrownBy(() -> DispatchWeights.ofExact(
                bd("1.10"), bd("0.00"), bd("0.00"), bd("0.00"), bd("0.00")))
                .isInstanceOf(WeightValidationException.class)
                .hasMessageContaining("[0.0, 1.0]");
    }

    @Test
    void invalidUpdateLeavesExistingWeightsUnchanged() {
        DispatchWeightsStore store = new DispatchWeightsStore();
        DispatchWeights original = store.current();

        // Simulate the controller flow: validation throws before store.update is called.
        assertThatThrownBy(() -> {
            DispatchWeights candidate = DispatchWeights.ofExact(
                    bd("0.50"), bd("0.25"), bd("0.20"), bd("0.15"), bd("0.10")); // sums to 1.20
            store.update(candidate);
        }).isInstanceOf(WeightValidationException.class);

        assertThat(store.current()).isEqualTo(original);
    }

    @Test
    void validUpdateReplacesWeights() {
        DispatchWeightsStore store = new DispatchWeightsStore();
        DispatchWeights candidate = DispatchWeights.ofExact(
                bd("0.20"), bd("0.20"), bd("0.20"), bd("0.20"), bd("0.20"));
        store.update(candidate);
        assertThat(store.current()).isEqualTo(candidate);
    }
}
