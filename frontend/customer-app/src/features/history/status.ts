import type { BookingStatus } from '@stores/bookingStore';
import { brand } from '@lib/theme';

/**
 * The one place booking statuses get their customer-facing label and colour.
 * The Service History list, booking detail and live tracking all read from
 * here, so a status looks the same wherever it appears.
 */

/**
 * Visual families a status can belong to. Red is kept for emergency and
 * destructive actions, so no status uses it; problems read as amber.
 */
export type StatusTone = 'neutral' | 'searching' | 'active' | 'attention' | 'success';

const STATUS_META: Record<BookingStatus, { label: string; tone: StatusTone }> = {
  CREATED: { label: 'Booked', tone: 'neutral' },
  SEARCHING_PROVIDER: { label: 'Finding a pro', tone: 'searching' },
  SEARCHING_FAILED: { label: 'No pro found', tone: 'attention' },
  PROVIDER_ASSIGNED: { label: 'Pro assigned', tone: 'active' },
  PROVIDER_ACCEPTED: { label: 'Pro confirmed', tone: 'active' },
  PROVIDER_ON_THE_WAY: { label: 'On the way', tone: 'active' },
  PROVIDER_ARRIVED: { label: 'Arrived', tone: 'active' },
  JOB_STARTED: { label: 'In progress', tone: 'active' },
  JOB_PAUSED: { label: 'Paused', tone: 'attention' },
  ADDITIONAL_QUOTE_REQUIRED: { label: 'New quote', tone: 'attention' },
  CUSTOMER_APPROVAL_PENDING: { label: 'Needs approval', tone: 'attention' },
  JOB_COMPLETED: { label: 'Completed', tone: 'success' },
  CUSTOMER_CONFIRMED: { label: 'Confirmed', tone: 'success' },
  PAYMENT_PENDING: { label: 'Payment due', tone: 'attention' },
  PAYMENT_COMPLETED: { label: 'Paid', tone: 'success' },
  DISPUTED: { label: 'Under review', tone: 'attention' },
  REFUNDED: { label: 'Refunded', tone: 'neutral' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral' },
};

/** Foreground, background and dot colours for each tone. */
export const STATUS_TONE_COLORS: Record<StatusTone, { fg: string; bg: string; dot: string }> = {
  neutral: { fg: '#475467', bg: brand.slateSoft, dot: '#98A2B3' },
  searching: { fg: brand.accentDeep, bg: brand.accentSoft, dot: brand.accent },
  active: { fg: brand.accentDark, bg: brand.accentSoft, dot: brand.accent },
  attention: { fg: brand.warmDark, bg: brand.warmSoft, dot: brand.warm },
  success: { fg: '#05603A', bg: brand.greenSoft, dot: brand.green },
};

export function bookingStatusLabel(status: BookingStatus): string {
  return STATUS_META[status]?.label ?? 'Updating';
}

export function bookingStatusTone(status: BookingStatus): StatusTone {
  return STATUS_META[status]?.tone ?? 'neutral';
}

/** Statuses after which nothing more will happen on a booking. */
export const TERMINAL_STATUSES: readonly BookingStatus[] = [
  'SEARCHING_FAILED',
  'PAYMENT_COMPLETED',
  'REFUNDED',
  'CANCELLED',
];

export function isTerminalStatus(status: BookingStatus | undefined): boolean {
  return status !== undefined && TERMINAL_STATUSES.includes(status);
}
