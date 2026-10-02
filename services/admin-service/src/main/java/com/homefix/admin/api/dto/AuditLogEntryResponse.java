package com.homefix.admin.api.dto;

import com.homefix.admin.audit.AuditLogQueryService;

/**
 * One Audit_Log entry as the Admin Portal's Audit Logs view reads it ({@code AuditLogEntry} in
 * {@code features/audit/api.ts}).
 *
 * @param actorName     always {@code null}: the log stores the actor's user id only, and names live
 *                      in the Auth Service
 * @param changeSummary one-line summary derived from the stored before/after values
 * @param timestamp     ISO-8601 instant the action was logged
 */
public record AuditLogEntryResponse(String id,
                                    String actorId,
                                    String actorName,
                                    String action,
                                    String entityType,
                                    String entityId,
                                    String changeSummary,
                                    String timestamp) {

    public static AuditLogEntryResponse from(AuditLogQueryService.Item item) {
        var entry = item.entry();
        return new AuditLogEntryResponse(
                entry.getId().toString(),
                entry.getActorId().toString(),
                null,
                entry.getActionType().name(),
                entry.getEntityType(),
                entry.getEntityId(),
                item.changeSummary(),
                entry.getLoggedAt().toString());
    }
}
