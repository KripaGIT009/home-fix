import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import {
  fetchPricingConfigs,
  updatePricingConfig,
  type PricingConfig,
  type PricingConfigUpdate,
} from './api';

export const pricingKeys = {
  configs: ['admin', 'pricing', 'configs'] as const,
};

/** Pricing configuration for every service subcategory (Requirement 6.11). */
export function usePricingConfigs(): UseQueryResult<PricingConfig[], ApiError> {
  return useQuery<PricingConfig[], ApiError>({
    queryKey: pricingKeys.configs,
    queryFn: fetchPricingConfigs,
  });
}

/** Update a single subcategory's pricing configuration (Requirement 6.11). */
export function useUpdatePricingConfig(): UseMutationResult<
  PricingConfig,
  ApiError,
  { subcategoryId: string; update: PricingConfigUpdate }
> {
  const queryClient = useQueryClient();
  return useMutation<
    PricingConfig,
    ApiError,
    { subcategoryId: string; update: PricingConfigUpdate }
  >({
    mutationFn: ({ subcategoryId, update }) => updatePricingConfig(subcategoryId, update),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: pricingKeys.configs });
    },
  });
}
