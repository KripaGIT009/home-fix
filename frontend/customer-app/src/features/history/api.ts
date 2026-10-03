import { apiClient } from '@api/client';
import type { BookingStatus } from '@stores/bookingStore';

/**
 * Service History API bindings (Requirement 28.7). Combines the Booking
 * Service's booking list/detail with the Invoice Service's signed-URL delivery
 * (Requirement 13.3, 13.5). All calls flow through the shared Axios client.
 */

/** A row in the paginated Service History list. */
export interface BookingHistoryItem {
  bookingId: string;
  referenceNumber: string;
  status: BookingStatus;
  /** Subcategory display name for the list row. */
  serviceName: string;
  /** ISO-8601 booking date (created or scheduled). */
  date: string;
  /** Final/estimated amount for the booking, in the platform currency. */
  amount: number;
  currency?: string;
}

/** One page of booking history (Requirement 28.7 — paginated list). */
export interface BookingHistoryPage {
  items: BookingHistoryItem[];
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
}

/**
 * GET /bookings/history?page&pageSize — paginated booking history for the
 * authenticated Customer, newest first. `page` is 1-based; `pageSize` is at
 * most 50.
 */
export async function fetchBookingHistory(
  page: number,
  pageSize: number,
): Promise<BookingHistoryPage> {
  const { data } = await apiClient.get<BookingHistoryPage>('/bookings/history', {
    params: { page, pageSize },
  });
  return data;
}

/**
 * Detailed view of a single booking (Requirement 28.7).
 *
 * The Booking Service omits null fields: `scheduledAt` and `providerId` are
 * absent until set. `address`, `description`, `provider` and `invoice` are not
 * owned by the Booking Service and are not sent today, so every screen treats
 * them as optional and hides their sections when absent.
 */
export interface BookingDetail {
  bookingId: string;
  referenceNumber: string;
  status: BookingStatus;
  /** Falls back to the literal "Service" when the catalog cannot resolve it. */
  serviceName: string;
  date: string;
  amount: number;
  currency?: string;
  /** True for an emergency (dispatch-now) booking. */
  emergency?: boolean;
  /** ISO-8601 time the visit is scheduled for; absent for emergency bookings. */
  scheduledAt?: string;
  /** ISO-8601 time the booking was created. */
  createdAt?: string;
  /** The assigned provider's id, once one is assigned. */
  providerId?: string;
  /** The booked subcategory — used to resolve the service name and to book again. */
  subcategoryId?: string;
  /** The service address as the customer entered it. */
  address?: string;
  /** The service address's position — frames the live map and the ETA estimate. */
  coordinates?: { latitude: number; longitude: number };
  /** Parts and materials the professional has recorded (Requirement 11.3). */
  parts?: Array<{ id: string; itemName: string; quantity: number; unitCost: number }>;
  description?: string;
  /**
   * The local partner agency that assigned the professional, sent while the
   * booking is `PROVIDER_ASSIGNED` and waiting for their confirmation
   * (Requirement MT-6.4).
   */
  tenantName?: string;
  provider?: {
    displayName: string;
    verified: boolean;
    rating: number;
  };
  /** Present once an invoice exists for the booking (Requirement 13). */
  invoice?: {
    invoiceId: string;
    invoiceNumber: string;
  };
}

/**
 * GET /bookings/{key} — detailed booking view. The key may be the booking id or
 * its HFX- reference; a booking that is not the caller's is a 404.
 */
export async function fetchBookingDetail(bookingId: string): Promise<BookingDetail> {
  const { data } = await apiClient.get<BookingDetail>(`/bookings/${bookingId}`);
  return data;
}

/**
 * POST /bookings/{key}/quote/approval or /quote/rejection — the customer's
 * answer to an updated quote after the professional added parts (Requirement
 * 9.7, 9.8). Approving resumes the job at the new price; declining completes
 * it at the original price.
 */
export async function decideQuote(bookingId: string, approve: boolean): Promise<void> {
  await apiClient.post(`/bookings/${bookingId}/quote/${approve ? 'approval' : 'rejection'}`);
}

/** A short-lived signed URL for downloading an invoice PDF (Requirement 13.3). */
export interface InvoiceDownload {
  /** Signed URL with a 72-hour expiry (Requirement 13.3). */
  url: string;
  invoiceNumber: string;
}

/**
 * GET /invoices/{invoiceId}/download — obtain a fresh signed URL for the
 * invoice PDF. The Invoice Service returns a URL with a 72-hour expiry; the
 * client opens it to view/download the PDF (Requirement 13.3, Task 33
 * acceptance: "invoice download opens PDF").
 */
export async function fetchInvoiceDownloadUrl(invoiceId: string): Promise<InvoiceDownload> {
  const { data } = await apiClient.get<InvoiceDownload>(`/invoices/${invoiceId}/download`);
  return data;
}
