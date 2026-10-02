package com.homefix.booking.api.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /bookings}. {@code scheduledAt} is required for scheduled
 * bookings and ignored for emergency bookings (determined by {@code emergency}).
 *
 * <p>{@code addressId} is required: the Dispatch Engine resolves the service location only
 * through the Customer Service's saved address, so a booking without one can never be matched
 * and would be dead-lettered after it was confirmed. {@code couponCode} is optional; when
 * present it is passed to the Pricing Engine so the booking is priced with the same discount
 * the customer was shown on the estimate (Requirement 6.10).
 */
public record CreateBookingRequest(
        @NotNull UUID categoryId,
        @NotNull UUID subcategoryId,
        @NotNull UUID addressId,
        boolean emergency,
        Instant scheduledAt,
        @Size(max = 2000) String description,
        @Size(max = 32) String couponCode) {
}
