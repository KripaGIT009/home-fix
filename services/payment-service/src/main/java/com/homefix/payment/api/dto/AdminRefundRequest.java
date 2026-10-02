package com.homefix.payment.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /admin/payments/{id}/refund} (Requirement 12.7, 19.2).
 *
 * <p>Unlike {@link RefundRequest} the idempotency key is not in the body: the Admin Portal sends it
 * as the {@code Idempotency-Key} header, and the controller passes it to the same refund flow.
 *
 * @param amount at most two decimal places, matching the {@code numeric(12,2)} columns
 * @param reason why the refund is issued; required, and logged with the acting staff member
 */
public record AdminRefundRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 500) String reason) {
}
