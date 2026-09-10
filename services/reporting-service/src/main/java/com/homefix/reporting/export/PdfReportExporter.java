package com.homefix.reporting.export;

import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import com.homefix.reporting.domain.ExportFormat;
import com.homefix.reporting.domain.Report;
import com.homefix.reporting.domain.ReportRow;

/**
 * PDF {@link ReportExporter} (Requirement 20.4).
 *
 * <p>This service ships a minimal, dependency-free PDF writer that emits a single-page document
 * listing the report title, column headers, and rows as text. It is deliberately simple: the
 * platform's rich PDF rendering (fonts, pagination, branding) is provided by a shared rendering
 * library wired in a later task. The output is a valid PDF byte stream so downstream storage and
 * download flows (Requirement 20.3) can be exercised end-to-end.
 */
@Component
public class PdfReportExporter implements ReportExporter {

    @Override
    public ExportFormat format() {
        return ExportFormat.PDF;
    }

    @Override
    public String contentType() {
        return "application/pdf";
    }

    @Override
    public byte[] export(Report report) {
        String content = buildText(report);
        return buildMinimalPdf(content);
    }

    private String buildText(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append(report.type().displayName()).append('\n');
        sb.append(String.join(" | ", report.columns())).append('\n');
        if (report.isEmpty()) {
            sb.append(report.message() == null ? "" : report.message());
        } else {
            for (ReportRow row : report.rows()) {
                sb.append(String.join(" | ", row.cells())).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Emits a minimal single-page PDF whose content stream draws the supplied text. Parentheses
     * and backslashes are escaped per the PDF string syntax so the document stays well-formed.
     */
    private byte[] buildMinimalPdf(String text) {
        String escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        StringBuilder body = new StringBuilder();
        body.append("BT /F1 12 Tf 40 780 Td 14 TL (");
        // Split into separate text-showing lines so newlines render.
        String[] lines = escaped.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                body.append(") Tj T* (");
            }
            body.append(lines[i]);
        }
        body.append(") Tj ET");

        String stream = body.toString();
        StringBuilder pdf = new StringBuilder();
        pdf.append("%PDF-1.4\n");
        pdf.append("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n");
        pdf.append("2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n");
        pdf.append("3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                + "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >> endobj\n");
        pdf.append("4 0 obj << /Length ").append(stream.length()).append(" >> stream\n");
        pdf.append(stream).append("\nendstream endobj\n");
        pdf.append("5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n");
        pdf.append("trailer << /Root 1 0 R >>\n");
        pdf.append("%%EOF");
        return pdf.toString().getBytes(StandardCharsets.UTF_8);
    }
}
