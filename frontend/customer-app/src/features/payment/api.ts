import { apiClient } from '@api/client';
import type { BookingStatus } from '@stores/bookingStore';

/**
 * Payment Service bindings (Requirement 12). The customer pays for a completed
 * job by naming the booking and a method only: the Payment Service reads the
 * amount and the provider from the Booking Service, so nothing the app sends
 * can change what is charged.
 */

/** Supported payment methods (Requirement 12.2), as the Payment Service names them. */
export type PaymentMethod = 'UPI' | 'CREDIT_DEBIT_CARD' | 'NET_BANKING' | 'WALLET' | 'CASH';

/** Customer-facing labels, in the order the methods are offered. */
export const PAYMENT_METHODS: ReadonlyArray<{ value: PaymentMethod; label: string }> = [
  { value: 'UPI', label: 'UPI' },
  { value: 'CREDIT_DEBIT_CARD', label: 'Card' },
  { value: 'NET_BANKING', label: 'Net banking' },
  { value: 'WALLET', label: 'Wallet' },
  { value: 'CASH', label: 'Cash' },
];

/** Payment transaction lifecycle (Requirement 12.4). */
export type TransactionStatus =
  'PENDING' | 'SUCCESS' | 'FAILED' | 'REFUNDED' | 'PARTIALLY_REFUNDED';

/** The Payment Service's view of a transaction. Never carries the payment credential. */
export interface PaymentTransaction {
  id: string;
  customerId: string;
  bookingId: string;
  providerId: string;
  amount: number;
  platformFee: number;
  providerNetEarning: number;
  refundedAmount: number;
  method: PaymentMethod;
  gateway: string;
  status: TransactionStatus;
  attemptCount: number;
  createdAt: string;
  updatedAt: string;
}

/** Booking statuses in which the job is done and the customer still has to pay. */
export const PAYABLE_STATUSES: readonly BookingStatus[] = [
  'JOB_COMPLETED',
  'CUSTOMER_CONFIRMED',
  'PAYMENT_PENDING',
];

export function isPayableStatus(status: BookingStatus | undefined): boolean {
  return status !== undefined && PAYABLE_STATUSES.includes(status);
}

/**
 * POST /payments — pay for a completed booking. Answers with the transaction:
 * SUCCESS when the gateway settled at once, PENDING while it is still
 * confirming, FAILED when the charge was declined (a new call starts a fresh
 * attempt). The booking itself moves to PAYMENT_COMPLETED shortly after a
 * success, via an event, so callers re-read the booking rather than assume it.
 * A booking that can no longer be paid is a 409 BOOKING_NOT_PAYABLE.
 */
export async function payForBooking(
  bookingId: string,
  method: PaymentMethod,
): Promise<PaymentTransaction> {
  const { data } = await apiClient.post<PaymentTransaction>('/payments', { bookingId, method });
  return data;
}
