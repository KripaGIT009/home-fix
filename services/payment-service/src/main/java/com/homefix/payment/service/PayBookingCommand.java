package com.homefix.payment.service;

import java.util.UUID;

import com.homefix.payment.domain.PaymentMethod;

/**
 * A request to pay for a completed booking (Requirement 12.2, 12.3). It carries only what the
 * paying customer chooses: everything that decides who pays whom and how much (customer, provider,
 * amount, platform fee) comes from the Booking Service and the platform fee rule.
 *
 * @param bookingId            the booking being paid for
 * @param method               the selected payment method
 * @param gatewayId            target gateway, or {@code null} for the configured default gateway
 * @param rawPaymentCredential optional gateway credential token to store encrypted; never a raw card
 *                             number (Requirement 12.9)
 */
public record PayBookingCommand(
        UUID bookingId,
        PaymentMethod method,
        String gatewayId,
        String rawPaymentCredential) {
}
