package com.homefix.reporting.api.dto;

import java.time.LocalDate;

import com.homefix.reporting.domain.ExportFormat;
import com.homefix.reporting.domain.ReportType;

import jakarta.validation.constraints.NotNull;

/**
 * Inbound report-generation request (Requirement 20.4). Carries the report type, the inclusive
 * date range, optional dimension filters, and the desired export format. The requestor's email for
 * the asynchronous notification is resolved from the authenticated principal, not the body, so it
 * is never accepted as untrusted input.
 */
public record ReportRequestDto(
        @NotNull ReportType reportType,
        @NotNull LocalDate from,
        @NotNull LocalDate to,
        String serviceCategory,
        String region,
        String providerId,
        @NotNull ExportFormat format) {
}
