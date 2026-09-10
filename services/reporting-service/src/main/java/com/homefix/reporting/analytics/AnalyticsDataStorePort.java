package com.homefix.reporting.analytics;

import java.util.List;

import com.homefix.reporting.domain.ReportFilters;
import com.homefix.reporting.domain.ReportRow;
import com.homefix.reporting.domain.ReportType;

/**
 * Hexagonal port over the analytics data store (Redshift / Aurora) that holds raw event data with
 * a minimum 2-year retention (Requirement 20.5).
 *
 * <p>Report generation logic depends only on this interface, never on a concrete columnar-store
 * SDK. A production adapter issues the columnar SQL for each report type; tests and local runs use
 * a stub. The port returns already-aggregated, non-PII rows (Requirement 26.4).
 */
public interface AnalyticsDataStorePort {

    /**
     * Queries the analytics store for the rows of the given report type within the supplied
     * filters. Returns an empty list when no data matches (the service maps that to an empty
     * report per Requirement 20.2).
     *
     * @param type    the report type being generated
     * @param filters date range and optional dimension filters
     * @return the aggregated data rows, possibly empty
     */
    List<ReportRow> query(ReportType type, ReportFilters filters);
}
