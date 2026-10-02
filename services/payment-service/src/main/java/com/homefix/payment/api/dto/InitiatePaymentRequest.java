package com.homefix.payment.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.homefix.payment.domain.PaymentMethod;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * Request body to initiate a payment (Requirement 12.2, 12.3). The idempotency key is derived
 * server-side from {@code customerId} + {@code bookingId}; clients do not supply it.
 *
 * <p>Amounts carry at most two decimal places, matching the {@code numeric(12,2)} columns: a finer
 * amount would be rounded on save while the gateway was charged the unrounded value.
 */
public record InitiatePaymentRequest(
        @NotNull UUID customerId,
        @NotNull UUID bookingId,
        @NotNull UUID providerId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @Digits(integer = 10, fraction = 2) BigDecimal platformFee,
        @NotNull PaymentMethod method,
        @NotNull String gatewayId,
        String paymentCredential) {
}
