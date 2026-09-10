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

/** Active job list with status indicators (Requirement 28.8). */
export function useActiveJobs(): UseQueryResult<ActiveJob[], ApiError> {
  return useQuery<ActiveJob[], ApiError>({
    queryKey: dashboardKeys.activeJobs,
    queryFn: fetchActiveJobs,
    // Active jobs move quickly; refresh a little more eagerly than the default.
    refetchInterval: 60_000,
  });
}
