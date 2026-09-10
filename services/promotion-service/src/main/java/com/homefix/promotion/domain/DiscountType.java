package com.homefix.promotion.domain;

/**
 * Coupon discount type (Requirement 21.1).
 *
 * <ul>
 *   <li>{@link #FLAT} — a fixed currency amount subtracted from the order.</li>
 *   <li>{@link #PERCENTAGE} — a percentage of the order value, capped by the coupon's maximum
 *       discount cap (which is required for this type).</li>
 * </ul>
 */
public enum DiscountType {
    FLAT,
    PERCENTAGE
}
