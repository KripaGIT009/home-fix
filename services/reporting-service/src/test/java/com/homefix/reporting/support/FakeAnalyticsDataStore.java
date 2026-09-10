package com.homefix.reporting.support;

import java.util.ArrayList;
import java.util.List;

import com.homefix.reporting.analytics.AnalyticsDataStorePort;
import com.homefix.reporting.domain.ReportFilters;
import com.homefix.reporting.domain.ReportRow;
import com.homefix.reporting.domain.ReportType;

/**
 * Test double for {@link AnalyticsDataStorePort} that returns a preconfigured set of rows,
 * defaulting to empty so the "no data" path is easy to exercise (Requirement 20.2).
 */
public class FakeAnalyticsDataStore implements AnalyticsDataStorePort {

    private final List<ReportRow> rows = new ArrayList<>();

    public FakeAnalyticsDataStore withRows(ReportRow... newRows) {
        rows.clear();
        rows.addAll(List.of(newRows));
        return this;
    }

    @Override
    public List<ReportRow> query(ReportType type, ReportFilters filters) {
        return List.copyOf(rows);
    }
}
