package com.homefix.payment.gateway;

import java.math.BigDecimal;

/**
 * Request to initiate a refund against a prior charge via a {@link PaymentGatewayPort}.
 *
 * @param idempotencyKey stable per refund attempt (the HomeFix refund id). Adapters must forward it
 *                       as the gateway's idempotency key so a re-sent refund call cannot refund twice
 *                       at the gateway (Requirement 12.7).
 */
public record GatewayRefundRequest(String gatewayChargeReference, BigDecimal amount, String idempotencyKey) {
}
