package com.homefix.dispatch.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published when a provider accepts a job offer (Requirement 8.6). Written to the
 * shared outbox so it commits atomically with any local state and is relayed to Kafka
 * exactly-once by the Outbox Processor.
 *
 * <p>{@code customerId} is part of the contract, not an optional extra: the Chat Service activates
 * a booking's channel from this event and needs both participants to do so. Before it was added,
 * every {@code ProviderAccepted} record failed the consumer's null check and was dead-lettered, so
 * no chat channel was ever activated. {@code bookingCreatedAt} lets the consumer set the channel's
 * retention window from the booking rather than from its own clock.
 */
public record ProviderAcceptedEvent(
        UUID bookingId,
        UUID customerId,
        UUID providerId,
        Instant bookingCreatedAt,
        Instant acceptedAt) {

    /** Kafka topic / event type name for this event. */
    public static final String EVENT_TYPE = "ProviderAccepted";

    /** Aggregate type used for the outbox row. */
    public static final String AGGREGATE_TYPE = "Booking";
}
