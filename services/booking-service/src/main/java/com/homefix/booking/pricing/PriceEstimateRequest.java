package com.homefix.booking.pricing;

import java.time.Instant;
import java.util.UUID;

/**
 * Inputs the Booking Service sends to the Pricing Engine when requesting an estimate
 * (Requirement 7.3). Emergency bookings request an estimate with the emergency flag so the
 * Pricing Engine applies the emergency multiplier (Requirement 6.2).
 */
public record PriceEstimateRequest(
        UUID categoryId,
        UUID subcategoryId,
        UUID customerId,
        boolean emergency,
        Instant scheduledAt) {
}
