package com.homefix.dispatch.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published when a provider accepts a job offer (Requirement 8.6). Written to the
 * shared outbox so it commits atomically with any local state and is relayed to Kafka
 * exactly-once by the Outbox Processor.
 */
public record ProviderAcceptedEvent(
        UUID bookingId,
        UUID providerId,
        Instant acceptedAt) {

    /** Kafka topic / event type name for this event. */
    public static final String EVENT_TYPE = "ProviderAccepted";

    /** Aggregate type used for the outbox row. */
    public static final String AGGREGATE_TYPE = "Booking";
}
