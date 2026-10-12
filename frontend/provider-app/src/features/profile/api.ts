import { apiClient, isApiError } from '@api/client';
import { toBankAccount, type BankAccount, type BankAccountResponse } from '@features/earnings/api';

/**
 * Provider profile bindings (Provider Service, `/providers/me/...`, and the
 * public Service Catalog listing).
 *
 * - GET /providers/me/profile                — the work profile dispatch matches on
 * - PUT /providers/me/profile                — services, skills, experience, radius, base location
 * - PUT /providers/me/radius                 — service radius only
 * - PUT /providers/me/availability           — weekly availability schedule (replaces it)
 * - PUT /providers/me/emergency-availability — emergency-job opt-in
 * - PUT /providers/me/bank-account           — add or replace the settlement bank account
 * - GET /catalog/categories                  — active categories with their subcategories
 *
 * The profile is created by the first PUT /profile (upsert). Until then
 * GET /profile answers 404 PROVIDER_NOT_FOUND, and the radius, availability
 * and emergency endpoints answer the same 404, so the services form has to be
 * saved first.
 *
 * The bank account is read back through GET /providers/me/settlement-info (see
 * features/earnings/api.ts), which already lists it with its verification state.
 */

// ---------------------------------------------------------------------------
// Profile
// ---------------------------------------------------------------------------

/** java.time.DayOfWeek as the Provider Service serialises it. */
export type DayOfWeek =
  'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';

export const DAYS_OF_WEEK: readonly DayOfWeek[] = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
];

/** A category the provider works in, with the subcategories they take. */
export interface CategorySelection {
  categoryId: string;
  subcategoryIds: string[];
}

/**
 * One weekly availability slot: the half-open hour range `[startHour, endHour)`
 * on `dayOfWeek`, in the provider's local wall-clock time (the Provider
 * Service reads it in Asia/Kolkata). `startHour` is 0–23, `endHour` 1–24.
 */
export interface AvailabilitySlot {
  dayOfWeek: DayOfWeek;
  startHour: number;
  endHour: number;
}

/** Wire shape of ProfileResponse. */
interface ProfileResponse {
  id: string;
  displayName: string | null;
  yearsExperience: number;
  serviceRadiusKm: number;
  baseLatitude: number | null;
  baseLongitude: number | null;
  aggregateRating: number | string | null;
  walletBalance: number | string | null;
  emergencyAvailable: boolean;
  underReview: boolean;
  bankAccountVerified: boolean;
  skillTags: string[] | null;
  categories: { categoryId: string; subcategoryIds: string[] | null }[] | null;
  availability: AvailabilitySlot[] | null;
}

/** The provider's work profile, as the screens use it. */
export interface ProviderProfile {
  id: string;
  displayName: string;
  yearsExperience: number;
  /** Kilometres from the base location the provider will travel (1–100). */
  serviceRadiusKm: number;
  /** Both null until a base service location is set; dispatch skips such providers. */
  baseLatitude: number | null;
  baseLongitude: number | null;
  /** 0–5; 0 until the first review. */
  aggregateRating: number;
  emergencyAvailable: boolean;
  /** Flagged for an admin review (low rating); dispatch skips flagged providers. */
  underReview: boolean;
  /** The tags dispatch matches a booking's required skills against. */
  skillTags: string[];
  categories: CategorySelection[];
  /** Empty means "no schedule": dispatch treats the provider as always available. */
  availability: AvailabilitySlot[];
}

function toNumber(value: number | string | null | undefined): number {
  const parsed = typeof value === 'string' ? Number(value) : value;
  return typeof parsed === 'number' && Number.isFinite(parsed) ? parsed : 0;
}

export function toProviderProfile(response: ProfileResponse): ProviderProfile {
  return {
    id: response.id,
    displayName: response.displayName ?? '',
    yearsExperience: response.yearsExperience,
    serviceRadiusKm: response.serviceRadiusKm,
    baseLatitude: response.baseLatitude ?? null,
    baseLongitude: response.baseLongitude ?? null,
    aggregateRating: toNumber(response.aggregateRating),
    emergencyAvailable: response.emergencyAvailable,
    underReview: response.underReview,
    skillTags: response.skillTags ?? [],
    categories: (response.categories ?? []).map((category) => ({
      categoryId: category.categoryId,
      subcategoryIds: category.subcategoryIds ?? [],
    })),
    availability: response.availability ?? [],
  };
}

/**
 * Body of PUT /providers/me/profile (ProfileRequest). Every field except the
 * base location is replaced on each save. `baseLatitude`/`baseLongitude`
 * travel as a pair; leaving both out keeps the location on file.
 */
export interface ProfilePayload {
  /** At most 100 characters; null when the provider left it empty. */
  displayName: string | null;
  /** At most 5 categories, each with at most 10 subcategories. */
  categories: CategorySelection[];
  /** 1–20 tags. */
  skillTags: string[];
  /** 0–50. */
  yearsExperience: number;
  /** 1–100 km. */
  serviceRadiusKm: number;
  baseLatitude?: number;
  baseLongitude?: number;
}

/**
 * GET /providers/me/profile — the provider's work profile, or `null` when they
 * have not saved one yet (404 PROVIDER_NOT_FOUND), which is the normal state
 * of a provider who has just signed up.
 */
export async function fetchMyProfile(): Promise<ProviderProfile | null> {
  try {
    const { data } = await apiClient.get<ProfileResponse>('/providers/me/profile');
    return toProviderProfile(data);
  } catch (error) {
    if (isApiError(error) && error.status === 404 && error.code === 'PROVIDER_NOT_FOUND') {
      return null;
    }
    throw error;
  }
}

/**
 * PUT /providers/me/profile — create or replace the profile. Rejected with 400
 * VALIDATION_ERROR (counts and ranges), CATEGORY_DEACTIVATED or
 * SUBCATEGORY_DEACTIVATED.
 */
export async function saveProfile(payload: ProfilePayload): Promise<ProviderProfile> {
  const { data } = await apiClient.put<ProfileResponse>('/providers/me/profile', payload);
  return toProviderProfile(data);
}

/** PUT /providers/me/radius — service radius only (1–100 km, else 400 VALIDATION_ERROR). */
export async function saveServiceRadius(serviceRadiusKm: number): Promise<ProviderProfile> {
  const { data } = await apiClient.put<ProfileResponse>('/providers/me/radius', {
    serviceRadiusKm,
  });
  return toProviderProfile(data);
}

/**
 * PUT /providers/me/availability — replace the weekly schedule. An empty list
 * clears it (always available). Overlapping slots on a day are rejected with
 * 400 OVERLAPPING_AVAILABILITY.
 */
export async function saveAvailability(slots: AvailabilitySlot[]): Promise<ProviderProfile> {
  const { data } = await apiClient.put<ProfileResponse>('/providers/me/availability', { slots });
  return toProviderProfile(data);
}

/** PUT /providers/me/emergency-availability — opt in to or out of emergency jobs. */
export async function saveEmergencyAvailability(
  emergencyAvailable: boolean,
): Promise<ProviderProfile> {
  const { data } = await apiClient.put<ProfileResponse>('/providers/me/emergency-availability', {
    emergencyAvailable,
  });
  return toProviderProfile(data);
}

// ---------------------------------------------------------------------------
// Service catalog (public, active entries only)
// ---------------------------------------------------------------------------

/** An active subcategory: a bookable service with the skills it needs. */
export interface CatalogSubcategory {
  id: string;
  categoryId: string;
  name: string;
  /**
   * Skills a provider needs for this service. Dispatch offers a booking to
   * providers who carry at least one of them.
   */
  skillTags: string[];
  emergencyAvailable: boolean;
}

/** An active category with its active subcategories. */
export interface CatalogCategory {
  id: string;
  name: string;
  description: string | null;
  displayOrder: number;
  subcategories: CatalogSubcategory[];
}

interface CatalogCategoryResponse {
  id: string;
  name: string;
  description?: string | null;
  displayOrder: number;
  subcategories?: (Omit<CatalogSubcategory, 'skillTags'> & { skillTags?: string[] | null })[];
}

/** GET /catalog/categories — the active catalog, in the admin-defined display order. */
export async function fetchServiceCatalog(): Promise<CatalogCategory[]> {
  const { data } = await apiClient.get<CatalogCategoryResponse[]>('/catalog/categories');
  return [...data]
    .sort((a, b) => a.displayOrder - b.displayOrder)
    .map((category) => ({
      id: category.id,
      name: category.name,
      description: category.description ?? null,
      displayOrder: category.displayOrder,
      subcategories: (category.subcategories ?? []).map((sub) => ({
        id: sub.id,
        categoryId: sub.categoryId,
        name: sub.name,
        skillTags: sub.skillTags ?? [],
        emergencyAvailable: sub.emergencyAvailable,
      })),
    }));
}

// ---------------------------------------------------------------------------
// Bank account
// ---------------------------------------------------------------------------

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
