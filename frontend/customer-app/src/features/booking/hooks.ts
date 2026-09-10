import { useMutation } from '@tanstack/react-query';
import type { UseMutationResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { useBookingStore, type BookingStatus } from '@stores/bookingStore';
import {
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

/** Create a booking on confirmation (Requirements 7.5, 8.1). */
export function useCreateBooking(): UseMutationResult<
  CreateBookingResponse,
  ApiError,
  CreateBookingPayload
> {
  const setActiveBooking = useBookingStore((state) => state.setActiveBooking);

  return useMutation<CreateBookingResponse, ApiError, CreateBookingPayload>({
    mutationFn: createBooking,
    onSuccess: (response, variables) => {
      setActiveBooking({
        bookingId: response.bookingId,
        referenceNumber: response.referenceNumber,
        status: response.status as BookingStatus,
        subcategoryId: variables.subcategoryId,
        // The Booking Service is authoritative for the address ID; until the
        // response carries it we track the emergency flag and subcategory,
        // which the tracking screens need immediately.
        addressId: '',
        isEmergency: response.isEmergency,
      });
    },
  });
}
