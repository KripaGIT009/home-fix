import { z } from 'zod';
import {
  MAX_DESCRIPTION_LENGTH,
  MAX_SCHEDULE_HORIZON_DAYS,
  MAX_SCHEDULE_HORIZON_MS,
  MIN_LEAD_TIME_MS,
} from './constants';

/**
 * Service Request form schema (Requirement 7).
 *
 * Covers the address, scheduled date/time, description, and emergency toggle.
 * Media files are validated separately (see media.ts) because RHF handles them
 * as a controlled File[] outside the resolver.
 */

/** Address sub-schema — either GPS-detected or manually entered. */
export const addressSchema = z.object({
  line1: z.string().trim().min(1, 'Address is required').max(200),
  line2: z.string().trim().max(200).optional().or(z.literal('')),
  city: z.string().trim().min(1, 'City is required').max(100),
  postalCode: z
    .string()
    .trim()
    .regex(/^\d{6}$/, 'Enter a valid 6-digit PIN code'),
  /** Optional GPS coordinates when detected from the device. */
  latitude: z.number().min(-90).max(90).optional(),
  longitude: z.number().min(-180).max(180).optional(),
});

export type AddressFormValues = z.infer<typeof addressSchema>;

/**
 * Validate that a scheduled ISO datetime is within [now + 2h, now + 90d].
 * Emergency bookings bypass scheduling (they dispatch immediately), so this is
 * only enforced for scheduled requests via a superRefine below.
 */
function isValidScheduleWindow(isoDateTime: string): { ok: boolean; message?: string } {
  const scheduled = new Date(isoDateTime).getTime();
  if (Number.isNaN(scheduled)) {
    return { ok: false, message: 'Choose a valid date and time' };
  }
  const now = Date.now();
  if (scheduled < now + MIN_LEAD_TIME_MS) {
    return { ok: false, message: 'Choose a time at least 2 hours from now' };
  }
  if (scheduled > now + MAX_SCHEDULE_HORIZON_MS) {
    return { ok: false, message: `Choose a date within ${MAX_SCHEDULE_HORIZON_DAYS} days` };
  }
  return { ok: true };
}

export const serviceRequestSchema = z
  .object({
    address: addressSchema,
    /** ISO local datetime string from the datetime-local input. */
    scheduledAt: z.string().trim(),
    description: z.string().trim().max(MAX_DESCRIPTION_LENGTH).optional().or(z.literal('')),
    isEmergency: z.boolean(),
  })
  .superRefine((values, ctx) => {
    // Scheduled bookings must satisfy the lead-time / horizon window
    // (Requirements 7.7 and 7.8). Emergency bookings dispatch now, so the
    // scheduled time is not required.
    if (values.isEmergency) {
      return;
    }
    if (!values.scheduledAt) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['scheduledAt'],
        message: 'Choose a date and time',
      });
      return;
    }
    const window = isValidScheduleWindow(values.scheduledAt);
    if (!window.ok) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['scheduledAt'],
        message: window.message ?? 'Invalid schedule',
      });
    }
  });

export type ServiceRequestFormValues = z.infer<typeof serviceRequestSchema>;

/** Coupon code schema for the price-estimate screen (Requirement 6.10). */
export const couponSchema = z.object({
  code: z
    .string()
    .trim()
    .min(3, 'Enter a valid coupon code')
    .max(32)
    .regex(/^[A-Za-z0-9_-]+$/, 'Coupon codes are letters, numbers, - and _'),
});

export type CouponFormValues = z.infer<typeof couponSchema>;
