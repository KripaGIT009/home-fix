package com.homefix.reporting.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.homefix.reporting.config.ReportingProperties;
import com.homefix.reporting.delivery.StubDownloadLinkStoreAdapter;
import com.homefix.reporting.export.CsvReportExporter;
import com.homefix.reporting.export.PdfReportExporter;
import com.homefix.reporting.export.ReportExportService;
import com.homefix.reporting.rbac.ReportAuthorization;
import com.homefix.reporting.report.ReportGenerator;
import com.homefix.reporting.report.ReportingService;
import com.homefix.reporting.support.FakeAnalyticsDataStore;
import com.homefix.reporting.support.RecordingReportEmailAdapter;

/**
 * Web-layer test proving a non-Finance_Admin receives 403 on a restricted report while a
 * Finance_Admin succeeds (Requirement 20.6), and that a &gt; 7-day range is accepted for async
 * processing (Requirement 20.3). Uses standalone MockMvc with the shared error envelope advice.
 */
class ReportControllerTest {

    private MockMvc mockMvc;

    private static Authentication auth(String... roles) {
        var authorities = java.util.List.of(roles).stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        return new UsernamePasswordAuthenticationToken("44444444-4444-4444-4444-444444444444",
                null, authorities);
    }

    @BeforeEach
    void setUp() {
        var store = new FakeAnalyticsDataStore();
        var properties = new ReportingProperties();
        var linkStore = new StubDownloadLinkStoreAdapter(properties, Clock.systemUTC());
        var email = new RecordingReportEmailAdapter();
        var exportService = new ReportExportService(
                java.util.List.of(new CsvReportExporter(), new PdfReportExporter()));
        var reportingService = new ReportingService(
                new ReportGenerator(store), exportService, linkStore, email, properties, Runnable::run);
        var controller = new ReportController(reportingService, exportService, new ReportAuthorization());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String body(String reportType, String from, String to, String format) {
        return "{\"reportType\":\"" + reportType + "\",\"from\":\"" + from + "\",\"to\":\"" + to
                + "\",\"format\":\"" + format + "\"}";
    }

    @Test
    void nonFinanceAdminReceives403OnPaymentReconciliation() throws Exception {
        mockMvc.perform(post("/reports")
                        .principal(auth("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PAYMENT_RECONCILIATION", "2026-01-01", "2026-01-03", "CSV")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("REPORT_ACCESS_DENIED"));
    }

    @Test
    void financeAdminCanRequestPaymentReconciliation() throws Exception {
        mockMvc.perform(post("/reports")
                        .principal(auth("FINANCE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PAYMENT_RECONCILIATION", "2026-01-01", "2026-01-03", "CSV")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SYNCHRONOUS"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("No data matches")));
    }

    @Test
    void adminCanRequestUnrestrictedReport() throws Exception {
        mockMvc.perform(post("/reports")
                        .principal(auth("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("DAILY_REVENUE_SUMMARY", "2026-01-01", "2026-01-05", "CSV")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SYNCHRONOUS"));
    }

    @Test
    void longRangeReportIsAcceptedForAsyncProcessing() throws Exception {
        mockMvc.perform(post("/reports")
                        .principal(auth("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("DAILY_REVENUE_SUMMARY", "2026-01-01", "2026-01-31", "CSV")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mode").value("ASYNCHRONOUS"))
                .andExpect(jsonPath("$.accepted").value(true));
    }
}
