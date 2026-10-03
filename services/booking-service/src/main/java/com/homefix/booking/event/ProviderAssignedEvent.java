package com.homefix.booking.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of the {@code ProviderAssigned} event, written to the transactional outbox in the same
 * transaction as a transition into PROVIDER_ASSIGNED after which the booking
 * rests in PROVIDER_ASSIGNED, awaiting the provider's acceptance (Requirement 22.1, 22.2) — today
 * that is a Tenant assignment (Requirement MT-5.3).
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
 * @param tenantId         the Tenant that assigned the job; null unless the booking went through
 *                         the Tenant fallback (Requirement MT-5.3)
 * @param tenantName       that Tenant's name, which the provider's notification names ("assigned to
 *                         you by ..."); null when there is no Tenant or its name could not be read.
 *                         Both are additions at the end, so consumers that ignore unknown fields
 *                         are unaffected.
 */
public record ProviderAssignedEvent(
        UUID bookingId,
        String reference,
        UUID customerId,
        UUID providerId,
        Instant bookingCreatedAt,
        Instant occurredAt,
        UUID tenantId,
        String tenantName) {

    public static final String AGGREGATE_TYPE = "Booking";
    public static final String EVENT_TYPE = "ProviderAssigned";
}
