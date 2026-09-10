package com.homefix.payment.gateway;

/** Result of initiating a settlement bank transfer via a {@link PaymentGatewayPort}. */
public record GatewayTransferResult(String gatewayTransferReference, boolean succeeded) {
}
