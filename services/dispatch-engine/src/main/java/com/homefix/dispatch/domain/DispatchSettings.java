package com.homefix.dispatch.domain;

import java.util.Objects;

/**
 * A snapshot of every Admin-tunable dispatch setting (Requirements 8.2, 8.4, 8.5, 8.8, 19.5): the
 * matching weights plus the search-radius and offer-timeout parameters.
 *
 * <p>It exists so the Admin Portal's single "dispatch rules" form can be read and written as one
 * value. The live values themselves stay where the dispatch loop reads them —
 * {@link MatchingWeightsStore} and {@code DispatchProperties} — so this record is never consulted
 * while dispatching.
 */
public record DispatchSettings(
        MatchingWeights weights,
        double initialRadiusKm,
        double radiusIncrementKm,
        int maxExpansionCycles,
        long offerTimeoutSeconds) {

    public DispatchSettings {
        Objects.requireNonNull(weights, "weights must not be null");
    }
}
