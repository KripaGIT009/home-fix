import { z } from 'zod';
import { normaliseMobile } from './model';

/**
 * Tenant form schema, mirroring provider-service's create/edit validation
 * (Requirement MT-1.2) so a bad value is caught before the round trip:
 * - name: 2–120 characters
 * - latitude within [-90, 90], longitude within [-180, 180]
 * - radius within [1, 100] km, one decimal place at most (numeric(5,1))
 * - at least one category, every one active in the catalog
 * - optional contact phone (E.164 once normalised) and email (≤ 254 characters)
 *
 * Numeric fields are kept as strings in the form: coercing an empty field
 * gives 0, which is a perfectly valid latitude, and would let a forgotten
 * base location through as a point in the Atlantic.
 */

/** A required decimal within [min, max]. */
function boundedNumber(label: string, min: number, max: number) {
  return z
    .string()
    .trim()
    .min(1, `${label} is required.`)
    .refine((value) => Number.isFinite(Number(value)), `${label} must be a number.`)
    .refine(
      (value) => Number(value) >= min && Number(value) <= max,
      `${label} must be between ${min} and ${max}.`,
    );
}

export function tenantSchema(activeCategoryIds: ReadonlySet<string>) {
  return z.object({
    name: z
      .string()
      .trim()
      .min(2, 'Name must be at least 2 characters.')
      .max(120, 'Name must be at most 120 characters.'),
    contactPhone: z
      .string()
      .trim()
      .refine(
        (value) => value === '' || normaliseMobile(value) !== null,
        'Enter a valid mobile number, e.g. 98765 43210 or +919876543210.',
      ),
    contactEmail: z
      .string()
      .trim()
      .max(254, 'Email must be at most 254 characters.')
      .refine(
        (value) => value === '' || z.string().email().safeParse(value).success,
        'Enter a valid email address.',
      ),
    baseLatitude: boundedNumber('Latitude', -90, 90),
    baseLongitude: boundedNumber('Longitude', -180, 180),
    serviceRadiusKm: boundedNumber('Radius', 1, 100).refine(
      (value) => /^\d+(\.\d)?$/.test(value),
      'Use at most one decimal place.',
    ),
    categoryIds: z
      .array(z.string())
      .min(1, 'Choose at least one category.')
      .refine(
        (ids) => ids.every((id) => activeCategoryIds.has(id)),
        'Remove the inactive categories; only active ones can be covered.',
      ),
    status: z.enum(['ACTIVE', 'SUSPENDED']),
  });
}

export type TenantFormValues = z.infer<ReturnType<typeof tenantSchema>>;

/** The add-member form: one mobile number (Requirements MT-2.2, MT-3.2). */
export const mobileSchema = z.object({
  mobileNumber: z
    .string()
    .trim()
    .min(1, 'Enter a mobile number.')
    .refine(
      (value) => normaliseMobile(value) !== null,
      'Enter a valid mobile number, e.g. 98765 43210 or +919876543210.',
    ),
});

export type MobileFormValues = z.infer<typeof mobileSchema>;
