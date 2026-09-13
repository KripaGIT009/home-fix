package com.homefix.provider.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Balance and bank accounts a settlement request can draw on (Requirement 14.2).
 *
 * @param availableBalance wallet balance a settlement may be requested against
 * @param currency         ISO 4217 code the balance is denominated in
 * @param bankAccounts     accounts on file; only verified ones can receive a settlement
 */
public record SettlementInfoResponse(
        BigDecimal availableBalance,
        String currency,
        List<BankAccountResponse> bankAccounts) {
}
