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
 * tags, so these arrive absent and the
 * {@link com.homefix.dispatch.service.BookingEnrichmentService} resolves them from the Customer
 * Service and the Service Catalog before matching. When they were primitives, an absent coordinate
 * silently deserialised to {@code 0.0} and every candidate was scored against the Gulf of Guinea;
 * boxed types make "not supplied" observable, so the enrichment step looks up exactly what is
 * missing instead of guessing. An event that does carry them (an older or test producer) is
 * matched on them as-is.
 *
 * <p>{@code reference} and {@code scheduledAt} are carried through to the job offer so the provider
 * can see which booking and which slot they are being offered; both may be absent.
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
        Instant occurredAt,
        String reference,
        Instant scheduledAt) {

    /** An event without the display-only {@code reference} and {@code scheduledAt}. */
    public BookingCreatedEvent(UUID bookingId, UUID customerId, UUID subcategoryId, UUID addressId,
                               Double customerLat, Double customerLon, List<String> requiredSkillTags,
                               boolean emergency, Instant occurredAt) {
        this(bookingId, customerId, subcategoryId, addressId, customerLat, customerLon,
                requiredSkillTags, emergency, occurredAt, null, null);
    }
}
