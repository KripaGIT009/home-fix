package com.homefix.payment.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** Request body to refund a transaction (Requirement 12.7). */
public record RefundRequest(@NotNull @DecimalMin("0.01") BigDecimal amount) {
}
