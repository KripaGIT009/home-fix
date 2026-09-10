package com.homefix.payment.gateway;

/** Result of initiating a refund via a {@link PaymentGatewayPort}. */
public record GatewayRefundResult(String gatewayRefundReference, boolean succeeded) {
}
