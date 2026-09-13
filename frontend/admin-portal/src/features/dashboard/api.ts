import { apiClient } from '@api/client';

/**
 * Admin Service dashboard bindings (Requirement 19.1).
 *
 * Endpoint (see design.md — Admin Service):
 * - GET /admin/dashboard — the 7 live platform metrics
 *
 * Calls flow through the shared Axios client (JWT + correlation id + normalized
 * ApiError). The metrics are refreshed by the client every 60 seconds
 * (Requirement 19.1); see the hooks module for the polling configuration.
 */

/**
 * The seven dashboard metrics enumerated in Requirement 19.1:
 * total active Bookings, total active Providers online, new Customer
 * registrations in the last 24h, gross revenue in the last 24h, average
 * provider response time over the last 24h, open complaint count, and overall
 * platform rating (mean Review rating, 1.0–5.0).
 */
export interface DashboardMetrics {
  activeBookings: number;
  activeProvidersOnline: number;
  newRegistrationsLast24h: number;
  grossRevenueLast24h: number;
  /** Average provider response time over the last 24h, in seconds. */
  avgProviderResponseTimeSeconds: number;
  openComplaintCount: number;
  /** Mean of all Review ratings on a 1.0–5.0 scale. */
  platformRating: number;
  /**
   * ISO 4217 currency for grossRevenueLast24h. Optional: the Admin Service does not
   * send it (see API_CONTRACTS.md), so the screen falls back to INR.
   */
  currency?: string;
}

/** GET /admin/dashboard — the 7 live dashboard metrics (Req 19.1). */
export async function fetchDashboardMetrics(): Promise<DashboardMetrics> {
  const { data } = await apiClient.get<DashboardMetrics>('/admin/dashboard');
  return data;
}
