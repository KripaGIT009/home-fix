package com.homefix.booking.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.homefix.booking.media.MediaFile;

/**
 * Framework-agnostic command carrying the inputs to create a booking (scheduled or
 * emergency). Built by the REST layer from the request DTO and the authenticated principal.
 *
 * @param scheduledAt required for scheduled bookings (Requirement 7.1); null/ignored for
 *                    emergency bookings (Requirement 8.1)
 * @param media       optional customer-uploaded media, max 10 (Requirement 7.2)
 */
public record CreateBookingCommand(
        UUID customerId,
        UUID categoryId,
        UUID subcategoryId,
        UUID addressId,
        boolean emergency,
        Instant scheduledAt,
        String description,
        List<MediaFile> media) {

    public CreateBookingCommand {
        media = media == null ? List.of() : List.copyOf(media);
    }
}
