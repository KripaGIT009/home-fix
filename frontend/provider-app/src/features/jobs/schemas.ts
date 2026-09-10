import { z } from 'zod';

/**
 * Zod schemas for the job-execution forms (Requirement 11).
 */

/** Pause reason: mandatory, 1–500 characters (Requirement 11.5). */
export const pauseReasonSchema = z.object({
  reason: z
    .string()
    .trim()
    .min(1, 'A reason is required to pause the job')
    .max(500, 'Keep the reason under 500 characters'),
});

export type PauseReasonFormValues = z.infer<typeof pauseReasonSchema>;

/**
 * Parts/materials line item: item name required, quantity minimum 1, unit cost
 * minimum 0.01 (Requirement 11.3). Numeric fields are coerced so they can be
 * bound to text inputs.
 */
export const partSchema = z.object({
  itemName: z.string().trim().min(1, 'Enter the item name').max(120, 'Item name is too long'),
  quantity: z.coerce
    .number({ invalid_type_error: 'Enter a quantity' })
    .int('Quantity must be a whole number')
    .min(1, 'Quantity must be at least 1'),
  unitCost: z.coerce
    .number({ invalid_type_error: 'Enter a unit cost' })
    .min(0.01, 'Unit cost must be at least 0.01'),
});

export type PartFormValues = z.infer<typeof partSchema>;
