import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import type { BookingDetail } from '@features/history/api';
import { historyKeys, useBookingDetail } from '@features/history/hooks';
import { isPayableStatus, payForBooking, type PaymentMethod, type PaymentTransaction } from './api';

/**
 * Pay for a completed booking (Requirement 12), then re-read the booking
 * whatever the outcome. A 409 BOOKING_NOT_PAYABLE means the booking moved on
 * (already paid, cancelled, disputed), so the re-read shows the customer where
 * it stands now.
 */
export function usePayBooking(
  bookingId: string,
): UseMutationResult<PaymentTransaction, ApiError, PaymentMethod> {
  const queryClient = useQueryClient();
  return useMutation<PaymentTransaction, ApiError, PaymentMethod>({
    mutationFn: (method) => payForBooking(bookingId, method),
    onSettled: () =>
      void queryClient.invalidateQueries({ queryKey: historyKeys.detail(bookingId) }),
  });
}

/** Everything a paying screen needs, so tracking and booking detail behave alike. */
export interface PaymentFlow {
  /** The booking is done and unpaid. */
  payable: boolean;
  method: PaymentMethod;
  setMethod: (method: PaymentMethod) => void;
  /** Pays with the chosen method. */
  submit: () => void;
  submitting: boolean;
  /** The request itself failed (network, 409, 503...). */
  error: ApiError | null;
  /** The gateway declined the payment; the customer can try again. */
  declined: boolean;
  /**
   * The payment was accepted but the booking has not reached PAYMENT_COMPLETED
   * yet; `settled` tells a confirmed charge from one the gateway is still
   * confirming.
   */
  confirming: boolean;
  settled: boolean;
}

/**
 * The pay-for-a-completed-job flow for one booking. A successful payment moves
 * the booking to PAYMENT_COMPLETED a moment later through an event, so while
 * the booking catches up it is re-read every few seconds instead of assuming
 * the move is instant.
 */
export function usePaymentFlow(detail: BookingDetail): PaymentFlow {
  const [method, setMethod] = useState<PaymentMethod>('UPI');
  const pay = usePayBooking(detail.bookingId);

  const payable = isPayableStatus(detail.status);
  const declined = pay.data?.status === 'FAILED';
  const confirming = payable && pay.isSuccess && !declined;
  useBookingDetail(detail.bookingId, { poll: confirming });

  return {
    payable,
    method,
    setMethod,
    submit: () => pay.mutate(method),
    submitting: pay.isPending,
    error: pay.error,
    declined,
    confirming,
    settled: pay.data?.status === 'SUCCESS',
  };
}
