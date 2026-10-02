package com.homefix.pricing.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import com.homefix.pricing.config.PricingProperties;
import com.homefix.pricing.coupon.CouponDiscountPort;
import com.homefix.pricing.domain.PriceBreakdown;
import com.homefix.pricing.domain.PricingParameters;

/**
 * Orchestrates a full price quote: resolves Admin-configured parameters (read-through cache),
 * runs the itemised formula, then applies any coupon (Requirement 6.1–6.10).
 *
 * <p>The coupon's discount is quoted by the Promotion Service through {@link CouponDiscountPort};
 * this service only decides what order value it applies to (the pre-coupon total) and folds the
 * result into the breakdown. Quoting records no redemption.
 *
 * <p>The returned {@link PriceBreakdown} has all components non-null, its components sum to the
 * total (Property 6), and the total is never below the configured floor (Property 1) even after
 * a coupon is applied.
 */
@Service
public class PriceQuoteService {

    private final PricingService pricingService;
    private final PricingConfigService configService;
    private final CouponDiscountPort couponDiscounts;
    private final PricingProperties properties;

    public PriceQuoteService(PricingService pricingService,
                             PricingConfigService configService,
                             CouponDiscountPort couponDiscounts,
                             PricingProperties properties) {
        this.pricingService = pricingService;
        this.configService = configService;
        this.couponDiscounts = couponDiscounts;
        this.properties = properties;
    }

    public PriceBreakdown quote(PriceRequest request) {
        PricingParameters params = configService.requireParameters(request.subcategoryId());
        PriceBreakdown pre = pricingService.calculate(request, params);

        if (request.couponCode() == null || request.couponCode().isBlank()) {
            return pre;
        }

        // Minimum-order check is against the pre-coupon total. The port never returns more
        // than that total; clamp anyway so a misbehaving dependency cannot produce a negative
        // pre-floor total that the floor logic would then hide in the discount line.
        BigDecimal couponAmount = couponDiscounts
                .discountFor(request.couponCode().strip(), request.userId(), pre.total())
                .min(pre.total());

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
