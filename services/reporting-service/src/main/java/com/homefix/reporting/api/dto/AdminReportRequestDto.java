package com.homefix.reporting.api.dto;

import java.time.LocalDate;
import java.util.Arrays;

import com.homefix.reporting.domain.ExportFormat;
import com.homefix.reporting.domain.ReportType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The Admin Portal's report request (Requirement 20.4), in the shape of the portal's
 * {@code ReportRequestPayload}. It carries no dimension filters — the portal offers none — and is
 * translated into the existing {@link ReportRequestDto} so both entry points share one generation
 * and authorization path.
 *
 * <p>{@code reportTypeId} is a plain string rather than the enum so an unknown id fails with the
 * service's {@code INVALID_REPORT_REQUEST} envelope instead of an unreadable-body error.
 */
public record AdminReportRequestDto(
        @NotBlank String reportTypeId,
        @NotNull LocalDate fromDate,
        @NotNull LocalDate toDate,
        @NotNull ExportFormat format) {

    /**
     * @throws IllegalArgumentException if {@code reportTypeId} names no {@link ReportType}
     */
    public ReportRequestDto toReportRequest() {
        return new ReportRequestDto(reportType(), fromDate, toDate, null, null, null, format);
    }

    private ReportType reportType() {
        return Arrays.stream(ReportType.values())
                .filter(type -> type.name().equals(reportTypeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown report type '" + reportTypeId + "'"));
    }
}
