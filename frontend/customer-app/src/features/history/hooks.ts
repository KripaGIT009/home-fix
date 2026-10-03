import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import { keepPreviousData } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { isAssignmentStatus, isTerminalStatus } from './status';
import {
  decideQuote,
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

/** How often the history list is re-read while a booking on it is being assigned. */
export const HISTORY_ASSIGNMENT_POLL_MS = 15_000;

/**
 * Paginated booking history. Keeps the previous page visible while fetching.
 * While a row is waiting on a local partner or on the assigned professional's
 * confirmation, the page is re-read so its chip moves on by itself
 * (Requirement MT-13.3).
 */
export function useBookingHistory(
  page: number,
  pageSize: number = DEFAULT_HISTORY_PAGE_SIZE,
): UseQueryResult<BookingHistoryPage, ApiError> {
  return useQuery<BookingHistoryPage, ApiError>({
    queryKey: historyKeys.page(page, pageSize),
    queryFn: () => fetchBookingHistory(page, pageSize),
    placeholderData: keepPreviousData,
    staleTime: 30_000,
    refetchInterval: (query) =>
      query.state.data?.items.some((item) => isAssignmentStatus(item.status))
        ? HISTORY_ASSIGNMENT_POLL_MS
        : false,
  });
}

/** How often an in-flight booking is re-read while a screen is watching it. */
export const BOOKING_POLL_INTERVAL_MS = 5_000;

/**
 * Detailed view of a single booking.
 *
 * With `poll`, the booking is re-read every few seconds until it reaches a
 * terminal status, so the tracking screen follows the booking through the
 * dispatch and job lifecycle. A 403/404 stops polling (the booking is not the
 * caller's, or does not exist); a transient failure backs off to a slower poll.
 *
 * Without `poll`, a booking that a local partner is assigning, or whose assigned
 * professional has yet to confirm, is still re-read until it moves on: nothing
 * the customer does on the screen would change it (Requirement MT-13.3).
 */
export function useBookingDetail(
  bookingId: string,
  options: { poll?: boolean } = {},
): UseQueryResult<BookingDetail, ApiError> {
  const { poll = false } = options;
  return useQuery<BookingDetail, ApiError>({
    queryKey: historyKeys.detail(bookingId),
    queryFn: () => fetchBookingDetail(bookingId),
    enabled: Boolean(bookingId),
    staleTime: poll ? 0 : 30_000,
    refetchInterval: (query) => {
      const { error, data } = query.state;
      const watching = poll ? !isTerminalStatus(data?.status) : isAssignmentStatus(data?.status);
      if (!watching) return false;
      if (error) {
        return error.status === 403 || error.status === 404 ? false : BOOKING_POLL_INTERVAL_MS * 3;
      }
      return BOOKING_POLL_INTERVAL_MS;
    },
  });
}

/** Approve or decline an updated quote, then re-read the booking (Requirement 9.7, 9.8). */
export function useQuoteDecision(bookingId: string): UseMutationResult<void, ApiError, boolean> {
  const queryClient = useQueryClient();
  return useMutation<void, ApiError, boolean>({
    mutationFn: (approve) => decideQuote(bookingId, approve),
    onSettled: () =>
      void queryClient.invalidateQueries({ queryKey: historyKeys.detail(bookingId) }),
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
