package com.homefix.booking.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound view of the {@code PaymentCompleted} event published by the Payment Service
 * (Requirement 12.6). Consuming it settles the booking at PAYMENT_COMPLETED. Only
 * {@code bookingId} and {@code paymentId} are acted on; the rest is carried for logging and so the
 * record mirrors the producer's payload. Unknown fields are ignored so the producer can evolve its
 * payload without breaking this consumer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentCompletedEvent(
        UUID paymentId,
        UUID bookingId,
        UUID customerId,
        UUID providerId,
        BigDecimal amount,
        BigDecimal platformFee,
        BigDecimal providerNetEarning,
        String paymentMethod,
        Instant completedAt) {

    /** Kafka topic / event type name for this event. */
    public static final String EVENT_TYPE = "PaymentCompleted";
}
