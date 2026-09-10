package com.homefix.reporting.report;

import java.util.List;

import com.homefix.reporting.domain.ReportType;

/**
 * Static definition of the column headers for each pre-built report (Requirement 20.1). Columns
 * describe aggregated, non-PII dimensions only (Requirement 26.4).
 */
public final class ReportCatalog {

    private ReportCatalog() {
    }

    /**
     * Returns the ordered column headers for the given report type. Callers align each
     * {@code ReportRow}'s cells positionally with this list.
     */
    public static List<String> columnsFor(ReportType type) {
        return switch (type) {
            case DAILY_REVENUE_SUMMARY, WEEKLY_REVENUE_SUMMARY, MONTHLY_REVENUE_SUMMARY ->
                    List.of("Period", "Bookings", "Gross Revenue", "Platform Fee", "Net Revenue");
            case PROVIDER_PERFORMANCE ->
                    List.of("Provider ID", "Jobs Completed", "Avg Rating", "On-Time %", "Cancellations");
            case SERVICE_CATEGORY_DEMAND ->
                    List.of("Service Category", "Bookings", "Completed", "Cancelled", "Avg Ticket");
            case CUSTOMER_RETENTION ->
                    List.of("Cohort", "New Customers", "Returning Customers", "Retention %");
            case COMPLAINT_RESOLUTION ->
                    List.of("Category", "Opened", "Resolved", "Avg Resolution Hours", "SLA Breaches");
            case PAYMENT_RECONCILIATION ->
                    List.of("Date", "Captured", "Refunded", "Net Settled", "Discrepancies");
            case SETTLEMENT ->
                    List.of("Provider ID", "Settlement ID", "Amount", "Status", "Settled At");
        };
    }
}
