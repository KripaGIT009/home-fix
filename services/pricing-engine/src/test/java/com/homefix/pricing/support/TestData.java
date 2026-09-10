package com.homefix.pricing.support;

import java.math.BigDecimal;
import java.util.UUID;

import com.homefix.pricing.domain.PricingParameters;

/** Shared builders for pricing test fixtures. */
public final class TestData {

    public static final UUID SUBCATEGORY = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private TestData() {
    }

    /**
     * Parameters with no surcharges, no fees/taxes, no distance so tests can isolate a single
     * behaviour. Emergency/surge multipliers default to 2.0 (the caps) so multiplier tests see
     * the maximum effect unless overridden.
     */
    public static PricingParameters baseParams(String basePrice) {
        return new PricingParameters(
                SUBCATEGORY,
                new BigDecimal(basePrice),
                BigDecimal.ZERO,          // perKmRate
                new BigDecimal("100.00"), // maxTravelCharge
                BigDecimal.ZERO,          // nightSurcharge
                BigDecimal.ZERO,          // weekendSurcharge
                BigDecimal.ZERO,          // platformFeeRate
                BigDecimal.ZERO,          // taxRate
                new BigDecimal("2.0"),    // emergencyMultiplier
                new BigDecimal("2.0"),    // surgeMultiplier
                new BigDecimal("10.00"),  // overrideFloor
                new BigDecimal("500.00")); // overrideCeiling
    }

    public static PricingParameters withMultipliers(String basePrice, String emergency, String surge) {
        PricingParameters p = baseParams(basePrice);
        return new PricingParameters(p.subcategoryId(), p.basePrice(), p.perKmRate(),
                p.maxTravelCharge(), p.nightSurcharge(), p.weekendSurcharge(), p.platformFeeRate(),
                p.taxRate(), new BigDecimal(emergency), new BigDecimal(surge),
                p.overrideFloor(), p.overrideCeiling());
    }

    public static PricingParameters withDistance(String basePrice, String perKmRate, String maxTravel) {
        PricingParameters p = baseParams(basePrice);
        return new PricingParameters(p.subcategoryId(), p.basePrice(), new BigDecimal(perKmRate),
                new BigDecimal(maxTravel), p.nightSurcharge(), p.weekendSurcharge(),
                p.platformFeeRate(), p.taxRate(), p.emergencyMultiplier(), p.surgeMultiplier(),
                p.overrideFloor(), p.overrideCeiling());
    }

    public static PricingParameters withSurcharges(String basePrice, String night, String weekend) {
        PricingParameters p = baseParams(basePrice);
        return new PricingParameters(p.subcategoryId(), p.basePrice(), p.perKmRate(),
                p.maxTravelCharge(), new BigDecimal(night), new BigDecimal(weekend),
                p.platformFeeRate(), p.taxRate(), p.emergencyMultiplier(), p.surgeMultiplier(),
                p.overrideFloor(), p.overrideCeiling());
    }
}
