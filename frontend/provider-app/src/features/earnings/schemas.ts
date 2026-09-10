import { z } from 'zod';

/**
 * Settlement request form schema (Requirement 14.2). The amount must be
 * between 1.00 and the available wallet balance, and a verified bank account
 * must be selected. Because the upper bound depends on the current balance, the
 * schema is built via a factory that receives it.
 *
 * The server re-validates both conditions authoritatively (Requirement 14.2);
 * client validation gives immediate, descriptive feedback.
 */
export function buildSettlementSchema(availableBalance: number) {
  return z.object({
    amount: z.coerce
      .number({ invalid_type_error: 'Enter an amount' })
      .min(1, 'Amount must be at least 1.00')
      .max(
        availableBalance,
        `Amount cannot exceed your available balance of ${availableBalance.toFixed(2)}`,
      ),
    bankAccountId: z.string().min(1, 'Select a verified bank account'),
  });
}

export type SettlementFormValues = z.infer<ReturnType<typeof buildSettlementSchema>>;
