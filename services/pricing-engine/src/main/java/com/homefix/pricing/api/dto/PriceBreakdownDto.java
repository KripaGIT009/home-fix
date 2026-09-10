package com.homefix.pricing.api.dto;

import java.math.BigDecimal;

import com.homefix.pricing.domain.PriceBreakdown;

/**
 * Fully itemised price breakdown returned to callers (Requirement 6.9, Property 6). Every
 * component is non-null and the components sum to {@link #total()}.
 */
public record PriceBreakdownDto(
        BigDecimal basePrice,
        BigDecimal distanceCharge,
        BigDecimal timeCharge,
        BigDecimal partsMaterialsCharge,
        BigDecimal emergencyCharge,
        BigDecimal weekendSurcharge,
        BigDecimal nightSurcharge,
        BigDecimal demandSurgeCharge,
        BigDecimal platformFee,
        BigDecimal taxes,
        BigDecimal discountAmount,
        BigDecimal couponAmount,
        BigDecimal total) {

    public static PriceBreakdownDto from(PriceBreakdown b) {
        return new PriceBreakdownDto(
                b.basePrice(), b.distanceCharge(), b.timeCharge(), b.partsMaterialsCharge(),
                b.emergencyCharge(), b.weekendSurcharge(), b.nightSurcharge(), b.demandSurgeCharge(),
                b.platformFee(), b.taxes(), b.discountAmount(), b.couponAmount(), b.total());
    }
}
