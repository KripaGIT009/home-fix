package com.homefix.booking.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.homefix.booking.domain.Booking;

/**
 * One of a provider's in-flight jobs, as served to the Provider Service for its dashboard
 * (Requirement 28.8).
 *
 * <p>Carries only what the Booking Service owns. The service name behind {@code subcategoryId} and
 * the customer's locality live in the Catalog and Customer services respectively, so the caller
 * resolves those — this response deliberately does not reach across those boundaries.
 */
public record ProviderJobResponse(
        UUID bookingId,
        String reference,
        UUID subcategoryId,
        String status,
        boolean emergency,
        Instant scheduledAt,
        BigDecimal estimatedTotal) {

    public static ProviderJobResponse of(Booking booking) {
        return new ProviderJobResponse(
                booking.getId(),
                booking.getReference(),
                booking.getSubcategoryId(),
                booking.getStatus().name(),
                booking.isEmergency(),
                booking.getScheduledAt(),
                booking.getEstimatedTotal());
    }
}
