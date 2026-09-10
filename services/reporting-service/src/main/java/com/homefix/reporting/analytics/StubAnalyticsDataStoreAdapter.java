package com.homefix.reporting.analytics;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.homefix.reporting.domain.ReportFilters;
import com.homefix.reporting.domain.ReportRow;
import com.homefix.reporting.domain.ReportType;

/**
 * Default {@link AnalyticsDataStorePort} adapter used in local/dev/test profiles.
 *
 * <p>It does not connect to Redshift/Aurora — it returns no rows, exercising the empty-report path
 * (Requirement 20.2) while keeping the service independently buildable. The concrete columnar-store
 * adapter is wired in a later integration task. Filter values are never logged (Requirement 26.4);
 * only the report type is recorded.
 *
 * <p>The only adapter for this port; a real analytics store replaces it by
 * supplying its own bean definition.
 */
@Component
public class StubAnalyticsDataStoreAdapter implements AnalyticsDataStorePort {

    private static final Logger log = LoggerFactory.getLogger(StubAnalyticsDataStoreAdapter.class);

    @Override
    public List<ReportRow> query(ReportType type, ReportFilters filters) {
        // Do not log filter values (may include region/provider dimensions).
        log.info("Analytics query served by stub adapter for report type {}", type);
        return List.of();
    }
}
