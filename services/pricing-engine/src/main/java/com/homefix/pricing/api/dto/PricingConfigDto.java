package com.homefix.pricing.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.homefix.pricing.domain.PricingParameters;

/**
 * Admin Portal view of one subcategory's pricing parameters (Requirement 6.11), in the exact shape
 * the portal's {@code PricingConfig} type reads ({@code frontend/admin-portal/src/features/pricing/api.ts}).
 *
 * <p>It differs from {@link PricingParametersDto} in three ways, all driven by the portal:
 * <ul>
 *   <li>The platform fee is a <em>percent</em> ({@code 15} for 15%) because that is what an Admin
 *       types into the dialog; the domain stores a fraction ({@code 0.15}). The conversion lives
 *       here, in both directions, so the domain never sees a percent.</li>
 *   <li>The multipliers are called {@code ...Cap} on the portal: they are the per-subcategory
 *       ceiling the Admin sets, still clamped by the engine-wide maxima at quote time.</li>
 *   <li>{@code taxRate} and the provider-override floor/ceiling are not part of it, which is why
 *       the update is a merge ({@link #toChanges}) rather than the replace of the parameters PUT.</li>
 * </ul>
 *
 * <p>{@code subcategoryName} and {@code categoryName} belong to the Service Catalog; the Pricing
 * Engine has no catalog client, so they are always {@code null} here and ignored on input.
 * {@code currency} is fixed to {@code INR}, the platform's only settlement currency.
 */
public record PricingConfigDto(
        UUID subcategoryId,
        String subcategoryName,
        String categoryName,
        BigDecimal basePrice,
        BigDecimal perKmRate,
        BigDecimal maxTravelCharge,
        BigDecimal platformFeePercent,
        BigDecimal nightSurcharge,
        BigDecimal weekendSurcharge,
        BigDecimal emergencyMultiplierCap,
        BigDecimal surgeMultiplierCap,
        String currency) {

    /** The platform's settlement currency; every pricing parameter is denominated in it. */
    public static final String CURRENCY = "INR";

    public static PricingConfigDto from(PricingParameters p) {
        return new PricingConfigDto(p.subcategoryId(), null, null, p.basePrice(), p.perKmRate(),
                p.maxTravelCharge(), fractionToPercent(p.platformFeeRate()), p.nightSurcharge(),
                p.weekendSurcharge(), p.emergencyMultiplier(), p.surgeMultiplier(), CURRENCY);
    }

    /**
     * The portal's edit as a partial parameter set for {@code subcategoryId}: every field the
     * portal does not manage ({@code taxRate}, {@code overrideFloor}, {@code overrideCeiling}) and
     * every field it left out is {@code null}, which the merge reads as "keep the stored value".
     */
    public PricingParameters toChanges(UUID subcategoryId) {
        return new PricingParameters(subcategoryId, basePrice, perKmRate, maxTravelCharge,
                nightSurcharge, weekendSurcharge, percentToFraction(platformFeePercent), null,
                emergencyMultiplierCap, surgeMultiplierCap, null, null);
    }

    /** {@code 0.15} -> {@code 15}; trailing zeros dropped so the portal shows "15%", not "15.000000%". */
    static BigDecimal fractionToPercent(BigDecimal fraction) {
        if (fraction == null) {
            return null;
        }
        BigDecimal percent = fraction.movePointRight(2).stripTrailingZeros();
        // stripTrailingZeros turns 100 into 1E+2; keep a plain, non-negative scale.
        return percent.scale() < 0 ? percent.setScale(0) : percent;
    }

    /** {@code 15} -> {@code 0.15}. */
    static BigDecimal percentToFraction(BigDecimal percent) {
        return percent == null ? null : percent.movePointLeft(2);
    }
}
