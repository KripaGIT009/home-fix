import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import type { BankAccount } from '@features/earnings/api';
import { earningsKeys } from '@features/earnings/hooks';
import { saveBankAccount, type BankAccountPayload } from './api';

/**
 * Add or replace the bank account, then refresh settlement info: it is where
 * both the profile card and the Earnings settlement form read the account.
 */
export function useSaveBankAccount(): UseMutationResult<BankAccount, ApiError, BankAccountPayload> {
  const queryClient = useQueryClient();
  return useMutation<BankAccount, ApiError, BankAccountPayload>({
    mutationFn: saveBankAccount,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: earningsKeys.settlementInfo });
    },
  });
}
