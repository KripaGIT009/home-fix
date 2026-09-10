package com.homefix.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Centralised monetary rounding policy for the Pricing Engine.
 *
 * <p>All monetary math uses {@link BigDecimal}; every component and the final total is
 * normalised through {@link #round(BigDecimal)} to a single scale/rounding mode so the sum of
 * the itemised components exactly equals the returned total (Requirement 6.9, Property 6). No
 * intermediate {@code double} arithmetic is used anywhere in the engine.
 */
public final class Money {

    private final int scale;
    private final RoundingMode roundingMode;

    public Money(int scale, RoundingMode roundingMode) {
        this.scale = scale;
        this.roundingMode = roundingMode;
    }

    /** Rounds a value to the configured monetary scale and rounding mode. */
    public BigDecimal round(BigDecimal value) {
        return value.setScale(scale, roundingMode);
    }

    /** Zero at the configured scale. */
    public BigDecimal zero() {
        return round(BigDecimal.ZERO);
    }

    /** Null-safe rounding: treats {@code null} as zero. */
    public BigDecimal roundOrZero(BigDecimal value) {
        return value == null ? zero() : round(value);
    }

    public int getScale() {
        return scale;
    }

    public RoundingMode getRoundingMode() {
        return roundingMode;
    }
}
