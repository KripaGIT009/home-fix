package com.homefix.pricing.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.homefix.pricing.service.PriceRequest;

import jakarta.validation.constraints.NotNull;

/**
 * Inbound payload for a price estimate (Requirement 6.9). Optional fields may be omitted.
 */
public record PriceRequestDto(
        @NotNull UUID subcategoryId,
        boolean emergency,
        boolean surgeActive,
        BigDecimal distanceKm,
        BigDecimal timeCharge,
        BigDecimal partsMaterialsCharge,
        LocalDateTime scheduledLocalTime,
        String couponCode,
        UUID userId,
        BigDecimal orderDiscount) {

    public PriceRequest toDomain() {
        return new PriceRequest(subcategoryId, emergency, surgeActive, distanceKm, timeCharge,
                partsMaterialsCharge, scheduledLocalTime, couponCode, userId, orderDiscount);
    }
}
