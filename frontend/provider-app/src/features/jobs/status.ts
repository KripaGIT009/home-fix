import type { BookingStatus } from '@lib/bookingStatus';

/**
 * Job-execution predicates over the shared Booking lifecycle
 * (@lib/bookingStatus, Requirement 9.1). The label/colour mapping lives with the
 * type itself so the dashboard and these screens cannot describe the same status
 * two different ways.
 */

/** Whether the job is actively running (started, not paused). */
export function isJobRunning(status: BookingStatus): boolean {
  return status === 'JOB_STARTED';
}

/** Whether the job is before the on-site "arrived" milestone. */
export function isBeforeArrival(status: BookingStatus): boolean {
  return (
    status === 'PROVIDER_ASSIGNED' ||
    status === 'PROVIDER_ACCEPTED' ||
    status === 'PROVIDER_ON_THE_WAY'
  );
}
