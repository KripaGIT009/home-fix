import { useQuery } from '@tanstack/react-query';
import type { UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { fetchVerificationState, type VerificationState } from './api';

/** Query key for the Provider's verification state. */
export const verificationKeys = {
  state: ['provider', 'verification'] as const,
};

/** Current verification status + document checklist (Requirement 5.11). */
export function useVerificationState(): UseQueryResult<VerificationState, ApiError> {
  return useQuery<VerificationState, ApiError>({
    queryKey: verificationKeys.state,
    queryFn: fetchVerificationState,
  });
}
