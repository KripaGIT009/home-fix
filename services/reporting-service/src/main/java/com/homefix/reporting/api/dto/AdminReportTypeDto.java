package com.homefix.reporting.api.dto;

import com.homefix.reporting.domain.ReportType;

/**
 * One entry of the Admin Portal's report picker (Requirement 20.1), in the shape of the portal's
 * {@code ReportType} ({@code frontend/admin-portal/src/features/reports/api.ts}).
 *
 * @param id          the {@link ReportType} constant, sent back as {@code reportTypeId}
 * @param name        the human-readable report name
 * @param financeOnly {@code true} for the Finance_Admin-only reports (Requirement 20.6)
 */
public record AdminReportTypeDto(String id, String name, boolean financeOnly) {

    public static AdminReportTypeDto from(ReportType type) {
        return new AdminReportTypeDto(type.name(), type.displayName(), type.isFinanceRestricted());
    }
}
