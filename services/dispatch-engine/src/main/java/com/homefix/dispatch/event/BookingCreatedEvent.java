package com.homefix.dispatch.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

/**
 * Inbound {@code BookingCreated} event payload consumed from the Booking Service (Requirement 7.5).
 * Only the fields the Dispatch Engine needs to match a provider are modelled; unknown fields are
 * ignored so the two services can evolve their schemas independently.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingCreatedEvent(
        UUID bookingId,
        UUID customerId,
        UUID subcategoryId,
        double customerLat,
        double customerLon,
        List<String> requiredSkillTags,
        boolean emergency) {
}
