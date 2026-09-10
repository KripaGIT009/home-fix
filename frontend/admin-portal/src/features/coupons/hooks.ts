import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import {
  createCoupon,
  deactivateCoupon,
  fetchCoupons,
  type Coupon,
  type CreateCouponPayload,
} from './api';

export const couponKeys = {
  list: ['admin', 'coupons'] as const,
};

/** All coupons (Requirement 21). */
export function useCoupons(): UseQueryResult<Coupon[], ApiError> {
  return useQuery<Coupon[], ApiError>({
    queryKey: couponKeys.list,
    queryFn: fetchCoupons,
  });
}

/** Create a new coupon (Requirement 21.1). */
export function useCreateCoupon(): UseMutationResult<Coupon, ApiError, CreateCouponPayload> {
  const queryClient = useQueryClient();
  return useMutation<Coupon, ApiError, CreateCouponPayload>({
    mutationFn: createCoupon,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: couponKeys.list });
    },
  });
}

/** Deactivate a coupon, preventing further redemptions (Requirement 21.4). */
export function useDeactivateCoupon(): UseMutationResult<Coupon, ApiError, string> {
  const queryClient = useQueryClient();
  return useMutation<Coupon, ApiError, string>({
    mutationFn: deactivateCoupon,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: couponKeys.list });
    },
  });
}
