package com.homefix.payment.gateway;

import java.math.BigDecimal;

/** Request to initiate a refund against a prior charge via a {@link PaymentGatewayPort}. */
public record GatewayRefundRequest(String gatewayChargeReference, BigDecimal amount) {
}
