package com.homefix.booking.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /internal/bookings/{bookingId}/payment-pending}.
 *
 * @param customerId the customer paying, as the Payment Service resolved them from their token; must
 *                   be the booking's own customer
 */
public record PaymentPendingRequest(

        @NotNull(message = "customerId is required")
        UUID customerId) {
}
