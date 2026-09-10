package com.homefix.admin.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

/**
 * Admin request body to update the dispatch matching weights (Requirement 19.5). Each field is
 * required; range and exact-sum validation are performed by {@code DispatchWeights.ofExact},
 * which throws a descriptive error identifying the failed condition and leaves the stored weights
 * unchanged.
 */
public record DispatchWeightsRequest(
        @NotNull BigDecimal distanceWeight,
        @NotNull BigDecimal availabilityWeight,
        @NotNull BigDecimal ratingWeight,
        @NotNull BigDecimal skillWeight,
        @NotNull BigDecimal performanceWeight) {
}
