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
 * @param bookingReference         the booking's human-readable reference, sent with the provider's
 *                                 wallet credit so the earnings line names the job; optional
 */
public record InitiatePaymentCommand(
        UUID customerId,
        UUID bookingId,
        UUID providerId,
        BigDecimal amount,
        BigDecimal platformFee,
        PaymentMethod method,
        String gatewayId,
        String rawPaymentCredential,
        String bookingReference) {

    /** A command without a booking reference. */
    public InitiatePaymentCommand(UUID customerId, UUID bookingId, UUID providerId, BigDecimal amount,
                                  BigDecimal platformFee, PaymentMethod method, String gatewayId,
                                  String rawPaymentCredential) {
        this(customerId, bookingId, providerId, amount, platformFee, method, gatewayId,
                rawPaymentCredential, null);
    }
}
