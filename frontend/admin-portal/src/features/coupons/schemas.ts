import { z } from 'zod';

/**
 * Coupon creation form schema mirroring the server-side constraints in
 * Requirement 21.1 so invalid input is caught before submission:
 * - code: 4–20 alphanumeric characters (validated case-insensitively server-side)
 * - discountValue > 0
 * - minOrderValue ≥ 0
 * - maxDiscountCap required and > 0 when discountType is PERCENTAGE
 * - expiryDate strictly after validFrom
 * - perUserLimit, totalLimit each ≥ 1 (integers)
 */
export const couponSchema = z
  .object({
    code: z
      .string()
      .trim()
      .regex(/^[A-Za-z0-9]{4,20}$/, 'Code must be 4–20 alphanumeric characters.'),
    discountType: z.enum(['FLAT', 'PERCENTAGE']),
    discountValue: z.coerce.number().positive('Discount value must be greater than 0.'),
    minOrderValue: z.coerce.number().min(0, 'Minimum order value cannot be negative.'),
    maxDiscountCap: z.coerce.number().positive().optional(),
    validFrom: z.string().min(1, 'Valid-from date is required.'),
    expiryDate: z.string().min(1, 'Expiry date is required.'),
    perUserLimit: z.coerce.number().int().min(1, 'Per-user limit must be at least 1.'),
    totalLimit: z.coerce.number().int().min(1, 'Total limit must be at least 1.'),
  })
  .refine((value) => value.discountType !== 'PERCENTAGE' || (value.maxDiscountCap ?? 0) > 0, {
    message: 'A maximum discount cap is required for percentage coupons.',
    path: ['maxDiscountCap'],
  })
  .refine((value) => new Date(value.expiryDate).getTime() > new Date(value.validFrom).getTime(), {
    message: 'Expiry date must be after the valid-from date.',
    path: ['expiryDate'],
  });

export type CouponFormValues = z.infer<typeof couponSchema>;
