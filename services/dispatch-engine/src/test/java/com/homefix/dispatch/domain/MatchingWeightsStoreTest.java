package com.homefix.dispatch.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the store applies valid weight updates and leaves the active weights unchanged when a
 * candidate is invalid (Requirement 19.5, Property 19). Because {@link MatchingWeights}
 * construction is what validates, a rejected update never reaches the store.
 */
class MatchingWeightsStoreTest {

    @Test
    void startsWithDefaultWeights() {
        assertThat(new MatchingWeightsStore().current()).isEqualTo(MatchingWeights.DEFAULT);
    }

    @Test
    void appliesValidUpdate() {
        MatchingWeightsStore store = new MatchingWeightsStore();
        MatchingWeights updated = MatchingWeights.of(0.5, 0.2, 0.1, 0.1, 0.1);

        store.update(updated);

        assertThat(store.current()).isEqualTo(updated);
    }

    @Test
    void invalidUpdateLeavesExistingWeightsUnchanged() {
        MatchingWeightsStore store = new MatchingWeightsStore();
        MatchingWeights firstValid = MatchingWeights.of(0.4, 0.3, 0.1, 0.1, 0.1);
        store.update(firstValid);

        // An invalid candidate never constructs, so the store keeps the previous valid weights.
        assertThatThrownBy(() -> store.update(MatchingWeights.ofExact(
                new BigDecimal("0.5"), new BigDecimal("0.5"), new BigDecimal("0.5"),
                new BigDecimal("0.5"), new BigDecimal("0.5"))))
                .isInstanceOf(WeightValidationException.class);

        assertThat(store.current()).isEqualTo(firstValid);
    }
}
