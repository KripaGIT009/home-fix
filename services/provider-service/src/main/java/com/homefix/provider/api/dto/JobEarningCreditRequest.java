package com.homefix.provider.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /internal/providers/{providerId}/earnings}: the Payment Service crediting a
 * paid booking's earning (Requirement 12.10). The net is derived here (gross minus fee), so the two
 * services cannot disagree about it.
 *
 * @param bookingReference the human-readable reference for the earnings line; optional
 */
public record JobEarningCreditRequest(
        @NotNull UUID bookingId,
        String bookingReference,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal gross,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal platformFee) {
}
