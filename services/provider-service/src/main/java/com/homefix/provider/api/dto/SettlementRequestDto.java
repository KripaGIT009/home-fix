package com.homefix.provider.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /providers/{id}/settlements} (Requirement 14.2, 4.9).
 *
 * <p>{@code bankAccountRef} is optional: when omitted, the provider's stored verified bank
 * account is used. When provided, it is encrypted before persistence.
 */
public record SettlementRequestDto(
        @NotNull(message = "amount is required")
        BigDecimal amount,
        String bankAccountRef) {
}
