package com.homefix.promotion.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Redemption / cancellation request (Requirement 21.3, 21.5): the coupon code and the customer the
 * counters move for. Used by both the redeem and cancel endpoints since they share the same shape.
 */
public record RedeemCouponRequest(
        @NotBlank
        String code,

        @NotNull
        UUID userId) {
}
