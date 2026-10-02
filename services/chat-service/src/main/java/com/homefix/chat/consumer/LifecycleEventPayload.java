package com.homefix.chat.consumer;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound view of the booking-lifecycle events driving the chat channel lifecycle
 * (Requirement 18.1, 18.5): {@code ProviderAccepted}, {@code PaymentCompleted}, and
 * {@code BookingCancelled}. Producers publish richer payloads; only the fields needed to manage a
 * channel are modelled here, and unknown fields are ignored so producers can evolve independently.
 *
 * <p>The stable {@code eventId} used for deduplication is carried on the Kafka record header, not
 * in this body.
 *
 * @param bookingId        the booking the event relates to (required)
 * @param customerId       the customer on the booking (required for activation; on deactivation,
 *                         recorded in the tombstone if no channel exists yet)
 * @param providerId       the provider on the booking (required for activation; may be null on
 *                         {@code BookingCancelled} for a booking cancelled before assignment)
 * @param bookingCreatedAt the booking's creation timestamp, anchoring the 90-day retention window
 *                         (Requirement 18.4); falls back to now when absent
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LifecycleEventPayload(
        UUID bookingId,
        UUID customerId,
        UUID providerId,
        Instant bookingCreatedAt) {
}
