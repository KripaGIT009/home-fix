import { apiClient } from '@api/client';

/**
 * Booking Management bindings (Requirement 19.2).
 *
 * Admins search and inspect bookings across the platform and can force-cancel a
 * booking when intervention is required. Cancellations are recorded in the
 * Audit_Log (Req 19.8).
 *
 * Endpoints (see design.md — Admin Service / Booking Service):
 * - GET  /admin/bookings              — list/search bookings (optional status filter)
 * - GET  /admin/bookings/{id}         — a single booking's detail
 * - POST /admin/bookings/{id}/cancel  — admin force-cancel with a reason
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

export interface AdminBooking {
  id: string;
  reference: string;
  customerName: string;
  providerName?: string;
  serviceName: string;
  status: BookingStatus;
  isEmergency: boolean;
  totalAmount: number;
  currency: string;
  createdAt: string;
  scheduledAt?: string;
}

/** GET /admin/bookings — bookings, optionally filtered by search + status. */
export async function fetchBookings(params: {
  search?: string;
  status?: BookingStatus | '';
}): Promise<AdminBooking[]> {
  const query: Record<string, string> = {};
  if (params.search) query.search = params.search;
  if (params.status) query.status = params.status;
  const { data } = await apiClient.get<AdminBooking[]>('/admin/bookings', {
    params: Object.keys(query).length > 0 ? query : undefined,
  });
  return data;
}

/** POST /admin/bookings/{id}/cancel — admin force-cancel with a reason. */
export async function cancelBooking(id: string, reason: string): Promise<AdminBooking> {
  const { data } = await apiClient.post<AdminBooking>(`/admin/bookings/${id}/cancel`, {
    reason,
  });
  return data;
}
