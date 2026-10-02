package com.homefix.dispatch.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The dispatch problem derived from a {@code BookingCreated} event: which booking needs a
 * provider, where the customer is, which skills are required, and whether this is an emergency
 * (Requirement 8.2).
 *
 * @param bookingId      the booking awaiting a provider
 * @param customerId     the customer who created the booking (for failure notification)
 * @param customerLat    customer latitude (decimal degrees)
 * @param customerLon    customer longitude (decimal degrees)
 * @param subcategoryId  the requested service subcategory
 * @param requiredSkillTags at-least-one-of skill tags the provider must carry
 * @param emergency      whether this is an emergency booking (adds the emergency-availability filter)
 * @param bookingCreatedAt when the booking was created; carried through to the ProviderAccepted
 *                         event so downstream consumers can set retention windows from the booking
 *                         rather than from their own clock
 * @param reference      the customer-facing booking reference, shown on the job offer; may be null
 * @param scheduledAt    the booked slot, shown on the job offer; may be null
 */
public record DispatchRequest(
        UUID bookingId,
        UUID customerId,
        double customerLat,
        double customerLon,
        UUID subcategoryId,
        List<String> requiredSkillTags,
        boolean emergency,
        Instant bookingCreatedAt,
        String reference,
        Instant scheduledAt) {

    public DispatchRequest {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(subcategoryId, "subcategoryId must not be null");
        requiredSkillTags = requiredSkillTags == null ? List.of() : List.copyOf(requiredSkillTags);
    }

    /** A request without the display-only {@code reference} and {@code scheduledAt}. */
    public DispatchRequest(UUID bookingId, UUID customerId, double customerLat, double customerLon,
                           UUID subcategoryId, List<String> requiredSkillTags, boolean emergency,
                           Instant bookingCreatedAt) {
        this(bookingId, customerId, customerLat, customerLon, subcategoryId, requiredSkillTags,
                emergency, bookingCreatedAt, null, null);
    }
}
