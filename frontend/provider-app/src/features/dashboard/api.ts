import { apiClient } from '@api/client';

/**
 * Provider Service dashboard bindings (Requirement 14, 4).
 *
 * Endpoints (see design.md — Provider Service, PROVIDER_PROFILE +
 * PROVIDER_EARNINGS + BOOKING data models):
 * - GET /providers/me/summary     — wallet balance + today's earnings snapshot
 * - GET /providers/me/active-jobs — currently active/assigned bookings
 *
 * Calls flow through the shared Axios client (JWT + correlation id + normalized
 * ApiError). The `me` segment resolves the authenticated Provider on the
 * server from the bearer token, so no id needs to be passed from the client.
 */

/**
 * Booking lifecycle states relevant to a Provider's active work
 * (design.md BOOKING.status). A job is "active" while it is anywhere between
 * assignment and completion.
 */
export type JobStatus =
  'ASSIGNED' | 'ACCEPTED' | 'EN_ROUTE' | 'ARRIVED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

/** Earnings summary shown at the top of the Dashboard (Requirement 14.1). */
export interface EarningsSummary {
  /** Cumulative available balance in the Provider's wallet. */
  walletBalance: number;
  /** Net earnings credited so far today. */
  todayEarnings: number;
  /** Number of jobs completed today (context for today's earnings). */
  todayJobCount: number;
  /** ISO 4217 currency code the amounts are denominated in. */
  currency: string;
}

/** A single active job card on the Dashboard (Requirement 28.8). */
export interface ActiveJob {
  bookingId: string;
  /** Short human reference, e.g. "BKG-2025-000123". */
  reference: string;
  /** Service subcategory name, e.g. "AC Repair". */
  serviceName: string;
  status: JobStatus;
  /** Whether this is an emergency booking (surfaced prominently). */
  isEmergency: boolean;
  /** Scheduled/slot start time (ISO 8601). */
  scheduledAt: string;
  /** Short customer area/locality label (no precise address on the list). */
  customerArea: string;
  /** Estimated payout for the job, when known. */
  estimatedEarning: number | null;
}

/** GET /providers/me/summary — wallet + today's earnings (Requirement 14.1). */
export async function fetchEarningsSummary(): Promise<EarningsSummary> {
  const { data } = await apiClient.get<EarningsSummary>('/providers/me/summary');
  return data;
}

/** GET /providers/me/active-jobs — current active job list (Requirement 28.8). */
export async function fetchActiveJobs(): Promise<ActiveJob[]> {
  const { data } = await apiClient.get<ActiveJob[]>('/providers/me/active-jobs');
  return data;
}
