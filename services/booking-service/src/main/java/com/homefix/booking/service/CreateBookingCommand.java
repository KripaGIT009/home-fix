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
 * @param couponCode  optional coupon to price the booking with (Requirement 6.10); blank is
 *                    treated as none
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
        String couponCode,
        List<MediaFile> media) {

    public CreateBookingCommand {
        couponCode = couponCode == null || couponCode.isBlank() ? null : couponCode.strip();
        media = media == null ? List.of() : List.copyOf(media);
    }
}
