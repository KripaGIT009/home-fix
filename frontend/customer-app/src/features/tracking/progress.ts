import type { BookingStatus } from '@stores/bookingStore';
import type { TimelineStep, TimelineStepState } from '@components/ProgressTimeline';

/**
 * How a booking's status maps onto the customer's journey: which screen state
 * the tracking page shows, and where the progress timeline stands.
 */

/** The tracking page's top-level state for a booking status. */
export type TrackingPhase =
  | 'booked'
  | 'searching'
  | 'searchFailed'
  | 'assigned'
  | 'enRoute'
  | 'arrived'
  | 'working'
  | 'paused'
  | 'approval'
  | 'completed'
  | 'paymentDue'
  | 'paid'
  | 'cancelled'
  | 'disputed'
  | 'refunded';

const PHASE_BY_STATUS: Record<BookingStatus, TrackingPhase> = {
  CREATED: 'booked',
  SEARCHING_PROVIDER: 'searching',
  SEARCHING_FAILED: 'searchFailed',
  PROVIDER_ASSIGNED: 'assigned',
  PROVIDER_ACCEPTED: 'assigned',
  PROVIDER_ON_THE_WAY: 'enRoute',
  PROVIDER_ARRIVED: 'arrived',
  JOB_STARTED: 'working',
  JOB_PAUSED: 'paused',
  ADDITIONAL_QUOTE_REQUIRED: 'approval',
  CUSTOMER_APPROVAL_PENDING: 'approval',
  JOB_COMPLETED: 'completed',
  CUSTOMER_CONFIRMED: 'paymentDue',
  PAYMENT_PENDING: 'paymentDue',
  PAYMENT_COMPLETED: 'paid',
  DISPUTED: 'disputed',
  REFUNDED: 'refunded',
  CANCELLED: 'cancelled',
};

export function trackingPhase(status: BookingStatus): TrackingPhase {
  return PHASE_BY_STATUS[status] ?? 'booked';
}

/** Statuses during which the provider's live location is worth asking for. */
export const LOCATION_STATUSES: readonly BookingStatus[] = [
  'PROVIDER_ACCEPTED',
  'PROVIDER_ON_THE_WAY',
  'PROVIDER_ARRIVED',
];

/** Statuses during which the booking chat is meaningful. */
export const CHAT_STATUSES: readonly BookingStatus[] = [
  'PROVIDER_ASSIGNED',
  'PROVIDER_ACCEPTED',
  'PROVIDER_ON_THE_WAY',
  'PROVIDER_ARRIVED',
  'JOB_STARTED',
  'JOB_PAUSED',
  'ADDITIONAL_QUOTE_REQUIRED',
  'CUSTOMER_APPROVAL_PENDING',
  'JOB_COMPLETED',
  'CUSTOMER_CONFIRMED',
  'PAYMENT_PENDING',
];

/** The journey, in order. Each status sits on exactly one of these steps. */
const JOURNEY = [
  { key: 'booked', label: 'Booking placed' },
  { key: 'searching', label: 'Finding a professional' },
  { key: 'assigned', label: 'Professional assigned' },
  { key: 'enRoute', label: 'On the way' },
  { key: 'arrived', label: 'Arrived' },
  { key: 'working', label: 'Job in progress' },
  { key: 'completed', label: 'Job completed' },
  { key: 'paid', label: 'Payment' },
] as const;

type JourneyKey = (typeof JOURNEY)[number]['key'];

/** Where each status stands on the journey. */
const STEP_BY_STATUS: Partial<Record<BookingStatus, JourneyKey>> = {
  CREATED: 'booked',
  SEARCHING_PROVIDER: 'searching',
  SEARCHING_FAILED: 'searching',
  PROVIDER_ASSIGNED: 'assigned',
  PROVIDER_ACCEPTED: 'assigned',
  PROVIDER_ON_THE_WAY: 'enRoute',
  PROVIDER_ARRIVED: 'arrived',
  JOB_STARTED: 'working',
  JOB_PAUSED: 'working',
  ADDITIONAL_QUOTE_REQUIRED: 'working',
  CUSTOMER_APPROVAL_PENDING: 'working',
  // The job is done: paying is what is left.
  JOB_COMPLETED: 'paid',
  CUSTOMER_CONFIRMED: 'paid',
  PAYMENT_PENDING: 'paid',
  DISPUTED: 'completed',
  REFUNDED: 'paid',
};

/** Extra copy for the current step, keyed by status. */
const CURRENT_NOTE: Partial<Record<BookingStatus, string>> = {
  CREATED: 'Confirming your booking.',
  SEARCHING_PROVIDER: 'Offering your job to verified pros nearby.',
  PROVIDER_ASSIGNED: 'Waiting for them to start the trip.',
  PROVIDER_ACCEPTED: 'Waiting for them to start the trip.',
  PROVIDER_ON_THE_WAY: 'Heading to your address now.',
  PROVIDER_ARRIVED: 'Your professional is at your door.',
  JOB_STARTED: 'Work is underway.',
  JOB_PAUSED: 'Work is paused for now.',
  ADDITIONAL_QUOTE_REQUIRED: 'An updated quote needs your approval.',
  CUSTOMER_APPROVAL_PENDING: 'Waiting for your approval.',
  JOB_COMPLETED: 'Pay to finish up.',
  CUSTOMER_CONFIRMED: 'Payment is due.',
  PAYMENT_PENDING: 'Payment is due.',
};

/**
 * The progress timeline for a status. Cancelled, failed, disputed and refunded
 * bookings end the journey with a marked step instead of continuing it.
 */
export function journeySteps(status: BookingStatus): TimelineStep[] {
  if (status === 'CANCELLED') {
    return [
      { key: 'booked', label: 'Booking placed', state: 'done' },
      { key: 'cancelled', label: 'Booking cancelled', state: 'failed' },
    ];
  }
  if (status === 'PAYMENT_COMPLETED') {
    return JOURNEY.map((step) => ({ key: step.key, label: step.label, state: 'done' }));
  }

  const currentKey = STEP_BY_STATUS[status] ?? 'booked';
  const currentIndex = JOURNEY.findIndex((step) => step.key === currentKey);

  if (status === 'SEARCHING_FAILED') {
    return [
      { key: 'booked', label: 'Booking placed', state: 'done' },
      {
        key: 'searching',
        label: 'No professional available',
        description: 'Nobody nearby could take the job this time.',
        state: 'failed',
      },
    ];
  }

  const steps: TimelineStep[] = JOURNEY.map((step, index) => {
    let state: TimelineStepState = 'upcoming';
    if (index < currentIndex) state = 'done';
    else if (index === currentIndex) state = 'current';
    const note = state === 'current' ? CURRENT_NOTE[status] : undefined;
    return { key: step.key, label: step.label, state, ...(note ? { description: note } : {}) };
  });

  if (status === 'DISPUTED') {
    return [
      ...steps.slice(0, currentIndex + 1).map((step) => ({ ...step, state: 'done' as const })),
      {
        key: 'disputed',
        label: 'Under review',
        description: 'Our support team is looking into this booking.',
        state: 'current',
      },
    ];
  }
  if (status === 'REFUNDED') {
    return [
      ...steps.slice(0, currentIndex).map((step) => ({ ...step, state: 'done' as const })),
      { key: 'refunded', label: 'Refunded', state: 'done' },
    ];
  }
  return steps;
}
