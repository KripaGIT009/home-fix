package com.homefix.reporting.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.reporting.config.ReportingProperties;
import com.homefix.reporting.delivery.DownloadLinkStorePort;
import com.homefix.reporting.delivery.StubDownloadLinkStoreAdapter;
import com.homefix.reporting.domain.ExportFormat;
import com.homefix.reporting.domain.GenerationMode;
import com.homefix.reporting.domain.ReportFilters;
import com.homefix.reporting.domain.ReportType;
import com.homefix.reporting.export.CsvReportExporter;
import com.homefix.reporting.export.PdfReportExporter;
import com.homefix.reporting.export.ReportExportService;
import com.homefix.reporting.support.FakeAnalyticsDataStore;
import com.homefix.reporting.support.RecordingReportEmailAdapter;

/**
 * Unit tests for {@link ReportingService}: synchronous-vs-asynchronous branching at the 7-day
 * threshold (Requirement 20.2, 20.3) and the async delivery flow (generate → export → store →
 * email with a 7-day expiring link).
 */
class ReportingServiceTest {

    /** Runs submitted async work inline so the test can assert deterministically. */
    private static final Executor INLINE = Runnable::run;

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    private FakeAnalyticsDataStore store;
    private RecordingReportEmailAdapter email;
    private DownloadLinkStorePort linkStore;
    private ReportingProperties properties;
    private ReportingService service;

    @BeforeEach
    void setUp() {
        store = new FakeAnalyticsDataStore();
        email = new RecordingReportEmailAdapter();
        properties = new ReportingProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC);
        linkStore = new StubDownloadLinkStoreAdapter(properties, clock);
        ReportGenerator generator = new ReportGenerator(store);
        ReportExportService exportService =
                new ReportExportService(java.util.List.of(new CsvReportExporter(), new PdfReportExporter()));
        service = new ReportingService(generator, exportService, linkStore, email, properties, INLINE);
    }

    private static ReportRequest request(int inclusiveDays, ReportType type) {
        ReportFilters filters = new ReportFilters(
                START, START.plusDays(inclusiveDays - 1L), null, null, null);
        return new ReportRequest(type, filters, ExportFormat.CSV, "requestor-subject");
    }

    @Test
    void sevenDayRangeReturnsSynchronousReport() {
        ReportResult result = service.submit(request(7, ReportType.WEEKLY_REVENUE_SUMMARY));

        assertThat(result.mode()).isEqualTo(GenerationMode.SYNCHRONOUS);
        assertThat(result.accepted()).isFalse();
        assertThat(result.report()).isNotNull();
        assertThat(email.sent()).isEmpty();
    }

    @Test
    void eightDayRangeTriggersAsyncPathAndEmailsExpiringLink() {
        ReportResult result = service.submit(request(8, ReportType.MONTHLY_REVENUE_SUMMARY));

        assertThat(result.mode()).isEqualTo(GenerationMode.ASYNCHRONOUS);
        assertThat(result.accepted()).isTrue();
        assertThat(result.report()).isNull();

        // Async delivery (run inline) emailed the requestor a link expiring 7 days out.
        assertThat(email.sent()).hasSize(1);
        var sent = email.sent().get(0);
        assertThat(sent.recipient()).isEqualTo("requestor-subject");
        assertThat(sent.link().expiresAt())
                .isEqualTo(sent.link().issuedAt().plus(properties.getDownloadLinkTtl()));
        assertThat(sent.link().isExpiredAt(sent.link().expiresAt())).isTrue();
        assertThat(sent.link().isExpiredAt(sent.link().issuedAt())).isFalse();
    }

    @Test
    void synchronousEmptyResultCarriesNoDataMessage() {
        ReportResult result = service.submit(request(3, ReportType.SERVICE_CATEGORY_DEMAND));

        assertThat(result.mode()).isEqualTo(GenerationMode.SYNCHRONOUS);
        assertThat(result.report().isEmpty()).isTrue();
        assertThat(result.report().message()).contains("No data matches");
    }
}
