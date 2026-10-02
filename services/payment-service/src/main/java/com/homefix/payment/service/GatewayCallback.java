package com.homefix.payment.service;

/**
 * A payment gateway callback (webhook) received asynchronously (Requirement 12.5).
 *
 * <p>Only {@code payload} is authenticated. The outcome, failure reason, transaction id and amount
 * are all read from inside that signed payload (see {@link SignedCallbackPayload}); this record
 * deliberately carries no unsigned outcome fields, so nothing outside the signature can influence
 * what the callback does.
 *
 * @param gatewayId the gateway that sent the callback; selects the secret that verifies the payload
 * @param payload   the raw payload text as received (the signature is computed over this)
 * @param signature the signature supplied by the gateway
 */
public record GatewayCallback(
        String gatewayId,
        String payload,
        String signature) {
}
