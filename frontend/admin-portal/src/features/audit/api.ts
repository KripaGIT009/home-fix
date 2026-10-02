import { apiClient } from '@api/client';

/**
 * Audit Logs bindings (Requirement 19.2, Requirement 19.8).
 *
 * Every admin action (create, update, delete, approve, reject) is recorded with
 * the actor, action type, affected entity, timestamp, and a change payload. The
 * store is append-only (Requirement 26.10); this module is read-only.
 *
 * Endpoints (see design.md — Admin Service):
 * - GET /admin/audit-logs — paginated, filterable audit log entries
 */

export type AuditAction = 'CREATE' | 'UPDATE' | 'DELETE' | 'APPROVE' | 'REJECT';

export interface AuditLogEntry {
  id: string;
  actorId: string;
  /** The Admin Service holds only the actor's id; the screen falls back to it. */
  actorName?: string | null;
  action: AuditAction;
  entityType: string;
  entityId: string;
  /** Free-form change summary; for updates, before/after of modified fields. */
  changeSummary: string;
  timestamp: string;
}

export interface AuditLogPage {
  entries: AuditLogEntry[];
  /** Opaque keyset cursor for the next page; null/absent on the last page. */
  nextCursor?: string | null;
}

/** The filters a user picks; changing them starts again from the first page. */
export interface AuditLogFilters {
  action?: AuditAction | '';
  entityType?: string;
}

export interface AuditLogQuery extends AuditLogFilters {
  cursor?: string;
}

/** GET /admin/audit-logs — a page of audit log entries (Requirement 19.8). */
export async function fetchAuditLogs(query: AuditLogQuery): Promise<AuditLogPage> {
  const params: Record<string, string> = {};
  if (query.action) params.action = query.action;
  if (query.entityType) params.entityType = query.entityType;
  if (query.cursor) params.cursor = query.cursor;
  const { data } = await apiClient.get<AuditLogPage>('/admin/audit-logs', {
    params: Object.keys(params).length > 0 ? params : undefined,
  });
  return data;
}
