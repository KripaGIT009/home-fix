package com.homefix.reporting.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.homefix.reporting.domain.ExportFormat;
import com.homefix.reporting.domain.Report;
import com.homefix.reporting.domain.ReportRow;
import com.homefix.reporting.domain.ReportType;

/**
 * Unit tests for CSV and PDF export (Requirement 20.4), covering populated and empty reports and
 * the content types used for download.
 */
class ReportExportServiceTest {

    private final ReportExportService service =
            new ReportExportService(List.of(new CsvReportExporter(), new PdfReportExporter()));

    private static Report populated() {
        return Report.of(ReportType.SERVICE_CATEGORY_DEMAND,
                List.of("Service Category", "Bookings", "Completed", "Cancelled", "Avg Ticket"),
                List.of(ReportRow.of("Plumbing", "40", "38", "2", "150.00")));
    }

    @Test
    void csvExportContainsHeaderAndRow() {
        String csv = new String(service.export(populated(), ExportFormat.CSV), StandardCharsets.UTF_8);

        assertThat(csv).contains("Service Category,Bookings,Completed,Cancelled,Avg Ticket");
        assertThat(csv).contains("Plumbing,40,38,2,150.00");
        assertThat(service.contentType(ExportFormat.CSV)).isEqualTo("text/csv");
    }

    @Test
    void csvExportOfEmptyReportContainsNoDataComment() {
        Report empty = Report.empty(ReportType.SERVICE_CATEGORY_DEMAND,
                List.of("Service Category", "Bookings"));

        String csv = new String(service.export(empty, ExportFormat.CSV), StandardCharsets.UTF_8);

        assertThat(csv).contains("Service Category,Bookings");
        assertThat(csv).contains("No data matches");
    }

    @Test
    void csvQuotesCellsContainingCommas() {
        Report report = Report.of(ReportType.PROVIDER_PERFORMANCE,
                List.of("Provider ID", "Note"),
                List.of(ReportRow.of("prov-1", "fast, reliable")));

        String csv = new String(service.export(report, ExportFormat.CSV), StandardCharsets.UTF_8);

        assertThat(csv).contains("\"fast, reliable\"");
    }

    @Test
    void pdfExportProducesValidPdfHeaderAndContentType() {
        byte[] pdf = service.export(populated(), ExportFormat.PDF);
        String head = new String(pdf, 0, Math.min(8, pdf.length), StandardCharsets.UTF_8);

        assertThat(head).startsWith("%PDF-");
        assertThat(service.contentType(ExportFormat.PDF)).isEqualTo("application/pdf");
    }

    @Test
    void unsupportedFormatIsRejected() {
        // A service constructed without any exporters cannot serve the requested format.
        ReportExportService bare = new ReportExportService(List.of());
        assertThatThrownBy(() -> bare.export(populated(), ExportFormat.CSV))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
