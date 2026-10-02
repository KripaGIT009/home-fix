package com.homefix.payment.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for a gateway callback/webhook (Requirement 12.5). The {@code payload} is the exact
 * text over which the gateway computed the {@code signature}, and it alone carries the outcome:
 * a JSON object with {@code eventId}, {@code transactionId}, {@code gatewayId}, {@code status},
 * {@code amount}, {@code timestamp} and optionally {@code failureReason} (see
 * {@code SignedCallbackPayload}).
 *
 * <p>The former unsigned {@code succeeded} and {@code failureReason} fields have been removed: they
 * decided the outcome without being covered by the signature. A body that still sends them is
 * accepted, but they are ignored (Spring Boot's Jackson setup ignores unknown properties); only the
 * signed payload is ever consulted.
 */
public record GatewayCallbackRequest(
        @NotNull String gatewayId,
        @NotNull String payload,
        @NotNull String signature) {
}
