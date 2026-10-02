package com.homefix.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

import com.homefix.payment.domain.PaymentMethod;

/**
 * Request to initiate a charge with a {@link PaymentGatewayPort}.
 *
 * @param transactionId the HomeFix transaction id. Adapters must pass it to the gateway as the
 *                      merchant reference / metadata so the gateway echoes it in the signed callback
 *                      (which is rejected unless it names this id), and may use it as the gateway-side
 *                      idempotency key for the charge.
 */
public record GatewayChargeRequest(
        UUID transactionId,
        UUID bookingId,
        UUID customerId,
        BigDecimal amount,
        PaymentMethod method) {
}
