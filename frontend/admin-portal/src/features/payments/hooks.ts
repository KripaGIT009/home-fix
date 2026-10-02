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

/** A refund submission: the payment, what to refund, and its idempotency key. */
export interface RefundVariables {
  id: string;
  payload: RefundPayload;
  idempotencyKey: string;
}

/** Issue a full or partial refund against a payment. */
export function useRefundPayment(): UseMutationResult<AdminPayment, ApiError, RefundVariables> {
  const queryClient = useQueryClient();
  return useMutation<AdminPayment, ApiError, RefundVariables>({
    mutationFn: ({ id, payload, idempotencyKey }) => refundPayment(id, payload, idempotencyKey),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin', 'payments'] });
    },
  });
}
