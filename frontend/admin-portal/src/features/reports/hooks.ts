import { useMutation, useQuery } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import {
  fetchReportTypes,
  requestReport,
  type ReportRequestPayload,
  type ReportResult,
  type ReportType,
} from './api';

export const reportKeys = {
  types: ['admin', 'reports', 'types'] as const,
};

/** Report types available to the current user (Requirement 20). */
export function useReportTypes(): UseQueryResult<ReportType[], ApiError> {
  return useQuery<ReportType[], ApiError>({
    queryKey: reportKeys.types,
    queryFn: fetchReportTypes,
  });
}

/** Request a report; the result is sync (download link) or queued (email). */
export function useRequestReport(): UseMutationResult<
  ReportResult,
  ApiError,
  ReportRequestPayload
> {
  return useMutation<ReportResult, ApiError, ReportRequestPayload>({
    mutationFn: requestReport,
  });
}
