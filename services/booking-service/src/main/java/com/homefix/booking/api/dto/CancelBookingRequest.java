package com.homefix.booking.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /bookings/{reference}/cancellation}. An optional reason is
 * recorded in the audit trail.
 */
public record CancelBookingRequest(@Size(max = 500) String reason) {
}
