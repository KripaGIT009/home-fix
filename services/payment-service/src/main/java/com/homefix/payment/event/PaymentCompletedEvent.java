package com.homefix.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published when a payment transitions to SUCCESS (Requirement 12.6). Written to the
 * shared outbox so it commits atomically with the transaction state change and is relayed to Kafka
 * exactly-once by the Outbox Processor. The Invoice Service consumes this to generate the invoice.
 */
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

    /** Aggregate type used for the outbox row. */
    public static final String AGGREGATE_TYPE = "Payment";
}
