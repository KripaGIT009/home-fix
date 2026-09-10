package com.homefix.complaint.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Request body for a Support_Agent-approved refund on a complaint (Requirement 16.5). */
public record RefundRequest(@NotNull @Positive BigDecimal amount) {
}
