package com.homefix.admin.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.homefix.admin.audit.AuditLogQueryService;

/**
 * A page of the Audit Logs view ({@code AuditLogPage} in {@code features/audit/api.ts}).
 *
 * @param nextCursor opaque token for the next page; omitted on the last page
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditLogPageResponse(List<AuditLogEntryResponse> entries, String nextCursor) {

    public static AuditLogPageResponse from(AuditLogQueryService.Page page) {
        return new AuditLogPageResponse(
                page.entries().stream().map(AuditLogEntryResponse::from).toList(),
                page.nextCursor());
    }
}
