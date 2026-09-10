package com.homefix.promotion.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Checkout-time coupon validation request (Requirement 21.2): the coupon code, the customer the
 * coupon is being applied for, and the current order value. The response is a
 * {@code CouponValidationResult} carrying the applicable discount, or a constraint-violation error.
 */
public record ValidateCouponRequest(
        @NotBlank
        String code,

        @NotNull
        UUID userId,

        @NotNull
        @PositiveOrZero
        BigDecimal orderValue) {
}
