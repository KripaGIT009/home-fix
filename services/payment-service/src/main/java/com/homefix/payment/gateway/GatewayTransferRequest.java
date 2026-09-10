package com.homefix.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/** Request to initiate a settlement bank transfer via a {@link PaymentGatewayPort}. */
public record GatewayTransferRequest(UUID providerId, BigDecimal amount, String bankAccountRef) {
}
