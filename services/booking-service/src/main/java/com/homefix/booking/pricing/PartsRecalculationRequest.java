package com.homefix.booking.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Inputs sent to the Pricing Engine when a provider adds parts/materials during job execution
 * (Requirement 6.8, 11.3). Carries the booking's original estimated total and the new
 * aggregate parts/materials charge so the Pricing Engine can return an updated itemized total.
 *
 * <p>{@code emergency} and {@code scheduledAt} repeat the booking's own pricing inputs so a
 * re-quote reproduces the multipliers and surcharges the original estimate was built from;
 * without them a recalculation would silently drop, for example, the emergency multiplier.
 */
public record PartsRecalculationRequest(
        UUID bookingId,
        UUID categoryId,
        UUID subcategoryId,
        BigDecimal originalTotal,
        BigDecimal partsTotal,
        boolean emergency,
        Instant scheduledAt) {
}
