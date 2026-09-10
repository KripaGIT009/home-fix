package com.homefix.payment.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.homefix.payment.domain.PaymentMethod;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Request body to initiate a payment (Requirement 12.2, 12.3). The idempotency key is derived
 * server-side from {@code customerId} + {@code bookingId}; clients do not supply it.
 */
public record InitiatePaymentRequest(
        @NotNull UUID customerId,
        @NotNull UUID bookingId,
        @NotNull UUID providerId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        BigDecimal platformFee,
        @NotNull PaymentMethod method,
        @NotNull String gatewayId,
        String paymentCredential) {
}
