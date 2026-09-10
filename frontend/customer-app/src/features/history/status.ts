import type { BookingStatus } from '@stores/bookingStore';

type StatusColor = 'default' | 'primary' | 'success' | 'warning' | 'error' | 'info';

/**
 * Presentation metadata for each Booking status: a human-readable label and a
 * MUI color for the status chip in the Service History list and detail views.
 */
const STATUS_META: Record<BookingStatus, { label: string; color: StatusColor }> = {
  CREATED: { label: 'Created', color: 'default' },
  SEARCHING_PROVIDER: { label: 'Finding a pro', color: 'info' },
  SEARCHING_FAILED: { label: 'No pro found', color: 'error' },
  PROVIDER_ASSIGNED: { label: 'Pro assigned', color: 'info' },
  PROVIDER_ACCEPTED: { label: 'Pro accepted', color: 'info' },
  PROVIDER_ON_THE_WAY: { label: 'On the way', color: 'primary' },
  PROVIDER_ARRIVED: { label: 'Arrived', color: 'primary' },
  JOB_STARTED: { label: 'In progress', color: 'primary' },
  JOB_PAUSED: { label: 'Paused', color: 'warning' },
  ADDITIONAL_QUOTE_REQUIRED: { label: 'Quote needed', color: 'warning' },
  CUSTOMER_APPROVAL_PENDING: { label: 'Approval pending', color: 'warning' },
  JOB_COMPLETED: { label: 'Completed', color: 'success' },
  CUSTOMER_CONFIRMED: { label: 'Confirmed', color: 'success' },
  PAYMENT_PENDING: { label: 'Payment pending', color: 'warning' },
  PAYMENT_COMPLETED: { label: 'Paid', color: 'success' },
  DISPUTED: { label: 'Disputed', color: 'error' },
  REFUNDED: { label: 'Refunded', color: 'default' },
  CANCELLED: { label: 'Cancelled', color: 'default' },
};

export function bookingStatusLabel(status: BookingStatus): string {
  return STATUS_META[status]?.label ?? status;
}

export function bookingStatusColor(status: BookingStatus): StatusColor {
  return STATUS_META[status]?.color ?? 'default';
}
