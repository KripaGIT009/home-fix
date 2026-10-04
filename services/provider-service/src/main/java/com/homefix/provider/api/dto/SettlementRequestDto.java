package com.homefix.provider.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /providers/{id}/settlements} (Requirement 14.2, 4.9).
 *
 * <p>{@code bankAccountRef} is optional. A settlement is always paid to the provider's stored
 * verified bank account (Requirement 14.3): omit it, or send that account's id
 * ({@code BankAccountResponse.id}); any other value is refused with 400
 * {@code BANK_ACCOUNT_NOT_ON_FILE}.
 */
public record SettlementRequestDto(
        @NotNull(message = "amount is required")
        BigDecimal amount,
        String bankAccountRef) {
}
