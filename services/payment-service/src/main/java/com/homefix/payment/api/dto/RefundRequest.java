package com.homefix.payment.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body to refund a transaction (Requirement 12.7).
 *
 * @param amount         at most two decimal places, matching the {@code numeric(12,2)} columns.
 * @param idempotencyKey client-chosen key identifying this refund request, scoped to the
 *                       transaction. Re-sending the same key (with the same amount) never refunds
 *                       twice; use a new key for a genuinely new refund. After a
 *                       {@code 502 REFUND_OUTCOME_UNKNOWN}, retry with the <em>same</em> key: the
 *                       refund may have executed, and only the same key lets the gateway recognise
 *                       the retry.
 */
public record RefundRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 64) String idempotencyKey) {
}
