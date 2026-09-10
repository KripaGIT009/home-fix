package com.homefix.dispatch.domain;

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
 */
public record DispatchRequest(
        UUID bookingId,
        UUID customerId,
        double customerLat,
        double customerLon,
        UUID subcategoryId,
        List<String> requiredSkillTags,
        boolean emergency) {

    public DispatchRequest {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(subcategoryId, "subcategoryId must not be null");
        requiredSkillTags = requiredSkillTags == null ? List.of() : List.copyOf(requiredSkillTags);
    }
}
