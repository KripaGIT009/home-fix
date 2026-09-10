package com.homefix.booking.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of the {@code BookingCreated} event published to the Kafka outbox when a booking
 * transitions to SEARCHING_PROVIDER (Requirement 7.5). The Dispatch Engine (Task 16) consumes
 * this to begin provider matching.
 */
public record BookingCreatedEvent(
        UUID bookingId,
        String reference,
        UUID customerId,
        UUID categoryId,
        UUID subcategoryId,
        UUID addressId,
        boolean emergency,
        Instant scheduledAt,
        Instant occurredAt) {

    public static final String AGGREGATE_TYPE = "Booking";
    public static final String EVENT_TYPE = "BookingCreated";
}
