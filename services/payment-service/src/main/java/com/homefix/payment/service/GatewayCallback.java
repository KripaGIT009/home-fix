package com.homefix.payment.service;

/**
 * A payment gateway callback (webhook) received asynchronously (Requirement 12.5).
 *
 * @param gatewayId    the gateway that sent the callback
 * @param payload      the raw payload bytes as received (the signature is computed over this)
 * @param signature    the signature header supplied by the gateway
 * @param succeeded    whether the gateway reports the charge succeeded
 * @param failureReason optional failure reason when {@code succeeded} is false
 */
public record GatewayCallback(
        String gatewayId,
        String payload,
        String signature,
        boolean succeeded,
        String failureReason) {
}
