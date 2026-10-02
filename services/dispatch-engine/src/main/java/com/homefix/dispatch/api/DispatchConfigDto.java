package com.homefix.dispatch.api;

import com.homefix.dispatch.domain.DispatchSettings;
import com.homefix.dispatch.domain.MatchingWeights;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Admin Portal view of the dispatch rules (Requirements 8.2, 8.4, 8.5, 8.8, 19.5), in the exact
 * shape of the portal's {@code DispatchConfig} type
 * ({@code frontend/admin-portal/src/features/dispatch/api.ts}); used for both the GET response and
 * the PUT body.
 *
 * <p>The weights are {@link BigDecimal} for the same reason as in {@link MatchingWeightsRequest}:
 * "sum equals 1.0 exactly" must be judged on the decimals the Admin submitted. Range checks are
 * left to {@code MatchingWeights.ofExact} and {@code DispatchSettingsService} so the error names
 * the failing condition; the bean constraints only reject missing fields.
 */
public record DispatchConfigDto(
        @NotNull @Valid Weights weights,
        @NotNull Double initialRadiusKm,
        @NotNull Double radiusIncrementKm,
        @NotNull Integer maxExpansionCycles,
        @NotNull Long offerTimeoutSeconds) {

    /** The five matching weights, keyed like the Requirement 8.3 score components. */
    public record Weights(
            @NotNull BigDecimal distance,
            @NotNull BigDecimal availability,
            @NotNull BigDecimal rating,
            @NotNull BigDecimal skill,
            @NotNull BigDecimal performance) {
    }

    public static DispatchConfigDto from(DispatchSettings s) {
        MatchingWeights w = s.weights();
        return new DispatchConfigDto(
                new Weights(
                        BigDecimal.valueOf(w.distanceWeight()),
                        BigDecimal.valueOf(w.availabilityWeight()),
                        BigDecimal.valueOf(w.ratingWeight()),
                        BigDecimal.valueOf(w.skillWeight()),
                        BigDecimal.valueOf(w.performanceWeight())),
                s.initialRadiusKm(),
                s.radiusIncrementKm(),
                s.maxExpansionCycles(),
                s.offerTimeoutSeconds());
    }

    /**
     * The requested settings.
     *
     * @throws com.homefix.dispatch.domain.WeightValidationException if a weight is outside
     *         [0.0, 1.0] or the five do not sum to exactly 1.0
     */
    public DispatchSettings toSettings() {
        MatchingWeights validated = MatchingWeights.ofExact(
                weights.distance(),
                weights.availability(),
                weights.rating(),
                weights.skill(),
                weights.performance());
        return new DispatchSettings(validated, initialRadiusKm, radiusIncrementKm,
                maxExpansionCycles, offerTimeoutSeconds);
    }
}
