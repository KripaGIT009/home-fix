import { useQuery } from '@tanstack/react-query';
import type { UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { fetchDashboardMetrics, type DashboardMetrics } from './api';

/** How often the dashboard metrics refresh, in milliseconds (Req 19.1). */
export const DASHBOARD_REFRESH_MS = 60_000;

/** Query key for the dashboard metrics resource. */
export const dashboardKeys = {
  metrics: ['admin', 'dashboard', 'metrics'] as const,
};

/**
 * Live dashboard metrics polled every 60 seconds (Requirement 19.1).
 *
 * `refetchInterval` drives the 60 s cadence and `refetchIntervalInBackground`
 * keeps it refreshing even when the tab is not focused, so an admin monitoring
 * the dashboard on a wall display always sees fresh numbers.
 */
export function useDashboardMetrics(): UseQueryResult<DashboardMetrics, ApiError> {
  return useQuery<DashboardMetrics, ApiError>({
    queryKey: dashboardKeys.metrics,
    queryFn: fetchDashboardMetrics,
    refetchInterval: DASHBOARD_REFRESH_MS,
    refetchIntervalInBackground: true,
  });
}
