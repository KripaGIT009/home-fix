package com.homefix.reporting.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.homefix.reporting.domain.Report;
import com.homefix.reporting.domain.ReportFilters;
import com.homefix.reporting.domain.ReportRow;
import com.homefix.reporting.domain.ReportType;
import com.homefix.reporting.support.FakeAnalyticsDataStore;

/**
 * Unit tests for {@link ReportGenerator}: the empty-result "no data" message (Requirement 20.2)
 * and the populated-report path with catalog columns (Requirement 20.1).
 */
class ReportGeneratorTest {

    private static final ReportFilters FILTERS = new ReportFilters(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5), null, null, null);

    @Test
    void emptyResultReturnsNoDataMessageAndColumns() {
        var store = new FakeAnalyticsDataStore(); // no rows
        var generator = new ReportGenerator(store);

        Report report = generator.generate(ReportType.DAILY_REVENUE_SUMMARY, FILTERS);

        assertThat(report.isEmpty()).isTrue();
        assertThat(report.message()).contains("No data matches");
        assertThat(report.columns())
                .isEqualTo(ReportCatalog.columnsFor(ReportType.DAILY_REVENUE_SUMMARY));
    }

    @Test
    void populatedResultCarriesRowsAndNoMessage() {
        var store = new FakeAnalyticsDataStore()
                .withRows(ReportRow.of("2026-01-01", "12", "1200.00", "180.00", "1020.00"));
        var generator = new ReportGenerator(store);

        Report report = generator.generate(ReportType.DAILY_REVENUE_SUMMARY, FILTERS);

        assertThat(report.isEmpty()).isFalse();
        assertThat(report.rows()).hasSize(1);
        assertThat(report.message()).isNull();
    }
}
