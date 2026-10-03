import { useQuery } from '@tanstack/react-query';
import type { UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { fetchActiveJobs, fetchEarningsSummary, type ActiveJob, type EarningsSummary } from './api';

/** Query keys for the Provider dashboard resources. */
export const dashboardKeys = {
  summary: ['provider', 'summary'] as const,
  activeJobs: ['provider', 'active-jobs'] as const,
};

/** Wallet balance + today's earnings (Requirement 14.1). */
export function useEarningsSummary(): UseQueryResult<EarningsSummary, ApiError> {
  return useQuery<EarningsSummary, ApiError>({
    queryKey: dashboardKeys.summary,
    queryFn: fetchEarningsSummary,
  });
}

/**
 * How often the active job list is re-read. A job the provider's agency
 * assigns lands here, not among the dispatch offers, and its push notification
 * is best-effort; the customer is waiting on the provider's answer, so the list
 * is refreshed often enough for it to show up promptly (Requirement MT-13.2).
 */
export const ACTIVE_JOBS_POLL_MS = 15_000;

/** Active job list with status indicators (Requirement 28.8). */
export function useActiveJobs(): UseQueryResult<ActiveJob[], ApiError> {
  return useQuery<ActiveJob[], ApiError>({
    queryKey: dashboardKeys.activeJobs,
    queryFn: fetchActiveJobs,
    refetchInterval: ACTIVE_JOBS_POLL_MS,
  });
}
