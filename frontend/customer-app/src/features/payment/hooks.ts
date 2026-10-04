import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult } from '@tanstack/react-query';
import { isApiError } from '@api/client';
import { useAuthStore } from '@stores/authStore';
import type { BookingDetail } from '@features/history/api';
import { historyKeys, useBookingDetail } from '@features/history/hooks';
import {
  awaitsRazorpayCheckout,
  confirmRazorpayPayment,
  fetchRazorpayCheckout,
  isPayableStatus,
  payForBooking,
  type PaymentMethod,
  type PaymentTransaction,
} from './api';
import { isCheckoutDismissed, openRazorpayCheckout } from './razorpay';

/**
 * Pays a booking: opens the payment, and when it is a Razorpay payment, takes
 * the customer through Checkout and has the server confirm what Checkout
 * returned. Paying again after closing Checkout reopens it on the same
 * Razorpay order, since the Payment Service returns the still-open payment.
 */
async function payBooking(bookingId: string, method: PaymentMethod): Promise<PaymentTransaction> {
  const tx = await payForBooking(bookingId, method);
  if (!awaitsRazorpayCheckout(tx)) return tx;

  let checkout;
  try {
    checkout = await fetchRazorpayCheckout(tx.id);
  } catch (error) {
    // Settled between the two calls (e.g. by Razorpay's webhook): the booking re-read shows it.
    if (isApiError(error) && error.code === 'PAYMENT_NOT_AWAITING_CHECKOUT') return tx;
    throw error;
  }
  const user = useAuthStore.getState().user;
  const result = await openRazorpayCheckout(checkout, method, {
    name: user?.displayName,
    email: user?.email,
    contact: user?.mobileNumber,
  });
  return confirmRazorpayPayment(tx.id, result);
}

/**
 * Pay for a completed booking (Requirement 12), then re-read the booking
 * whatever the outcome. A 409 BOOKING_NOT_PAYABLE means the booking moved on
 * (already paid, cancelled, disputed), so the re-read shows the customer where
 * it stands now.
 */
export function usePayBooking(
  bookingId: string,
): UseMutationResult<PaymentTransaction, Error, PaymentMethod> {
  const queryClient = useQueryClient();
  return useMutation<PaymentTransaction, Error, PaymentMethod>({
    mutationFn: (method) => payBooking(bookingId, method),
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
  error: Error | null;
  /** The gateway declined the payment; the customer can try again. */
  declined: boolean;
  /**
   * The customer closed Razorpay Checkout without paying, with the reason
   * Checkout gave if an attempt in it failed. Paying again reopens it.
   */
  cancelled: { failure: string | undefined } | null;
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

  const dismissed = isCheckoutDismissed(pay.error) ? pay.error : null;

  return {
    payable,
    method,
    setMethod,
    submit: () => pay.mutate(method),
    submitting: pay.isPending,
    error: dismissed ? null : pay.error,
    declined,
    cancelled: dismissed ? { failure: dismissed.failure } : null,
    confirming,
    settled: pay.data?.status === 'SUCCESS',
  };
}
