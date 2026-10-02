package com.homefix.pricing.coupon;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Quotes the discount a coupon gives on an order (Requirement 6.10).
 *
 * <p>The Promotion Service owns coupons: their definitions, their validity windows and the
 * per-user and total usage counters (Requirement 21). The Pricing Engine therefore asks it for the
 * discount instead of evaluating a copy of the coupon itself, so an estimate always carries exactly
 * the discount the Promotion Service will honour, under one set of rules and one set of error codes.
 *
 * <p>A quote is read-only: it never records a redemption. Usage is counted when the coupon is
 * actually redeemed through the Promotion Service's {@code POST /coupons/redeem}, at booking time.
 */
public interface CouponDiscountPort {

    /**
     * Returns the discount {@code code} gives on {@code orderValue} for {@code userId}.
     *
     * @param code       the coupon code as the customer entered it (case-insensitive)
     * @param userId     the customer the coupon is applied for; {@code null} skips the per-user
     *                   usage limit, which then applies at redemption
     * @param orderValue the pre-coupon order total, used for the minimum-order check
     * @return the discount amount, never null or negative and never above {@code orderValue}
     * @throws com.homefix.pricing.service.PricingException 422 with {@code COUPON_NOT_FOUND} or the
     *         violated constraint's code when the coupon does not apply; 503
     *         {@code COUPON_SERVICE_UNAVAILABLE} when the Promotion Service could not be consulted
     */
    BigDecimal discountFor(String code, UUID userId, BigDecimal orderValue);
}
