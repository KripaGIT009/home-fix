package com.homefix.reporting.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.List;

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
import com.homefix.reporting.domain.ReportRow;
import com.homefix.reporting.domain.ReportType;
import com.homefix.reporting.export.CsvReportExporter;
import com.homefix.reporting.export.PdfReportExporter;
import com.homefix.reporting.export.ReportExportService;
import com.homefix.reporting.rbac.ReportAuthorization;
import com.homefix.reporting.report.ReportGenerator;
import com.homefix.reporting.report.ReportingService;
import com.homefix.reporting.support.FakeAnalyticsDataStore;
import com.homefix.reporting.support.RecordingReportEmailAdapter;

/**
 * Web-layer tests for the Admin Portal report adapter {@link AdminReportController}
 * (Requirements 19.2, 20): the portal's {@code ReportType}/{@code ReportResult} shapes, the
 * role-filtered type list, and that the Finance_Admin restriction (Requirement 20.6) and the 7-day
 * SYNC/QUEUED split (Requirements 20.2, 20.3) of the underlying {@link ReportController} carry
 * through unchanged.
 */
class AdminReportControllerTest {

    private FakeAnalyticsDataStore store;
    private MockMvc mockMvc;

    private static Authentication auth(String... roles) {
        var authorities = List.of(roles).stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        return new UsernamePasswordAuthenticationToken("44444444-4444-4444-4444-444444444444",
                null, authorities);
    }

    @BeforeEach
    void setUp() {
        store = new FakeAnalyticsDataStore();
        var properties = new ReportingProperties();
        var linkStore = new StubDownloadLinkStoreAdapter(properties, Clock.systemUTC());
        var email = new RecordingReportEmailAdapter();
        var exportService = new ReportExportService(
                List.of(new CsvReportExporter(), new PdfReportExporter()));
        var reportingService = new ReportingService(
                new ReportGenerator(store), exportService, linkStore, email, properties, Runnable::run);
        var authorization = new ReportAuthorization();
        var reports = new ReportController(reportingService, exportService, authorization);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminReportController(reports, authorization))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String body(String reportTypeId, String from, String to, String format) {
        return "{\"reportTypeId\":\"" + reportTypeId + "\",\"fromDate\":\"" + from
                + "\",\"toDate\":\"" + to + "\",\"format\":\"" + format + "\"}";
    }

    // ------------------------------------------------------------------ GET /admin/reports/types

    @Test
    void typesForAnAdminOmitTheFinanceOnlyReports() throws Exception {
        mockMvc.perform(get("/admin/reports/types").principal(auth("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(ReportType.values().length - 2)))
                .andExpect(jsonPath("$[0].id").value("DAILY_REVENUE_SUMMARY"))
                .andExpect(jsonPath("$[0].name").value("Daily Revenue Summary"))
                .andExpect(jsonPath("$[0].financeOnly").value(false))
                .andExpect(jsonPath("$[*].id", not(hasItem("PAYMENT_RECONCILIATION"))))
                .andExpect(jsonPath("$[*].id", not(hasItem("SETTLEMENT"))));
    }

    @Test
    void typesForAFinanceAdminIncludeAndFlagTheFinanceOnlyReports() throws Exception {
        mockMvc.perform(get("/admin/reports/types").principal(auth("FINANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(ReportType.values().length)))
                .andExpect(jsonPath("$[?(@.id == 'SETTLEMENT')].financeOnly").value(true))
                .andExpect(jsonPath("$[?(@.id == 'PAYMENT_RECONCILIATION')].name")
                        .value("Payment Reconciliation Report"));
    }

    // ------------------------------------------------------------------ POST /admin/reports

    @Test
    void shortRangeIsSyncWithARowCountMessage() throws Exception {
        store.withRows(ReportRow.of("2026-01-01", "12", "1200.00", "180.00", "1020.00"));

        mockMvc.perform(post("/admin/reports").principal(auth("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("DAILY_REVENUE_SUMMARY", "2026-01-01", "2026-01-07", "CSV")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SYNC"))
                .andExpect(jsonPath("$.message").value("Report ready: 1 row."))
                .andExpect(jsonPath("$.downloadUrl").doesNotExist());
    }

    @Test
    void emptySyncReportCarriesTheNoDataMessage() throws Exception {
        mockMvc.perform(post("/admin/reports").principal(auth("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("DAILY_REVENUE_SUMMARY", "2026-01-01", "2026-01-03", "PDF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SYNC"))
                .andExpect(jsonPath("$.message").value(containsString("No data matches")));
    }

    @Test
    void longRangeIsQueued() throws Exception {
        mockMvc.perform(post("/admin/reports").principal(auth("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("DAILY_REVENUE_SUMMARY", "2026-01-01", "2026-01-31", "CSV")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mode").value("QUEUED"))
                .andExpect(jsonPath("$.message").value(containsString("emailed")))
                .andExpect(jsonPath("$.downloadUrl").doesNotExist());
    }

    @Test
    void financeOnlyReportIsForbiddenForAnAdmin() throws Exception {
        mockMvc.perform(post("/admin/reports").principal(auth("ADMIN", "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SETTLEMENT", "2026-01-01", "2026-01-03", "CSV")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("REPORT_ACCESS_DENIED"));
    }

    @Test
    void financeOnlyReportIsAllowedForAFinanceAdmin() throws Exception {
        mockMvc.perform(post("/admin/reports").principal(auth("FINANCE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SETTLEMENT", "2026-01-01", "2026-01-03", "CSV")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SYNC"));
    }

    @Test
    void unknownReportTypeIsABadRequest() throws Exception {
        mockMvc.perform(post("/admin/reports").principal(auth("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("NOT_A_REPORT", "2026-01-01", "2026-01-03", "CSV")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REPORT_REQUEST"));
    }

    @Test
    void invertedRangeIsABadRequest() throws Exception {
        mockMvc.perform(post("/admin/reports").principal(auth("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("DAILY_REVENUE_SUMMARY", "2026-01-05", "2026-01-01", "CSV")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REPORT_REQUEST"));
    }

    @Test
    void missingFieldsAreAValidationError() throws Exception {
        mockMvc.perform(post("/admin/reports").principal(auth("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportTypeId\":\"DAILY_REVENUE_SUMMARY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------------ POST /admin/reports/export

    @Test
    void exportReturnsTheFileAsAnAttachment() throws Exception {
        store.withRows(ReportRow.of("2026-01-01", "12", "1200.00", "180.00", "1020.00"));

        mockMvc.perform(post("/admin/reports/export").principal(auth("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("DAILY_REVENUE_SUMMARY", "2026-01-01", "2026-01-02", "CSV")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("daily_revenue_summary.csv")));
    }

    @Test
    void exportOfALongRangeIsAccepted() throws Exception {
        mockMvc.perform(post("/admin/reports/export").principal(auth("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("DAILY_REVENUE_SUMMARY", "2026-01-01", "2026-01-31", "PDF")))
                .andExpect(status().isAccepted());
    }
}
