package com.homefix.booking.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.service.BookingQueryService;

/**
 * One row of the Admin Portal's booking list, {@code GET /admin/bookings}, and the result of its
 * force-cancel (Requirement 19.2) — the portal's {@code AdminBooking} type, field for field.
 *
 * <p>{@code serviceName} and {@code totalAmount} are derived as for a history row:
 * the catalog's subcategory name (or the {@value BookingQueryService#FALLBACK_SERVICE_NAME}
 * fallback) and the final total once set, otherwise the accepted estimate.
 *
 * <h2>Always null</h2>
 * {@code customerName} and {@code providerName} are sent as {@code null}: names live in the Auth and
 * Provider services, which this service has no client for, and inventing a label would be worse
 * than showing none. The portal treats both as optional.
 */
public record AdminBookingResponse(
        UUID id,
        String reference,
        String customerName,
        String providerName,
        String serviceName,
        String status,
        // Named explicitly: the portal reads "isEmergency", and Jackson's bean conventions would
        // otherwise be free to strip the "is" prefix from a boolean accessor.
        @JsonProperty("isEmergency") boolean isEmergency,
        BigDecimal totalAmount,
        String currency,
        Instant createdAt,
        Instant scheduledAt) {

    public static AdminBookingResponse of(BookingQueryService.BookingView view) {
        Booking b = view.booking();
        return new AdminBookingResponse(
                b.getId(),
                b.getReference(),
                null,
                null,
                view.serviceName(),
                b.getStatus().name(),
                b.isEmergency(),
                view.amount(),
                BookingHistoryPageResponse.CURRENCY,
                b.getCreatedAt(),
                b.getScheduledAt());
    }
}
