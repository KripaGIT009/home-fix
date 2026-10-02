package com.homefix.payment.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/** Request body to initiate a settlement bank transfer (Requirement 14.3). */
public record SettlementRequest(
        @NotNull UUID providerId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull String bankAccountRef,
        @NotNull String gatewayId) {
}
