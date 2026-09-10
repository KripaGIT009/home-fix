package com.homefix.reporting.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.homefix.reporting.domain.GenerationMode;
import com.homefix.reporting.domain.Report;
import com.homefix.reporting.domain.ReportResultView;
import com.homefix.reporting.report.ReportResult;

/**
 * Outbound representation of a report request outcome.
 *
 * <ul>
 *   <li>Synchronous: {@code mode=SYNCHRONOUS}, the columns/rows are populated, and {@code message}
 *       carries the "no data" text when empty (Requirement 20.2).</li>
 *   <li>Asynchronous: {@code mode=ASYNCHRONOUS}, {@code accepted=true}, and the report body is
 *       omitted — the requestor is emailed a download link when ready (Requirement 20.3).</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportResponseDto(
        GenerationMode mode,
        boolean accepted,
        String reportType,
        List<String> columns,
        List<List<String>> rows,
        String message) {

    public static ReportResponseDto from(ReportResult result) {
        if (result.mode() == GenerationMode.ASYNCHRONOUS) {
            return new ReportResponseDto(GenerationMode.ASYNCHRONOUS, true, null, null, null,
                    "Report is being generated; a download link will be emailed when ready.");
        }
        Report report = result.report();
        ReportResultView view = ReportResultView.of(report);
        return new ReportResponseDto(
                GenerationMode.SYNCHRONOUS,
                false,
                report.type().name(),
                view.columns(),
                view.rows(),
                report.message());
    }
}
