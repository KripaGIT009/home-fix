package com.homefix.reporting.export;

import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import com.homefix.reporting.domain.ExportFormat;
import com.homefix.reporting.domain.Report;
import com.homefix.reporting.domain.ReportRow;

/**
 * CSV {@link ReportExporter} (Requirement 20.4). Emits the header row followed by one line per
 * data row, with RFC-4180 quoting for cells containing commas, quotes, or newlines. An empty
 * report is rendered as the header row plus the "no data" message as a comment line.
 */
@Component
public class CsvReportExporter implements ReportExporter {

    @Override
    public ExportFormat format() {
        return ExportFormat.CSV;
    }

    @Override
    public String contentType() {
        return "text/csv";
    }

    @Override
    public byte[] export(Report report) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, report.columns());
        if (report.isEmpty()) {
            sb.append("# ").append(report.message() == null ? "" : report.message()).append('\n');
        } else {
            for (ReportRow row : report.rows()) {
                appendRow(sb, row.cells());
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendRow(StringBuilder sb, java.util.List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(cells.get(i)));
        }
        sb.append('\n');
    }

    private String quote(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
