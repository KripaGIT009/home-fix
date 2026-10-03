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

/**
 * Whether the provider's agency has assigned the job and the provider has yet
 * to accept or decline it (Requirement MT-6). Until they accept, the job is not
 * theirs to work: the screens offer only Accept and Decline.
 */
export function isAwaitingProviderAnswer(status: BookingStatus): boolean {
  return status === 'PROVIDER_ASSIGNED';
}

/** Whether the job is before the on-site "arrived" milestone. */
export function isBeforeArrival(status: BookingStatus): boolean {
  return (
    status === 'PROVIDER_ASSIGNED' ||
    status === 'PROVIDER_ACCEPTED' ||
    status === 'PROVIDER_ON_THE_WAY'
  );
}

/**
 * Whether the job is done and the customer has yet to pay. Paying moves the
 * booking JOB_COMPLETED -> CUSTOMER_CONFIRMED -> PAYMENT_PENDING ->
 * PAYMENT_COMPLETED on the customer's side (Requirement 12), so the provider
 * can only wait.
 */
export function isAwaitingPayment(status: BookingStatus): boolean {
  return (
    status === 'JOB_COMPLETED' || status === 'CUSTOMER_CONFIRMED' || status === 'PAYMENT_PENDING'
  );
}

/** Whether the job is finished, paid or not — the completion summary applies. */
export function isJobFinished(status: BookingStatus): boolean {
  return isAwaitingPayment(status) || status === 'PAYMENT_COMPLETED';
}

/**
 * Whether the job is waiting on something the customer does (a quote decision
 * or the payment), so screens showing it re-read it until it moves on.
 */
export function isWaitingOnCustomer(status: BookingStatus): boolean {
  return status === 'CUSTOMER_APPROVAL_PENDING' || isAwaitingPayment(status);
}
