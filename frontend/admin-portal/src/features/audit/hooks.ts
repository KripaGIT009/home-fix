import { useQuery } from '@tanstack/react-query';
import type { UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { fetchAuditLogs, type AuditLogPage, type AuditLogQuery } from './api';

export const auditKeys = {
  page: (query: AuditLogQuery) => ['admin', 'audit-logs', query] as const,
};

/** A page of audit log entries filtered by action and entity type (Req 19.8). */
export function useAuditLogs(query: AuditLogQuery): UseQueryResult<AuditLogPage, ApiError> {
  return useQuery<AuditLogPage, ApiError>({
    queryKey: auditKeys.page(query),
    queryFn: () => fetchAuditLogs(query),
  });
}
