package com.homefix.booking.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of the {@code ProviderAssigned} event, written to the transactional outbox in the same
 * transaction as a SEARCHING_PROVIDER -> PROVIDER_ASSIGNED transition after which the booking
 * rests in PROVIDER_ASSIGNED, awaiting the provider's acceptance (Requirement 22.1, 22.2).
 *
 * <p>notification-service consumes it to tell the customer and the assigned provider (Requirement
 * 17.5); it requires {@code customerId} and renders {@code reference}.
 *
 * <p>It is <em>not</em> published by the Dispatch Engine's acceptance callback, which assigns and
 * accepts in one transaction (see {@code BookingTransitionService#transitionPassingThrough}); the
 * Dispatch Engine's own {@code ProviderAccepted} announces that.
 *
 * @param bookingId        the booking (always present)
 * @param reference        the customer-facing booking reference
 * @param customerId       the booking's customer
 * @param providerId       the assigned provider
 * @param bookingCreatedAt when the booking was created
 * @param occurredAt       when the transition was applied
 */
public record ProviderAssignedEvent(
        UUID bookingId,
        String reference,
        UUID customerId,
        UUID providerId,
        Instant bookingCreatedAt,
        Instant occurredAt) {

    public static final String AGGREGATE_TYPE = "Booking";
    public static final String EVENT_TYPE = "ProviderAssigned";
}
