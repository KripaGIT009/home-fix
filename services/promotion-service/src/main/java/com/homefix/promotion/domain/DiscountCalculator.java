package com.homefix.promotion.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure, side-effect-free computation of the discount amount a coupon yields for a given order value
 * (Requirement 21.2). Factored out of the service so it is trivially unit-testable and can be
 * targeted directly by property-based tests.
 *
 * <p>All monetary math uses {@link BigDecimal} and results are rounded to 2 decimal places
 * (HALF_UP). The returned discount never exceeds the order value and never goes negative.
 */
public final class DiscountCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private DiscountCalculator() {
    }

    /**
     * Computes the discount amount for {@code orderValue} given the coupon's discount type, value,
     * and (for PERCENTAGE) maximum discount cap.
     *
     * <ul>
     *   <li>FLAT: discount = min(discountValue, orderValue).</li>
     *   <li>PERCENTAGE: discount = min(orderValue × discountValue%, maxDiscountCap, orderValue).</li>
     * </ul>
     *
     * @param type          the discount type (must not be {@code null}).
     * @param discountValue the FLAT amount or the PERCENTAGE value (e.g. 15 for 15%); must be &gt; 0.
     * @param maxDiscountCap the cap applied to PERCENTAGE discounts; ignored for FLAT. May be
     *                       {@code null} for FLAT.
     * @param orderValue    the order value the coupon is applied to; must be &ge; 0.
     * @return the discount amount, rounded to 2 dp, clamped to {@code [0, orderValue]}.
     */
    public static BigDecimal compute(DiscountType type, BigDecimal discountValue,
                                     BigDecimal maxDiscountCap, BigDecimal orderValue) {
        if (type == null) {
            throw new IllegalArgumentException("Discount type is required");
        }
        if (discountValue == null || discountValue.signum() <= 0) {
            throw new IllegalArgumentException("Discount value must be greater than 0");
        }
        if (orderValue == null || orderValue.signum() < 0) {
            throw new IllegalArgumentException("Order value must be 0 or greater");
        }

        BigDecimal raw;
        if (type == DiscountType.FLAT) {
            raw = discountValue;
        } else {
            BigDecimal percentageDiscount = orderValue.multiply(discountValue)
                    .divide(HUNDRED, 2, RoundingMode.HALF_UP);
            if (maxDiscountCap == null) {
                throw new IllegalArgumentException(
                        "A maximum discount cap is required for PERCENTAGE coupons");
            }
            raw = percentageDiscount.min(maxDiscountCap);
        }

        // Never discount more than the order value; never go negative.
        BigDecimal clamped = raw.min(orderValue).max(BigDecimal.ZERO);
        return clamped.setScale(2, RoundingMode.HALF_UP);
    }
}
