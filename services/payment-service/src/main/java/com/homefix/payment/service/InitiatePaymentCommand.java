package com.homefix.payment.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.homefix.payment.domain.PaymentMethod;

/**
 * Command to initiate a payment for a booking (Requirement 12.2, 12.3, 12.10).
 *
 * @param customerId               paying customer (part of the idempotency scope)
 * @param bookingId                booking being paid for (part of the idempotency scope)
 * @param providerId               provider who will be credited on success
 * @param amount                   total payment amount (BigDecimal)
 * @param platformFee              platform fee amount to deduct for provider net; if {@code null},
 *                                 it is derived from the configured default platform fee percent
 * @param method                   selected payment method
 * @param gatewayId                target gateway id (e.g. "razorpay", "stripe")
 * @param rawPaymentCredential     optional gateway credential token to store encrypted; must never
 *                                 be a raw card number (Requirement 12.9)
 */
public record InitiatePaymentCommand(
        UUID customerId,
        UUID bookingId,
        UUID providerId,
        BigDecimal amount,
        BigDecimal platformFee,
        PaymentMethod method,
        String gatewayId,
        String rawPaymentCredential) {
}
