package com.homefix.location.booking;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Who a booking is between, as the Booking Service records it. The Location Service needs this to
 * decide whether a caller may see a booking's provider position or post one: only the booking's
 * customer and its assigned provider (plus staff) are entitled to either.
 *
 * <p>Read from the Booking Service's internal {@code GET /internal/bookings/{id}/payment-facts},
 * whose answer carries these fields alongside the amount and reference this service ignores.
 *
 * @param bookingId  the booking
 * @param customerId the customer who booked it
 * @param providerId the provider assigned to it; null until one is
 * @param status     the booking status, as the Booking Service names it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingParticipants(UUID bookingId, UUID customerId, UUID providerId, String status) {

    /** @return whether {@code userId} is this booking's customer. */
    public boolean isCustomer(UUID userId) {
        return userId != null && userId.equals(customerId);
    }

    /** @return whether {@code userId} is this booking's assigned provider. */
    public boolean isAssignedProvider(UUID userId) {
        return userId != null && userId.equals(providerId);
    }
}
