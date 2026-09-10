package com.homefix.dispatch.api;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Admin request body to update the dispatch matching weights (Requirement 19.5). Each field is
 * required; range and sum validation are enforced by {@code MatchingWeights.ofExact}, which
 * produces the descriptive error identifying the failed condition.
 *
 * <p>{@link BigDecimal} is used so "sum equals 1.0 exactly" is evaluated on the decimals the Admin
 * actually submitted, free of binary floating-point artefacts.
 */
public record MatchingWeightsRequest(
        @NotNull BigDecimal distanceWeight,
        @NotNull BigDecimal availabilityWeight,
        @NotNull BigDecimal ratingWeight,
        @NotNull BigDecimal skillWeight,
        @NotNull BigDecimal performanceWeight) {
}
