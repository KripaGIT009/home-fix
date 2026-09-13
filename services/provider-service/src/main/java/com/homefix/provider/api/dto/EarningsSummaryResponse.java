package com.homefix.provider.api.dto;

import java.math.BigDecimal;

/**
 * Wallet and today's-earnings snapshot shown at the top of the provider dashboard
 * (Requirement 14.1).
 *
 * @param walletBalance cumulative balance available in the provider's wallet
 * @param todayEarnings net earnings credited since the start of the current day
 * @param todayJobCount number of jobs credited today, as context for {@code todayEarnings}
 * @param currency      ISO 4217 code the amounts are denominated in
 */
public record EarningsSummaryResponse(
        BigDecimal walletBalance,
        BigDecimal todayEarnings,
        int todayJobCount,
        String currency) {
}
