package com.homefix.payment.gateway;

/** Result of initiating a charge with a {@link PaymentGatewayPort}. */
public record GatewayChargeResult(String gatewayReference, boolean accepted) {
}
