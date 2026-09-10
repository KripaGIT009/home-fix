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
 * authenticated Customer, newest first.
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

/** Detailed view of a single booking (Requirement 28.7). */
export interface BookingDetail {
  bookingId: string;
  referenceNumber: string;
  status: BookingStatus;
  serviceName: string;
  date: string;
  amount: number;
  currency?: string;
  address?: string;
  description?: string;
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

/** GET /bookings/{bookingId} — detailed booking view. */
export async function fetchBookingDetail(bookingId: string): Promise<BookingDetail> {
  const { data } = await apiClient.get<BookingDetail>(`/bookings/${bookingId}`);
  return data;
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
