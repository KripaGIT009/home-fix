package com.homefix.pricing.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Admin-configurable, per-Service_Subcategory economic parameters (Requirement 6.11).
 *
 * <p>These are the values an Admin tunes via the Admin portal; they are cached in Redis with a
 * 60-second TTL so updates reach new bookings within the bound of Requirement 6.11. They also
 * define the provider-override floor/ceiling for the subcategory (Requirement 6.12).
 *
 * <p>All monetary fields are {@link BigDecimal}. Percentage fields (platform-fee, tax) are
 * expressed as fractions (e.g. {@code 0.15} for 15%).
 */
public record PricingParameters(
        UUID subcategoryId,
        BigDecimal basePrice,
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

    public PricingParameters {
        if (subcategoryId == null) {
            throw new IllegalArgumentException("subcategoryId is required");
        }
    }
}
