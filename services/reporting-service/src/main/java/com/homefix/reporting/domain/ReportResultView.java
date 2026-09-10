package com.homefix.reporting.domain;

import java.util.List;

/**
 * A flattened, serialisation-friendly view of a {@link Report}: columns plus rows-of-cells. Used
 * by the REST layer to render the synchronous response body (Requirement 20.2) without exposing
 * the internal {@link ReportRow} record shape.
 */
public record ReportResultView(List<String> columns, List<List<String>> rows) {

    public static ReportResultView of(Report report) {
        List<List<String>> rows = report.rows().stream()
                .map(ReportRow::cells)
                .toList();
        return new ReportResultView(report.columns(), rows);
    }
}
