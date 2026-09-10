package com.homefix.pricing.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A coupon and its constraints, validated when applied to a booking price (Requirement 6.10).
 *
 * <p>Discount is expressed either as a flat {@code amount} or a {@code percentage} fraction
 * (e.g. {@code 0.10} for 10%). The engine validates active status, the {@code validFrom}/
 * {@code validUntil} date range, minimum order value, per-user usage limit and total usage
 * limit; any violation yields a descriptive error identifying the violated constraint.
 */
public record Coupon(
        String code,
        boolean active,
        Instant validFrom,
        Instant validUntil,
        BigDecimal minOrderValue,
        BigDecimal amount,
        BigDecimal percentage,
        Integer perUserLimit,
        Integer totalLimit) {

    public Coupon {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("coupon code is required");
        }
    }

    /** @return {@code true} if this coupon discounts by a percentage rather than a flat amount. */
    public boolean isPercentage() {
        return percentage != null && percentage.signum() > 0;
    }
}
