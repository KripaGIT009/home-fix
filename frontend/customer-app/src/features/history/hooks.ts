import { useMutation, useQuery } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import { keepPreviousData } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import {
  fetchBookingDetail,
  fetchBookingHistory,
  fetchInvoiceDownloadUrl,
  type BookingDetail,
  type BookingHistoryPage,
  type InvoiceDownload,
} from './api';

/** TanStack Query hooks for Service History (Requirement 28.7, Requirement 13). */

export const DEFAULT_HISTORY_PAGE_SIZE = 10;

export const historyKeys = {
  page: (page: number, pageSize: number) => ['history', 'bookings', page, pageSize] as const,
  detail: (bookingId: string) => ['history', 'booking', bookingId] as const,
};

/** Paginated booking history. Keeps the previous page visible while fetching. */
export function useBookingHistory(
  page: number,
  pageSize: number = DEFAULT_HISTORY_PAGE_SIZE,
): UseQueryResult<BookingHistoryPage, ApiError> {
  return useQuery<BookingHistoryPage, ApiError>({
    queryKey: historyKeys.page(page, pageSize),
    queryFn: () => fetchBookingHistory(page, pageSize),
    placeholderData: keepPreviousData,
    staleTime: 30_000,
  });
}

/** Detailed view of a single booking. */
export function useBookingDetail(bookingId: string): UseQueryResult<BookingDetail, ApiError> {
  return useQuery<BookingDetail, ApiError>({
    queryKey: historyKeys.detail(bookingId),
    queryFn: () => fetchBookingDetail(bookingId),
    enabled: Boolean(bookingId),
    staleTime: 30_000,
  });
}

/**
 * Fetch a fresh signed invoice URL on demand. Modeled as a mutation because it
 * is triggered by an explicit user action (tapping "Download invoice") and
 * should not be cached — the signed URL is short-lived (Requirement 13.3).
 */
export function useInvoiceDownload(): UseMutationResult<InvoiceDownload, ApiError, string> {
  return useMutation<InvoiceDownload, ApiError, string>({
    mutationFn: (invoiceId: string) => fetchInvoiceDownloadUrl(invoiceId),
  });
}
