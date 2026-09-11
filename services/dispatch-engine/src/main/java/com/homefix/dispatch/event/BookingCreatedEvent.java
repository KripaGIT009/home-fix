package com.homefix.dispatch.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inbound {@code BookingCreated} event payload consumed from the Booking Service (Requirement 7.5).
 * Only the fields the Dispatch Engine needs are modelled; unknown fields are ignored so the two
 * services can evolve their schemas independently.
 *
 * <p>The location and skill fields are deliberately boxed. The Booking Service owns booking facts
 * and publishes {@code addressId} and {@code subcategoryId}, not denormalised coordinates or skill
 * tags, so these arrive absent and the Dispatch Engine resolves them itself before matching. When
 * they were primitives, an absent coordinate silently deserialised to {@code 0.0} and every
 * candidate was scored against the Gulf of Guinea; boxed types make "not supplied" observable, and
 * {@link #requiresEnrichment()} is what the consumer asks instead of guessing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingCreatedEvent(
        UUID bookingId,
        UUID customerId,
        UUID subcategoryId,
        UUID addressId,
        Double customerLat,
        Double customerLon,
        List<String> requiredSkillTags,
        boolean emergency,
        Instant occurredAt) {

    /**
     * Whether the Dispatch Engine must resolve the customer location or the required skills before
     * it can match a provider. True for every event the Booking Service publishes today.
     */
    public boolean requiresEnrichment() {
        return customerLat == null || customerLon == null
                || requiredSkillTags == null || requiredSkillTags.isEmpty();
    }
}
