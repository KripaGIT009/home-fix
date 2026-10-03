package com.homefix.booking.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.homefix.booking.address.CustomerAddressPort.ServiceAddress;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.service.TenantBookingService.TenantBookingView;

/**
 * A booking as the Tenant Portal shows it, in the queue ({@code GET /tenant/bookings/queue}), the
 * Tenant's job list ({@code GET /tenant/bookings}) and the result of an assignment — the design's
 * {@code TenantBooking} (Requirement MT-5.1, MT-8.3).
 *
 * <p>{@code address} and {@code coordinates} are what a Provider sees for an assigned job and no
 * more (design "Security"): the customer's name and contact details are not sent. Both are null when
 * the Customer Service cannot resolve the address. {@code queuedAt} is when the booking first
 * entered the queue (null for a booking the Tenant got through automatic matching); {@code amount}
 * is the final total once set, otherwise the accepted estimate.
 */
public record TenantBookingResponse(
        UUID id,
        String reference,
        String serviceName,
        String status,
        // Named explicitly, as on AdminBookingResponse: Jackson would otherwise be free to strip
        // the "is" prefix from a boolean accessor.
        @JsonProperty("isEmergency") boolean isEmergency,
        Instant scheduledAt,
        Instant createdAt,
        Instant queuedAt,
        BigDecimal amount,
        String currency,
        String address,
        BookingDetailResponse.Coordinates coordinates,
        UUID providerId) {

    public static TenantBookingResponse of(TenantBookingView row) {
        Booking b = row.view().booking();
        ServiceAddress address = row.address();
        return new TenantBookingResponse(
                b.getId(),
                b.getReference(),
                row.view().serviceName(),
                b.getStatus().name(),
                b.isEmergency(),
                b.getScheduledAt(),
                b.getCreatedAt(),
                b.getQueuedForAssignmentAt(),
                row.view().amount(),
                BookingHistoryPageResponse.CURRENCY,
                address == null ? null : address.label(),
                address == null ? null
                        : new BookingDetailResponse.Coordinates(address.latitude(), address.longitude()),
                b.getProviderId());
    }
}
