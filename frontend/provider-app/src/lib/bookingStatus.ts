/**
 * The Booking lifecycle, shared by every feature that displays one.
 *
 * These are the exact names of the Booking Service's `BookingStatus` enum
 * (services/booking-service .../domain/BookingStatus.java), which is the single
 * authority for the value the API sends. The dashboard and the job-execution
 * screens used to each declare their own incompatible subset — one of them
 * invented names the backend never emits, so its status chips silently fell
 * back to raw text. One type, one presentation map, no drift.
 */
export type BookingStatus =
  | 'CREATED'
  | 'SEARCHING_PROVIDER'
  | 'SEARCHING_FAILED'
  | 'PROVIDER_ASSIGNED'
  | 'PROVIDER_ACCEPTED'
  | 'PROVIDER_ON_THE_WAY'
  | 'PROVIDER_ARRIVED'
  | 'JOB_STARTED'
  | 'JOB_PAUSED'
  | 'ADDITIONAL_QUOTE_REQUIRED'
  | 'CUSTOMER_APPROVAL_PENDING'
  | 'JOB_COMPLETED'
  | 'CUSTOMER_CONFIRMED'
  | 'PAYMENT_PENDING'
  | 'PAYMENT_COMPLETED'
  | 'DISPUTED'
  | 'REFUNDED'
  | 'CANCELLED';

type StatusColor = 'default' | 'info' | 'primary' | 'success' | 'warning' | 'error';

export interface StatusDescriptor {
  label: string;
  color: StatusColor;
}

/**
 * Presentation metadata for each status, driving the coloured status indicator
 * (MUI Chip) on the active job list and the job screens. Colours progress from
 * neutral (assigned) through primary (in progress) to success (paid), with
 * warning for states that need someone to act and error for terminal failures.
 */
const STATUS_MAP: Record<BookingStatus, StatusDescriptor> = {
  CREATED: { label: 'Created', color: 'default' },
  SEARCHING_PROVIDER: { label: 'Finding a provider', color: 'info' },
  SEARCHING_FAILED: { label: 'No provider found', color: 'error' },
  PROVIDER_ASSIGNED: { label: 'Assigned', color: 'default' },
  PROVIDER_ACCEPTED: { label: 'Accepted', color: 'info' },
  PROVIDER_ON_THE_WAY: { label: 'On the way', color: 'info' },
  PROVIDER_ARRIVED: { label: 'Arrived', color: 'warning' },
  JOB_STARTED: { label: 'In progress', color: 'primary' },
  JOB_PAUSED: { label: 'Paused', color: 'warning' },
  ADDITIONAL_QUOTE_REQUIRED: { label: 'Quote requested', color: 'warning' },
  CUSTOMER_APPROVAL_PENDING: { label: 'Awaiting approval', color: 'warning' },
  // Done but unpaid: the provider waits for the customer to pay.
  JOB_COMPLETED: { label: 'Awaiting payment', color: 'warning' },
  CUSTOMER_CONFIRMED: { label: 'Awaiting payment', color: 'warning' },
  PAYMENT_PENDING: { label: 'Awaiting payment', color: 'warning' },
  PAYMENT_COMPLETED: { label: 'Paid', color: 'success' },
  DISPUTED: { label: 'Disputed', color: 'error' },
  REFUNDED: { label: 'Refunded', color: 'default' },
  CANCELLED: { label: 'Cancelled', color: 'error' },
};

/** Resolve display label + colour for a booking status, with a safe fallback. */
export function describeBookingStatus(status: BookingStatus): StatusDescriptor {
  return STATUS_MAP[status] ?? { label: status, color: 'default' };
}
