package com.homefix.booking.api.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import com.homefix.booking.domain.Booking;

/**
 * What the Payment Service needs to charge a booking, {@code GET /internal/bookings/{id}/payment-facts}
 * and the answer of {@code POST .../payment-pending} (Requirement 12.1).
 *
 * <p>The Payment Service used to take the customer, provider, amount and fee from the client's
 * request body, so a customer could pay any amount to any provider. It now reads them here: the
 * customer it checks the caller against, the provider it credits, and the {@code amount} it charges,
 * which is {@link Booking#payableTotal()} — the same figure the customer app shows — at two decimal
 * places.
 *
 * @param status the booking's current status, which decides whether a payment may start
 * @param amount null only for a booking with neither a final nor an estimated total
 */
public record BookingPaymentFactsResponse(
        UUID bookingId,
        String reference,
        UUID customerId,
        UUID providerId,
        String status,
        BigDecimal amount,
        String currency) {

    public static BookingPaymentFactsResponse of(Booking booking) {
        BigDecimal amount = booking.payableTotal();
        return new BookingPaymentFactsResponse(
                booking.getId(),
                booking.getReference(),
                booking.getCustomerId(),
                booking.getProviderId(),
                booking.getStatus().name(),
                amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP),
                BookingHistoryPageResponse.CURRENCY);
    }
}
