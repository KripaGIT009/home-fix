import { useRef } from 'react';
import { useMutation } from '@tanstack/react-query';
import type { UseMutationResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { useBookingStore, type BookingStatus } from '@stores/bookingStore';
import {
  confirmBooking,
  createBooking,
  requestEstimate,
  type CreateBookingPayload,
  type CreateBookingResponse,
  type EstimateRequestPayload,
  type PriceEstimate,
} from './api';

/**
 * TanStack Query mutations for the booking flow (Requirements 6, 7, 8).
 *
 * The estimate is modelled as a mutation because it is a POST driven by the
 * user-entered request (and re-run when a coupon is applied), and the booking
 * creation is a mutation that persists the active booking into the store on
 * success so the tracking screens can pick it up.
 */

/** Request an itemized price estimate before confirmation (Requirement 7.3). */
export function useEstimate(): UseMutationResult<PriceEstimate, ApiError, EstimateRequestPayload> {
  return useMutation<PriceEstimate, ApiError, EstimateRequestPayload>({
    mutationFn: requestEstimate,
  });
}

/**
 * Create and confirm a booking when the customer accepts the estimate
 * (Requirements 7.5, 8.1).
 *
 * A scheduled booking is created in CREATED and only reaches dispatch once it
 * is confirmed, so both calls happen here and the mutation succeeds only when
 * the booking is searching for a provider. Emergency bookings are confirmed by
 * the service as part of creation.
 *
 * If creation succeeds but confirmation fails, a retry confirms the booking
 * already created instead of creating a second one -- unless the coupon has
 * changed since, in which case the first booking was priced differently and is
 * left unconfirmed (an unconfirmed booking is never dispatched).
 */
export function useCreateBooking(): UseMutationResult<
  CreateBookingResponse,
  ApiError,
  CreateBookingPayload
> {
  const setActiveBooking = useBookingStore((state) => state.setActiveBooking);
  const unconfirmed = useRef<{ booking: CreateBookingResponse; couponCode?: string } | null>(null);

  return useMutation<CreateBookingResponse, ApiError, CreateBookingPayload>({
    mutationFn: async (payload) => {
      const pending = unconfirmed.current;
      const created =
        pending && pending.couponCode === payload.couponCode
          ? pending.booking
          : await createBooking(payload);
      if (created.emergency || created.status !== 'CREATED') {
        unconfirmed.current = null;
        return created;
      }
      unconfirmed.current = {
        booking: created,
        ...(payload.couponCode ? { couponCode: payload.couponCode } : {}),
      };
      const confirmed = await confirmBooking(created.reference);
      unconfirmed.current = null;
      return confirmed;
    },
    onSuccess: (response, variables) => {
      setActiveBooking({
        bookingId: response.bookingId,
        // The service spells these `reference` and `emergency`; the store keeps
        // the UI's own names, so the mapping happens here.
        referenceNumber: response.reference,
        status: response.status as BookingStatus,
        subcategoryId: variables.subcategoryId,
        // The Booking Service is authoritative for the address ID; until the
        // response carries it we track the emergency flag and subcategory,
        // which the tracking screens need immediately.
        addressId: '',
        isEmergency: response.emergency,
      });
    },
  });
}
