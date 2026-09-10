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

export interface AdminPayment {
  id: string;
  bookingReference: string;
  customerName: string;
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

/** GET /admin/payments — payment transactions, optionally filtered. */
export async function fetchPayments(search?: string): Promise<AdminPayment[]> {
  const { data } = await apiClient.get<AdminPayment[]>('/admin/payments', {
    params: search ? { search } : undefined,
  });
  return data;
}

/** POST /admin/payments/{id}/refund — issue a full or partial refund. */
export async function refundPayment(id: string, payload: RefundPayload): Promise<AdminPayment> {
  const { data } = await apiClient.post<AdminPayment>(`/admin/payments/${id}/refund`, payload);
  return data;
}

/** The amount still available to refund on a payment. */
export function refundableAmount(payment: AdminPayment): number {
  return Math.max(0, payment.amount - payment.refundedAmount);
}
