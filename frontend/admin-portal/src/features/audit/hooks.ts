import { useInfiniteQuery } from '@tanstack/react-query';
import type { InfiniteData, UseInfiniteQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { fetchAuditLogs, type AuditLogFilters, type AuditLogPage } from './api';

export const auditKeys = {
  list: (filters: AuditLogFilters) => ['admin', 'audit-logs', filters] as const,
};

/**
 * Audit log entries filtered by action and entity type, paged by cursor
 * (Req 19.8). Pages accumulate, so "load more" appends rather than replacing
 * what is on screen. The cursor is not part of the key, only the filters: a new
 * filter is a new query that starts again from the first page.
 */
export function useAuditLogs(
  filters: AuditLogFilters,
): UseInfiniteQueryResult<InfiniteData<AuditLogPage, string | undefined>, ApiError> {
  return useInfiniteQuery<
    AuditLogPage,
    ApiError,
    InfiniteData<AuditLogPage, string | undefined>,
    ReturnType<typeof auditKeys.list>,
    string | undefined
  >({
    queryKey: auditKeys.list(filters),
    queryFn: ({ pageParam }) =>
      fetchAuditLogs({ ...filters, ...(pageParam ? { cursor: pageParam } : {}) }),
    initialPageParam: undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
  });
}
