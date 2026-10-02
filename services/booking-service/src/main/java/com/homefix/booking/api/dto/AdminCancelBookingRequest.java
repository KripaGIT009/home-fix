package com.homefix.booking.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /admin/bookings/{id}/cancel}. Unlike a customer's cancellation the
 * reason is required: a staff member cancelling someone else's booking must say why, and the
 * reason is what the booking's audit trail records against the transition (Requirement 9.15).
 * The bound matches the audit column.
 */
public record AdminCancelBookingRequest(@NotBlank @Size(max = 500) String reason) {
}
