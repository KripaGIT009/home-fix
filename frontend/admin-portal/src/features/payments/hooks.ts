import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { fetchPayments, refundPayment, type AdminPayment, type RefundPayload } from './api';

export const paymentKeys = {
  list: (search: string) => ['admin', 'payments', { search }] as const,
};

/** Payment transactions filtered by an optional search term (Requirement 19.2). */
export function usePayments(search: string): UseQueryResult<AdminPayment[], ApiError> {
  return useQuery<AdminPayment[], ApiError>({
    queryKey: paymentKeys.list(search),
    queryFn: () => fetchPayments(search || undefined),
  });
}

/** Issue a full or partial refund against a payment. */
export function useRefundPayment(): UseMutationResult<
  AdminPayment,
  ApiError,
  { id: string; payload: RefundPayload }
> {
  const queryClient = useQueryClient();
  return useMutation<AdminPayment, ApiError, { id: string; payload: RefundPayload }>({
    mutationFn: ({ id, payload }) => refundPayment(id, payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin', 'payments'] });
    },
  });
}
