package com.homefix.booking.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.pricing.PriceEstimate;

/**
 * Response returned when creating or querying a booking. When returned from a create call the
 * {@code estimate} carries the itemized price breakdown to present to the customer before
 * confirmation (Requirement 7.3); it is null for responses that do not include an estimate.
 */
public record BookingResponse(
        UUID bookingId,
        String reference,
        String status,
        boolean emergency,
        Instant scheduledAt,
        BigDecimal estimatedTotal,
        BigDecimal cancellationFee,
        PriceEstimateView estimate) {

    public static BookingResponse of(Booking b) {
        return new BookingResponse(b.getId(), b.getReference(), b.getStatus().name(),
                b.isEmergency(), b.getScheduledAt(), b.getEstimatedTotal(),
                b.getCancellationFee(), null);
    }

    public static BookingResponse withEstimate(Booking b, PriceEstimate estimate) {
        return new BookingResponse(b.getId(), b.getReference(), b.getStatus().name(),
                b.isEmergency(), b.getScheduledAt(), b.getEstimatedTotal(),
                b.getCancellationFee(), PriceEstimateView.of(estimate));
    }

    /** Itemized estimate view (Requirement 6.9, 7.3). */
    public record PriceEstimateView(BigDecimal total, Map<String, BigDecimal> components) {
        static PriceEstimateView of(PriceEstimate e) {
            return e == null ? null : new PriceEstimateView(e.total(), e.components());
        }
    }
}
