package com.homefix.provider.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One active job card on the provider dashboard (Requirement 28.8).
 *
 * @param bookingId        the booking's identifier
 * @param reference        short human reference for the job
 * @param serviceName      subcategory display name, empty when the catalog cannot be reached
 * @param status           booking lifecycle state
 * @param isEmergency      emergency bookings are surfaced prominently
 * @param scheduledAt      slot start time
 * @param customerArea     customer locality label; empty until the Customer Service lookup is wired
 * @param estimatedEarning estimated payout, or {@code null} when not yet known
 */
public record ActiveJobResponse(
        UUID bookingId,
        String reference,
        String serviceName,
        String status,
        boolean isEmergency,
        Instant scheduledAt,
        String customerArea,
        BigDecimal estimatedEarning) {
}
