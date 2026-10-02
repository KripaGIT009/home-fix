import { apiClient } from '@api/client';

/**
 * Payment and Refund Management bindings (Requirement 19.2, Requirement 10).
 *
 * Admins review payment transactions and issue refunds. Finance_Admins
 * additionally reconcile settlements; refund issuance is recorded in the
 * Audit_Log (Req 19.8).
 *
 * Endpoints (see design.md — Admin Service / Payment Service):
 * - GET  /admin/payments               — list/search payment transactions
 * - POST /admin/payments/{id}/refund   — issue a full or partial refund
 */

export type PaymentStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'REFUNDED' | 'PARTIALLY_REFUNDED';

export type PaymentMethod = 'CARD' | 'UPI' | 'NETBANKING' | 'WALLET' | 'CASH';

/**
 * A payment row. The Payment Service owns the transaction; the customer name
 * lives in another service and comes back null. `bookingReference` carries the
 * booking's id (a UUID), not a human reference, since the Payment Service does
 * not hold the latter.
 */
export interface AdminPayment {
  id: string;
  bookingReference?: string | null;
  customerName?: string | null;
  amount: number;
  refundedAmount: number;
  currency: string;
  method: PaymentMethod;
  status: PaymentStatus;
  gateway: string;
  createdAt: string;
}

export interface RefundPayload {
  /** Amount to refund; must be > 0 and ≤ (amount − refundedAmount). */
  amount: number;
  reason: string;
}

/** Header the Payment Service requires on every refund request. */
export const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key';

/** GET /admin/payments — payment transactions, optionally filtered. */
export async function fetchPayments(search?: string): Promise<AdminPayment[]> {
  const { data } = await apiClient.get<AdminPayment[]>('/admin/payments', {
    params: search ? { search } : undefined,
  });
  return data;
}

/**
 * POST /admin/payments/{id}/refund — issue a full or partial refund.
 *
 * The idempotency key makes a retry safe: if the first attempt reached the
 * gateway but its response was lost, replaying the same key returns that
 * refund instead of issuing a second one. The caller owns the key's lifetime
 * (one per submission, reused on retry).
 */
export async function refundPayment(
  id: string,
  payload: RefundPayload,
  idempotencyKey: string,
): Promise<AdminPayment> {
  const { data } = await apiClient.post<AdminPayment>(`/admin/payments/${id}/refund`, payload, {
    headers: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey },
  });
  return data;
}

/** The amount still available to refund on a payment. */
export function refundableAmount(payment: AdminPayment): number {
  return Math.max(0, payment.amount - payment.refundedAmount);
}
