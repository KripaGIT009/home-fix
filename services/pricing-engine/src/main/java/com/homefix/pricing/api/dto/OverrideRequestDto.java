package com.homefix.pricing.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Provider-specific pricing override proposal (Requirement 6.12). The proposed price is
 * accepted only if within the subcategory floor/ceiling.
 */
public record OverrideRequestDto(
        @NotNull UUID subcategoryId,
        @NotNull BigDecimal proposedPrice) {
}
