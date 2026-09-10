package com.homefix.reporting.rbac;

import com.homefix.reporting.domain.ReportType;

/**
 * Raised when a principal without the Finance_Admin role requests a Finance-restricted report
 * (Payment Reconciliation or Settlement). Surfaced by the REST layer as HTTP 403 Forbidden
 * (Requirement 20.6).
 */
public class ReportAccessDeniedException extends RuntimeException {

    private final ReportType reportType;

    public ReportAccessDeniedException(ReportType reportType) {
        super("Report type " + reportType.name() + " is restricted to the Finance_Admin role");
        this.reportType = reportType;
    }

    public ReportType getReportType() {
        return reportType;
    }
}
