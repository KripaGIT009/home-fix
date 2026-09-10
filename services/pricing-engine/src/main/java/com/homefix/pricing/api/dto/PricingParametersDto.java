package com.homefix.pricing.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.homefix.pricing.domain.PricingParameters;

import jakarta.validation.constraints.NotNull;

/**
 * Admin create/update payload for a subcategory's pricing parameters (Requirement 6.11).
 */
public record PricingParametersDto(
        @NotNull UUID subcategoryId,
        @NotNull BigDecimal basePrice,
        BigDecimal perKmRate,
        BigDecimal maxTravelCharge,
        BigDecimal nightSurcharge,
        BigDecimal weekendSurcharge,
        BigDecimal platformFeeRate,
        BigDecimal taxRate,
        BigDecimal emergencyMultiplier,
        BigDecimal surgeMultiplier,
        BigDecimal overrideFloor,
        BigDecimal overrideCeiling) {

    public PricingParameters toDomain() {
        return new PricingParameters(subcategoryId, basePrice, perKmRate, maxTravelCharge,
                nightSurcharge, weekendSurcharge, platformFeeRate, taxRate, emergencyMultiplier,
                surgeMultiplier, overrideFloor, overrideCeiling);
    }

    public static PricingParametersDto from(PricingParameters p) {
        return new PricingParametersDto(p.subcategoryId(), p.basePrice(), p.perKmRate(),
                p.maxTravelCharge(), p.nightSurcharge(), p.weekendSurcharge(), p.platformFeeRate(),
                p.taxRate(), p.emergencyMultiplier(), p.surgeMultiplier(), p.overrideFloor(),
                p.overrideCeiling());
    }
}
