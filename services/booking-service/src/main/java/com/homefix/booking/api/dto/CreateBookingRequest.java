package com.homefix.booking.api.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /bookings}. {@code scheduledAt} is required for scheduled
 * bookings and ignored for emergency bookings (determined by {@code emergency}).
 */
public record CreateBookingRequest(
        @NotNull UUID categoryId,
        @NotNull UUID subcategoryId,
        UUID addressId,
        boolean emergency,
        Instant scheduledAt,
        @Size(max = 2000) String description) {
}
