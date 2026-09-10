package com.homefix.reporting.domain;

/**
 * The pre-built reports offered by the Reporting Service (Requirement 20.1).
 *
 * <p>The three revenue-summary granularities share a single query shape but differ in their
 * bucketing period, so they are modelled as distinct types. {@link #restricted} marks the report
 * types that only a Finance_Admin may run (Requirement 20.6): Payment Reconciliation and
 * Settlement.
 */
public enum ReportType {

    DAILY_REVENUE_SUMMARY("Daily Revenue Summary", false),
    WEEKLY_REVENUE_SUMMARY("Weekly Revenue Summary", false),
    MONTHLY_REVENUE_SUMMARY("Monthly Revenue Summary", false),
    PROVIDER_PERFORMANCE("Provider Performance Report", false),
    SERVICE_CATEGORY_DEMAND("Service Category Demand Report", false),
    CUSTOMER_RETENTION("Customer Retention Report", false),
    COMPLAINT_RESOLUTION("Complaint Resolution Report", false),
    PAYMENT_RECONCILIATION("Payment Reconciliation Report", true),
    SETTLEMENT("Settlement Report", true);

    private final String displayName;
    private final boolean restricted;

    ReportType(String displayName, boolean restricted) {
        this.displayName = displayName;
        this.restricted = restricted;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * {@code true} when this report is restricted to the Finance_Admin role (Requirement 20.6):
     * Payment Reconciliation and Settlement.
     */
    public boolean isFinanceRestricted() {
        return restricted;
    }
}
