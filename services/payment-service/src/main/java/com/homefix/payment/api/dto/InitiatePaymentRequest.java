package com.homefix.payment.api.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.homefix.payment.domain.PaymentMethod;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body to pay for a booking (Requirement 12.2, 12.3). The idempotency key is derived
 * server-side from the booking's customer + {@code bookingId}; clients do not supply it.
 *
 * <p>Who pays, who is credited and how much are read from the Booking Service, never from the
 * client. Older clients still send {@code customerId}, {@code providerId}, {@code amount} and
 * {@code platformFee}: those fields are accepted so the request does not fail, and ignored, which
 * is why they are not declared here at all (no code can read them by mistake).
 *
 * @param bookingId         the booking being paid for
 * @param method            the selected payment method
 * @param gatewayId         optional gateway; the configured default gateway when omitted
 * @param paymentCredential optional gateway credential token, stored encrypted (Requirement 12.9)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InitiatePaymentRequest(
        @NotNull UUID bookingId,
        @NotNull PaymentMethod method,
        @Size(max = 32) String gatewayId,
        String paymentCredential) {
}
