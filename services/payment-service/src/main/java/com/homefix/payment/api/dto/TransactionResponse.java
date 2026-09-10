package com.homefix.payment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.homefix.payment.domain.PaymentMethod;
import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.payment.domain.TransactionStatus;

/**
 * API view of a {@link PaymentTransaction}. Never exposes the stored encrypted credential
 * (Requirement 12.9).
 */
public record TransactionResponse(
        UUID id,
        UUID customerId,
        UUID bookingId,
        UUID providerId,
        BigDecimal amount,
        BigDecimal platformFee,
        BigDecimal providerNetEarning,
        BigDecimal refundedAmount,
        PaymentMethod method,
        String gateway,
        TransactionStatus status,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt) {

    public static TransactionResponse from(PaymentTransaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getCustomerId(),
                tx.getBookingId(),
                tx.getProviderId(),
                tx.getAmount(),
                tx.getPlatformFee(),
                tx.providerNetEarning(),
                tx.getRefundedAmount(),
                tx.getMethod(),
                tx.getGateway(),
                tx.getStatus(),
                tx.getAttemptCount(),
                tx.getCreatedAt(),
                tx.getUpdatedAt());
    }
}
