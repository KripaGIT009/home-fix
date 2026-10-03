import { apiClient } from '@api/client';
import { toBankAccount, type BankAccount, type BankAccountResponse } from '@features/earnings/api';

/**
 * Provider profile bindings.
 *
 * - PUT /providers/me/bank-account — add or replace the settlement bank account
 *
 * The account is read back through GET /providers/me/settlement-info (see
 * features/earnings/api.ts), which already lists it with its verification state.
 */

/** Body of PUT /providers/me/bank-account, already normalised by the form. */
export interface BankAccountPayload {
  accountHolderName: string;
  /** Digits only. */
  accountNumber: string;
  /** Upper-cased. */
  ifsc: string;
}

/**
 * PUT /providers/me/bank-account — store the account (replacing any previous
 * one). It starts unverified unless the Provider Service verifies it at once;
 * settlements can only be paid into a verified account.
 */
export async function saveBankAccount(payload: BankAccountPayload): Promise<BankAccount> {
  const { data } = await apiClient.put<BankAccountResponse>('/providers/me/bank-account', payload);
  return toBankAccount(data);
}
