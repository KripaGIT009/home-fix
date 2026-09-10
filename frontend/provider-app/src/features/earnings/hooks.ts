import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { dashboardKeys } from '../dashboard/hooks';
import {
  fetchEarningsHistory,
  fetchSettlementInfo,
  fetchSettlements,
  requestSettlement,
  type EarningsHistoryPage,
  type SettlementInfo,
  type SettlementRequest,
  type SettlementRequestPayload,
} from './api';

/** Query keys for Provider earnings & settlement resources. */
export const earningsKeys = {
  history: (page: number, pageSize: number) =>
    ['provider', 'earnings-history', page, pageSize] as const,
  settlementInfo: ['provider', 'settlement-info'] as const,
  settlements: ['provider', 'settlements'] as const,
};

/** Paginated per-job earnings history (Requirement 14.5). */
export function useEarningsHistory(
  page: number,
  pageSize: number,
): UseQueryResult<EarningsHistoryPage, ApiError> {
  return useQuery<EarningsHistoryPage, ApiError>({
    queryKey: earningsKeys.history(page, pageSize),
    queryFn: () => fetchEarningsHistory(page, pageSize),
    placeholderData: keepPreviousData,
  });
}

/** Wallet balance + verified bank accounts for the settlement form (Req 14.2). */
export function useSettlementInfo(): UseQueryResult<SettlementInfo, ApiError> {
  return useQuery<SettlementInfo, ApiError>({
    queryKey: earningsKeys.settlementInfo,
    queryFn: fetchSettlementInfo,
  });
}

/** Settlement request history (Requirement 14.3). */
export function useSettlements(): UseQueryResult<SettlementRequest[], ApiError> {
  return useQuery<SettlementRequest[], ApiError>({
    queryKey: earningsKeys.settlements,
    queryFn: fetchSettlements,
  });
}

/** Request a settlement, then refresh balance, history, and dashboard summary. */
export function useRequestSettlement(): UseMutationResult<
  SettlementRequest,
  ApiError,
  SettlementRequestPayload
> {
  const queryClient = useQueryClient();
  return useMutation<SettlementRequest, ApiError, SettlementRequestPayload>({
    mutationFn: requestSettlement,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: earningsKeys.settlements });
      void queryClient.invalidateQueries({ queryKey: earningsKeys.settlementInfo });
      void queryClient.invalidateQueries({ queryKey: dashboardKeys.summary });
    },
  });
}
