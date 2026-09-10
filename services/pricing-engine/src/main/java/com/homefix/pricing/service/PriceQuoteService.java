package com.homefix.pricing.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import com.homefix.pricing.config.PricingProperties;
import com.homefix.pricing.coupon.CouponLookupPort;
import com.homefix.pricing.domain.Coupon;
import com.homefix.pricing.domain.PriceBreakdown;
import com.homefix.pricing.domain.PricingParameters;

/**
 * Orchestrates a full price quote: resolves Admin-configured parameters (read-through cache),
 * runs the itemised formula, then validates and applies any coupon (Requirement 6.1–6.10).
 *
 * <p>The returned {@link PriceBreakdown} has all components non-null, its components sum to the
 * total (Property 6), and the total is never below the configured floor (Property 1) even after
 * a coupon is applied.
 */
@Service
public class PriceQuoteService {

    private final PricingService pricingService;
    private final PricingConfigService configService;
    private final CouponService couponService;
    private final CouponLookupPort couponLookup;
    private final PricingProperties properties;

    public PriceQuoteService(PricingService pricingService,
                             PricingConfigService configService,
                             CouponService couponService,
                             CouponLookupPort couponLookup,
                             PricingProperties properties) {
        this.pricingService = pricingService;
        this.configService = configService;
        this.couponService = couponService;
        this.couponLookup = couponLookup;
        this.properties = properties;
    }

    public PriceBreakdown quote(PriceRequest request) {
        PricingParameters params = configService.requireParameters(request.subcategoryId());
        PriceBreakdown pre = pricingService.calculate(request, params);

        if (request.couponCode() == null || request.couponCode().isBlank()) {
            return pre;
        }

        Coupon coupon = couponLookup.findByCode(request.couponCode())
                .orElseThrow(() -> PricingException.coupon("COUPON_NOT_FOUND",
                        "Coupon '" + request.couponCode() + "' does not exist"));

        // Minimum-order check is against the pre-coupon total.
        BigDecimal couponAmount = couponService.validateAndComputeDiscount(
                coupon, pre.total(), request.userId());

        return PriceBreakdown.builder()
                .basePrice(pre.basePrice())
                .distanceCharge(pre.distanceCharge())
                .timeCharge(pre.timeCharge())
                .partsMaterialsCharge(pre.partsMaterialsCharge())
                .emergencyCharge(pre.emergencyCharge())
                .weekendSurcharge(pre.weekendSurcharge())
                .nightSurcharge(pre.nightSurcharge())
                .demandSurgeCharge(pre.demandSurgeCharge())
                .platformFee(pre.platformFee())
                .taxes(pre.taxes())
                .discountAmount(pre.discountAmount())
                .couponAmount(couponAmount)
                .build(pricingService.money(), properties.getMinTotal());
    }
}
