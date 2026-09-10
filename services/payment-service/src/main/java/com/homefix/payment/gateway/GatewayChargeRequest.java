package com.homefix.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

import com.homefix.payment.domain.PaymentMethod;

/** Request to initiate a charge with a {@link PaymentGatewayPort}. */
public record GatewayChargeRequest(
        UUID bookingId,
        UUID customerId,
        BigDecimal amount,
        PaymentMethod method) {
}
