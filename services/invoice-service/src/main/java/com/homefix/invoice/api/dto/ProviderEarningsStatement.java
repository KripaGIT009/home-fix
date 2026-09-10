package com.homefix.invoice.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A Provider's monthly earnings statement (Requirement 13.6): all settled jobs in the month, with
 * gross earnings, platform fees deducted, and net payout. Accessible on the first calendar day of
 * the following month.
 */
public record ProviderEarningsStatement(
        UUID providerId,
        int year,
        int month,
        int jobCount,
        BigDecimal grossEarnings,
        BigDecimal platformFees,
        BigDecimal netPayout,
        boolean available) {
}
