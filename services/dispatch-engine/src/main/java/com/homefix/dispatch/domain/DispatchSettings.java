package com.homefix.dispatch.domain;

import java.util.Objects;

/**
 * A snapshot of every Admin-tunable dispatch setting (Requirements 8.2, 8.4, 8.5, 8.8, 19.5): the
 * matching weights, the search-radius and offer-timeout parameters, how many providers each wave
 * offers the job to at once, and after how many minutes an unaccepted booking goes to the agencies.
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
        long offerTimeoutSeconds,
        int offerBatchSize,
        long agencyFallbackMinutes) {

    public DispatchSettings {
        Objects.requireNonNull(weights, "weights must not be null");
    }
}
