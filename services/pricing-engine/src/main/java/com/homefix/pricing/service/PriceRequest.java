package com.homefix.pricing.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The inputs to a price calculation (Requirement 6.1–6.10).
 *
 * <p>{@code scheduledLocalTime} is the booking's local date-time, used to decide the night
 * (22:00–06:00) and weekend (Sat/Sun) surcharge windows. Optional fields may be {@code null};
 * the service treats them as absent.
 */
public record PriceRequest(
        UUID subcategoryId,
        boolean emergency,
        boolean surgeActive,
        BigDecimal distanceKm,
        BigDecimal timeCharge,
        BigDecimal partsMaterialsCharge,
        LocalDateTime scheduledLocalTime,
        String couponCode,
        UUID userId,
        BigDecimal orderDiscount) {

    public PriceRequest {
        if (subcategoryId == null) {
            throw new IllegalArgumentException("subcategoryId is required");
        }
    }
}
