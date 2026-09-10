package com.homefix.reporting.domain;

import java.util.List;

/**
 * A fully materialised report: its type, the column headers, the data rows, and an optional
 * human-readable message.
 *
 * <p>When a query matches no data the report is returned {@link #isEmpty() empty} with a message
 * indicating no data matched the criteria (Requirement 20.2). The report contains only aggregated,
 * non-PII values (Requirement 26.4).
 */
public record Report(
        ReportType type,
        List<String> columns,
        List<ReportRow> rows,
        String message) {

    public Report {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** Builds a populated report with no accompanying message. */
    public static Report of(ReportType type, List<String> columns, List<ReportRow> rows) {
        return new Report(type, columns, rows, null);
    }

    /**
     * Builds the empty-result report returned when a query matches no data (Requirement 20.2),
     * carrying the standard "no data" message and the report's column headers.
     */
    public static Report empty(ReportType type, List<String> columns) {
        return new Report(type, columns, List.of(),
                "No data matches the selected criteria.");
    }
}
