package com.homefix.complaint.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for a Support_Agent-approved refund on a complaint (Requirement 16.5).
 *
 * @param amount         the refund amount; positive, at most two decimal places
 * @param reason         the agent's justification, recorded on the refund for audit (optional)
 * @param idempotencyKey optional client key; a retry with the same key and amount replays the
 *                       recorded outcome instead of being refused as a second refund
 */
public record RefundRequest(
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @Size(max = 500) String reason,
        @Size(max = 64) String idempotencyKey) {
}
