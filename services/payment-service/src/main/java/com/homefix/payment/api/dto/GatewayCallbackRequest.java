package com.homefix.payment.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for a gateway callback/webhook (Requirement 12.5). The {@code payload} is the exact
 * text over which the gateway computed the {@code signature}.
 */
public record GatewayCallbackRequest(
        @NotNull String gatewayId,
        @NotNull String payload,
        @NotNull String signature,
        boolean succeeded,
        String failureReason) {
}
