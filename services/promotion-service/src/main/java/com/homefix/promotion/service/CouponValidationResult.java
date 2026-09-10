package com.homefix.promotion.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Result of a successful checkout-time coupon validation (Requirement 21.2): the coupon identity
 * and the applicable discount amount for the supplied order value. Constraint violations are not
 * represented here — they are raised as a {@link CouponException} with a specific constraint code.
 */
public record CouponValidationResult(UUID couponId, String code, BigDecimal discountAmount) {
}
