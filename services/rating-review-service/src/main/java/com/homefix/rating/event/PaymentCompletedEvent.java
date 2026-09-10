package com.homefix.rating.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound view of the {@code PaymentCompleted} event published by the Payment Service
 * (Requirement 12.6). Consuming this opens 7-day review prompts for the customer and the provider
 * (Requirement 15.1, 15.10). Unknown fields are ignored so the producer can evolve its payload
 * without breaking this consumer.
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
