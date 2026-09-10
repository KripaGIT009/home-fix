package com.homefix.reporting.domain;

import java.util.List;

/**
 * A single row of report data as an ordered list of cell values, aligned positionally with the
 * report's {@code columns}. Cells are already-aggregated, non-PII values (counts, sums,
 * category names, opaque IDs) — never customer names, emails, or phone numbers (Requirement 26.4).
 */
public record ReportRow(List<String> cells) {

    public ReportRow {
        cells = List.copyOf(cells);
    }

    public static ReportRow of(String... cells) {
        return new ReportRow(List.of(cells));
    }
}
