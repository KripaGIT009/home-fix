package com.homefix.provider.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.homefix.provider.domain.ProviderEarning;

/**
 * Earnings-history line item (Requirement 14.5): booking reference, gross earnings, platform
 * fee deducted, and net earning per job, plus the entry type for itemised deductions.
 */
public record EarningResponse(
        UUID id,
        UUID bookingId,
        String bookingReference,
        String type,
        BigDecimal gross,
        BigDecimal platformFee,
        BigDecimal net,
        Instant creditedAt) {

    public static EarningResponse from(ProviderEarning e) {
        return new EarningResponse(
                e.getId(),
                e.getBookingId(),
                e.getBookingReference(),
                e.getType().name(),
                e.getGross(),
                e.getPlatformFee(),
                e.getNet(),
                e.getCreditedAt());
    }
}
