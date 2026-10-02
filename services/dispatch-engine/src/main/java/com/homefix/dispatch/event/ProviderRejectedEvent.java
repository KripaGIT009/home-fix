package com.homefix.dispatch.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published when a provider declines a job offer or lets it time out (Requirement
 * 8.7). Written to the shared outbox and relayed to the {@code ProviderRejected} topic by the
 * Outbox Processor; the Notification Service consumes it.
 *
 * <p>Field names follow the consumer's {@code LifecycleEventPayload}: {@code bookingId},
 * {@code customerId} and {@code providerId}. Events carry user ids, never contact details; the
 * Notification Service resolves recipients itself. {@code customerId} is always known here, since
 * the booking's customer is part of every dispatch request.
 *
 * @param bookingId  the booking that was offered
 * @param customerId the booking's customer
 * @param providerId the provider who declined or did not answer
 * @param reason     {@link #REASON_REJECTED} when the provider declined, {@link #REASON_TIMED_OUT}
 *                   when the offer window elapsed without an answer
 * @param rejectedAt when the Dispatch Engine recorded the outcome
 */
public record ProviderRejectedEvent(
        UUID bookingId,
        UUID customerId,
        UUID providerId,
        String reason,
        Instant rejectedAt) {

    /** Kafka topic / event type name for this event. */
    public static final String EVENT_TYPE = "ProviderRejected";

    /** Aggregate type used for the outbox row. */
    public static final String AGGREGATE_TYPE = "Booking";

    /** The provider explicitly declined the offer. */
    public static final String REASON_REJECTED = "REJECTED";

    /** The offer window elapsed without a response. */
    public static final String REASON_TIMED_OUT = "TIMED_OUT";
}
