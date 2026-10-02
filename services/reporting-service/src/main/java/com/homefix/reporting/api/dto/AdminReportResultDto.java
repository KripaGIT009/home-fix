package com.homefix.reporting.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.homefix.reporting.domain.GenerationMode;

/**
 * Outcome of an Admin Portal report request, in the shape of the portal's {@code ReportResult}.
 *
 * <ul>
 *   <li>{@code SYNC} — the range is within the synchronous threshold (Requirement 20.2); the
 *       report was generated and {@code message} says how many rows it has, or carries the
 *       "no data" text. The file itself comes from {@code POST /admin/reports/export}.</li>
 *   <li>{@code QUEUED} — the range is longer (Requirement 20.3); the report is generated in the
 *       background and the requestor is emailed an expiring download link.</li>
 * </ul>
 *
 * <p>{@code downloadUrl} is never set: the only links this service mints are the emailed artifact
 * links of the asynchronous path, and a plain browser link to a synchronous file could not carry
 * the bearer token every {@code /admin/**} call needs. It stays in the record (omitted from the
 * JSON while null) because it is part of the portal contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminReportResultDto(String mode, String downloadUrl, String message) {

    public static final String SYNC = "SYNC";
    public static final String QUEUED = "QUEUED";

    public static AdminReportResultDto from(ReportResponseDto response) {
        if (response.mode() == GenerationMode.ASYNCHRONOUS) {
            return new AdminReportResultDto(QUEUED, null, response.message());
        }
        if (response.message() != null) {
            return new AdminReportResultDto(SYNC, null, response.message());
        }
        int rows = response.rows() == null ? 0 : response.rows().size();
        return new AdminReportResultDto(SYNC, null,
                "Report ready: " + rows + (rows == 1 ? " row." : " rows."));
    }
}
