import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { cancelBooking, fetchBookings, type AdminBooking, type BookingStatus } from './api';

export const bookingKeys = {
  list: (search: string, status: BookingStatus | '') =>
    ['admin', 'bookings', { search, status }] as const,
};

/** Bookings filtered by an optional search term and status (Requirement 19.2). */
export function useBookings(
  search: string,
  status: BookingStatus | '',
): UseQueryResult<AdminBooking[], ApiError> {
  return useQuery<AdminBooking[], ApiError>({
    queryKey: bookingKeys.list(search, status),
    queryFn: () => fetchBookings({ ...(search ? { search } : {}), status }),
  });
}

/** Admin force-cancel a booking with a reason. */
export function useCancelBooking(): UseMutationResult<
  AdminBooking,
  ApiError,
  { id: string; reason: string }
> {
  const queryClient = useQueryClient();
  return useMutation<AdminBooking, ApiError, { id: string; reason: string }>({
    mutationFn: ({ id, reason }) => cancelBooking(id, reason),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin', 'bookings'] });
    },
  });
}
