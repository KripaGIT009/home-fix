package com.homefix.booking.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of the {@code ProviderAccepted} event booking-service writes when a Provider confirms a
 * Tenant assignment (Requirement MT-6.1).
 *
 * <p>Field for field the Dispatch Engine's {@code com.homefix.dispatch.event.ProviderAcceptedEvent},
 * with the same event and aggregate type, so chat-service (which activates the booking's channel
 * from {@code bookingId}, {@code customerId}, {@code providerId} and {@code bookingCreatedAt}) and
 * notification-service cannot tell a Tenant-assigned acceptance from an automatic one (Property
 * MT7). Unlike the Dispatch Engine's, this row is written in the same transaction as the
 * PROVIDER_ASSIGNED -> PROVIDER_ACCEPTED transition, so the status and the event cannot disagree.
 * If the Dispatch Engine's record changes, change this one with it.
 */
public record ProviderAcceptedEvent(
        UUID bookingId,
        UUID customerId,
        UUID providerId,
        Instant bookingCreatedAt,
        Instant acceptedAt) {

    public static final String EVENT_TYPE = "ProviderAccepted";

    public static final String AGGREGATE_TYPE = "Booking";
}
