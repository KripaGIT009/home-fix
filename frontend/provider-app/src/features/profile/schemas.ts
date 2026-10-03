import { z } from 'zod';
import type { BankAccountPayload } from './api';

/** Indian Financial System Code: 4 bank letters, a zero, 6 branch characters. */
export const IFSC_PATTERN = /^[A-Z]{4}0[A-Z0-9]{6}$/;

/** Account numbers may be typed with spaces; only the digits are sent. */
export function stripSpaces(value: string): string {
  return value.replace(/\s+/g, '');
}

/**
 * Bank account form (mirrors the Provider Service's validation, which stays
 * authoritative): holder 2–100 characters, account number 9–18 digits typed
 * twice, IFSC in the standard format after upper-casing.
 */
export const bankAccountSchema = z
  .object({
    accountHolderName: z
      .string()
      .trim()
      .min(2, 'Enter the name on the account (at least 2 characters)')
      .max(100, 'The name can be at most 100 characters'),
    accountNumber: z
      .string()
      .refine((value) => /^\d{9,18}$/.test(stripSpaces(value)), 'Enter 9 to 18 digits'),
    confirmAccountNumber: z.string().min(1, 'Type the account number again'),
    ifsc: z
      .string()
      .refine(
        (value) => IFSC_PATTERN.test(value.trim().toUpperCase()),
        'Enter an 11-character IFSC, e.g. HDFC0001234',
      ),
  })
  .refine(
    (values) => stripSpaces(values.accountNumber) === stripSpaces(values.confirmAccountNumber),
    { message: 'The account numbers do not match', path: ['confirmAccountNumber'] },
  );

export type BankAccountFormValues = z.input<typeof bankAccountSchema>;

/** The request body for validated form values. */
export function toBankAccountPayload(values: BankAccountFormValues): BankAccountPayload {
  return {
    accountHolderName: values.accountHolderName.trim(),
    accountNumber: stripSpaces(values.accountNumber),
    ifsc: values.ifsc.trim().toUpperCase(),
  };
}
