import { z } from 'zod';
import {
  DAYS_OF_WEEK,
  type AvailabilitySlot,
  type BankAccountPayload,
  type CatalogCategory,
  type CategorySelection,
  type DayOfWeek,
  type ProfilePayload,
  type ProviderProfile,
} from './api';

// ---------------------------------------------------------------------------
// Bank account
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Work profile limits
// ---------------------------------------------------------------------------

/**
 * The Provider Service's profile limits (ProfileRequest bean validation and
 * ProviderProperties defaults). The server stays authoritative; mirroring them
 * here only turns a round-trip 400 into an inline message.
 */
export const PROFILE_LIMITS = {
  displayNameMax: 100,
  maxCategories: 5,
  maxSubcategoriesPerCategory: 10,
  minSkillTags: 1,
  maxSkillTags: 20,
  minYearsExperience: 0,
  maxYearsExperience: 50,
  minServiceRadiusKm: 1,
  maxServiceRadiusKm: 100,
} as const;

/** Radius proposed to a provider who has not chosen one yet. */
export const DEFAULT_SERVICE_RADIUS_KM = 10;

// ---------------------------------------------------------------------------
// Services & skills
// ---------------------------------------------------------------------------

/**
 * The categories and skill tags implied by a set of chosen subcategories.
 *
 * Dispatch offers a booking to providers who carry at least one of the
 * booked subcategory's skill tags, so a provider's tags are exactly the union
 * of the tags of the services they offer (deduplicated case-insensitively,
 * as dispatch compares them). Categories come out in catalog order, and ids
 * not in the active catalog are ignored.
 */
export function deriveSelection(
  subcategoryIds: readonly string[],
  catalog: readonly CatalogCategory[],
): { categories: CategorySelection[]; skillTags: string[] } {
  const chosen = new Set(subcategoryIds);
  const categories: CategorySelection[] = [];
  const skillTags: string[] = [];
  const seenTags = new Set<string>();
  for (const category of catalog) {
    const subs = category.subcategories.filter((sub) => chosen.has(sub.id));
    if (subs.length === 0) continue;
    categories.push({ categoryId: category.id, subcategoryIds: subs.map((sub) => sub.id) });
    for (const sub of subs) {
      for (const raw of sub.skillTags) {
        const tag = raw.trim();
        const key = tag.toLowerCase();
        if (tag && !seenTags.has(key)) {
          seenTags.add(key);
          skillTags.push(tag);
        }
      }
    }
  }
  return { categories, skillTags };
}

/** Whole number in a range, typed into a text field. */
function isWholeNumberIn(value: string, min: number, max: number): boolean {
  const trimmed = value.trim();
  if (!/^\d+$/.test(trimmed)) return false;
  const parsed = Number(trimmed);
  return parsed >= min && parsed <= max;
}

/**
 * Services form: name, experience and the services offered. The categories
 * and skill tags sent to the server are derived from the chosen services, so
 * their limits are checked against the catalog the form was built from.
 */
export function buildServicesSchema(catalog: readonly CatalogCategory[]) {
  return z
    .object({
      displayName: z
        .string()
        .trim()
        .max(
          PROFILE_LIMITS.displayNameMax,
          `Your name can be at most ${PROFILE_LIMITS.displayNameMax} characters`,
        ),
      yearsExperience: z
        .string()
        .refine(
          (value) =>
            isWholeNumberIn(
              value,
              PROFILE_LIMITS.minYearsExperience,
              PROFILE_LIMITS.maxYearsExperience,
            ),
          `Enter whole years, from ${PROFILE_LIMITS.minYearsExperience} to ${PROFILE_LIMITS.maxYearsExperience}`,
        ),
      subcategoryIds: z.array(z.string()),
    })
    .superRefine((values, ctx) => {
      const { categories, skillTags } = deriveSelection(values.subcategoryIds, catalog);
      const issue = (message: string) =>
        ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['subcategoryIds'], message });

      if (categories.length === 0) {
        issue('Pick at least one service you offer');
        return;
      }
      if (categories.length > PROFILE_LIMITS.maxCategories) {
        issue(
          `You can offer services in at most ${PROFILE_LIMITS.maxCategories} categories (${categories.length} chosen)`,
        );
        return;
      }
      const crowded = categories.find(
        (category) => category.subcategoryIds.length > PROFILE_LIMITS.maxSubcategoriesPerCategory,
      );
      if (crowded) {
        const name = catalog.find((category) => category.id === crowded.categoryId)?.name;
        issue(
          `Pick at most ${PROFILE_LIMITS.maxSubcategoriesPerCategory} services in ${name ?? 'one category'}`,
        );
        return;
      }
      if (skillTags.length < PROFILE_LIMITS.minSkillTags) {
        issue('These services have no skills listed yet. Pick another service.');
        return;
      }
      if (skillTags.length > PROFILE_LIMITS.maxSkillTags) {
        issue(
          `These services need ${skillTags.length} different skills; a profile can carry at most ${PROFILE_LIMITS.maxSkillTags}. Pick fewer services.`,
        );
      }
    });
}

export type ServicesFormValues = z.input<ReturnType<typeof buildServicesSchema>>;

/**
 * Starting values for the services form. Services no longer in the active
 * catalog are left out (the server would reject them) and counted in
 * `droppedCount` so the screen can say so.
 */
export function servicesFormDefaults(
  profile: ProviderProfile | null,
  catalog: readonly CatalogCategory[],
  fallbackName = '',
): { values: ServicesFormValues; droppedCount: number } {
  const active = new Set(catalog.flatMap((category) => category.subcategories.map((s) => s.id)));
  const saved = profile?.categories.flatMap((category) => category.subcategoryIds) ?? [];
  const subcategoryIds = saved.filter((id) => active.has(id));
  return {
    values: {
      displayName: profile?.displayName || fallbackName,
      yearsExperience: profile ? String(profile.yearsExperience) : '',
      subcategoryIds,
    },
    droppedCount: saved.length - subcategoryIds.length,
  };
}

/**
 * PUT /profile body for validated services values. The radius and base
 * location are not edited here: the radius on file (or the default, for a new
 * profile) is sent back, and the location is left out so the server keeps it.
 */
export function toProfilePayload(
  values: ServicesFormValues,
  catalog: readonly CatalogCategory[],
  current: ProviderProfile | null,
): ProfilePayload {
  const { categories, skillTags } = deriveSelection(values.subcategoryIds, catalog);
  return {
    displayName: values.displayName.trim() || null,
    categories,
    skillTags,
    yearsExperience: Number(values.yearsExperience.trim()),
    serviceRadiusKm: current?.serviceRadiusKm || DEFAULT_SERVICE_RADIUS_KM,
  };
}

// ---------------------------------------------------------------------------
// Service area: base location + radius
// ---------------------------------------------------------------------------

const DECIMAL_PATTERN = /^[-+]?\d{1,3}(\.\d+)?$/;

/** A coordinate typed as a decimal number, or `null` when it is not one. */
export function parseCoordinate(value: string): number | null {
  const trimmed = value.trim();
  if (!DECIMAL_PATTERN.test(trimmed)) return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}

function coordinate(label: string, limit: number) {
  return z
    .string()
    .trim()
    .min(1, `Enter the ${label}`)
    .refine(
      (value) => {
        const parsed = parseCoordinate(value);
        return parsed !== null && parsed >= -limit && parsed <= limit;
      },
      `Enter a ${label} between -${limit} and ${limit}, e.g. ${label === 'latitude' ? '12.9716' : '77.5946'}`,
    );
}

/**
 * Service area form: the base location dispatch measures distance from, and
 * how far from it the provider will travel. Coordinates are kept as typed
 * text so a half-typed value never turns into 0.
 */
export const serviceAreaSchema = z.object({
  latitude: coordinate('latitude', 90),
  longitude: coordinate('longitude', 180),
  serviceRadiusKm: z
    .number({ invalid_type_error: 'Choose a radius' })
    .int('Choose a whole number of kilometres')
    .min(
      PROFILE_LIMITS.minServiceRadiusKm,
      `The radius must be at least ${PROFILE_LIMITS.minServiceRadiusKm} km`,
    )
    .max(
      PROFILE_LIMITS.maxServiceRadiusKm,
      `The radius can be at most ${PROFILE_LIMITS.maxServiceRadiusKm} km`,
    ),
});

export type ServiceAreaFormValues = z.input<typeof serviceAreaSchema>;

/** Coordinates are shown and sent with 6 decimals (about 10 cm), plenty for a base location. */
export function formatCoordinate(value: number): string {
  return value.toFixed(6);
}

export function serviceAreaFormDefaults(profile: ProviderProfile): ServiceAreaFormValues {
  return {
    latitude: profile.baseLatitude === null ? '' : formatCoordinate(profile.baseLatitude),
    longitude: profile.baseLongitude === null ? '' : formatCoordinate(profile.baseLongitude),
    serviceRadiusKm: profile.serviceRadiusKm || DEFAULT_SERVICE_RADIUS_KM,
  };
}

/**
 * The write a validated service area needs. There is no location-only
 * endpoint: a new location goes through PUT /profile, which re-sends the rest
 * of the profile as it is on file. When only the radius changed, the narrower
 * PUT /radius is enough — and it cannot fail on a since-deactivated category.
 */
export type ServiceAreaChange =
  { kind: 'radius'; serviceRadiusKm: number } | { kind: 'profile'; payload: ProfilePayload };

export function toServiceAreaChange(
  values: ServiceAreaFormValues,
  current: ProviderProfile,
): ServiceAreaChange {
  const latitude = Number(formatCoordinate(parseCoordinate(values.latitude) ?? 0));
  const longitude = Number(formatCoordinate(parseCoordinate(values.longitude) ?? 0));
  const unchanged =
    current.baseLatitude !== null &&
    current.baseLongitude !== null &&
    formatCoordinate(current.baseLatitude) === formatCoordinate(latitude) &&
    formatCoordinate(current.baseLongitude) === formatCoordinate(longitude);
  if (unchanged) {
    return { kind: 'radius', serviceRadiusKm: values.serviceRadiusKm };
  }
  return {
    kind: 'profile',
    payload: {
      displayName: current.displayName || null,
      categories: current.categories,
      skillTags: current.skillTags,
      yearsExperience: current.yearsExperience,
      serviceRadiusKm: values.serviceRadiusKm,
      baseLatitude: latitude,
      baseLongitude: longitude,
    },
  };
}

// ---------------------------------------------------------------------------
// Weekly availability
// ---------------------------------------------------------------------------

/** `anytime` sends no slots, which dispatch reads as "always available". */
export type AvailabilityMode = 'anytime' | 'weekly';

const DAY_INDEX: Record<DayOfWeek, number> = Object.fromEntries(
  DAYS_OF_WEEK.map((day, index) => [day, index]),
) as Record<DayOfWeek, number>;

/**
 * Weekly availability form, mirroring the Provider Service's rules: each slot
 * starts at hour 0–23 and ends at 1–24, after it starts, and slots on the same
 * day may touch but not overlap.
 */
export const availabilitySchema = z
  .object({
    mode: z.enum(['anytime', 'weekly']),
    slots: z.array(
      z.object({
        dayOfWeek: z.enum(DAYS_OF_WEEK as [DayOfWeek, ...DayOfWeek[]]),
        startHour: z.number().int().min(0, 'Pick a start time').max(23, 'Pick a start time'),
        endHour: z.number().int().min(1, 'Pick an end time').max(24, 'Pick an end time'),
      }),
    ),
  })
  .superRefine((values, ctx) => {
    // "Any time" sends no slots, so hidden ranges must not block saving it.
    if (values.mode !== 'weekly') return;
    if (values.slots.length === 0) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['slots'],
        message: 'Add at least one time range, or choose "Any time"',
      });
      return;
    }
    values.slots.forEach((slot, index) => {
      if (slot.endHour <= slot.startHour) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['slots', index, 'endHour'],
          message: 'The end time must be after the start time',
        });
        return;
      }
      const clash = values.slots.some(
        (other, otherIndex) =>
          otherIndex < index &&
          other.endHour > other.startHour &&
          other.dayOfWeek === slot.dayOfWeek &&
          slot.startHour < other.endHour &&
          other.startHour < slot.endHour,
      );
      if (clash) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['slots', index, 'startHour'],
          message: 'This overlaps another time range on the same day',
        });
      }
    });
  });

export type AvailabilityFormValues = z.input<typeof availabilitySchema>;

export function availabilityFormDefaults(profile: ProviderProfile): AvailabilityFormValues {
  return {
    mode: profile.availability.length > 0 ? 'weekly' : 'anytime',
    slots: sortSlots(profile.availability),
  };
}

/** Monday first, then by start hour. */
export function sortSlots(slots: readonly AvailabilitySlot[]): AvailabilitySlot[] {
  return [...slots].sort(
    (a, b) => DAY_INDEX[a.dayOfWeek] - DAY_INDEX[b.dayOfWeek] || a.startHour - b.startHour,
  );
}

/** PUT /availability body: no slots for "any time", else the slots in week order. */
export function toAvailabilityPayload(values: AvailabilityFormValues): AvailabilitySlot[] {
  return values.mode === 'anytime' ? [] : sortSlots(values.slots);
}
