import type { ProviderProfile } from './api';

/**
 * What dispatch needs from the work profile before it can offer a provider a
 * job (Provider Service `EligibilityEvaluator`, Requirement 8.2):
 *
 * - `services` — a saved profile with at least one skill tag. A booking is
 *   offered only to providers sharing one of its service's skill tags, and the
 *   profile does not exist at all until this form is saved.
 * - `serviceArea` — a base location. Distance is measured from it; a provider
 *   without one is skipped outright. (The radius always has a valid value.)
 *
 * The weekly schedule is optional: no schedule reads as "always available".
 * Being verified (APPROVED) and not flagged for review are the other two
 * conditions; verification is tracked by its own feature, and `underReview`
 * is reported here because only an admin can clear it.
 */
export type ProfileStep = 'services' | 'serviceArea';

export interface ProfileCompleteness {
  /** True when nothing on the profile stops dispatch from matching the provider. */
  complete: boolean;
  /** Steps still to do, in the order a provider should do them. */
  missing: ProfileStep[];
  /** Flagged for an admin review: not matched until an admin clears it. */
  underReview: boolean;
}

export function hasBaseLocation(profile: ProviderProfile): boolean {
  return profile.baseLatitude !== null && profile.baseLongitude !== null;
}

export function assessProfile(profile: ProviderProfile | null): ProfileCompleteness {
  if (!profile) {
    return { complete: false, missing: ['services', 'serviceArea'], underReview: false };
  }
  const missing: ProfileStep[] = [];
  if (profile.skillTags.length === 0) missing.push('services');
  if (!hasBaseLocation(profile) || profile.serviceRadiusKm < 1) missing.push('serviceArea');
  return { complete: missing.length === 0, missing, underReview: profile.underReview };
}

/** Where each step is edited. */
export const PROFILE_STEP_ROUTES: Record<ProfileStep, string> = {
  services: '/profile/services',
  serviceArea: '/profile/service-area',
};

export const AVAILABILITY_ROUTE = '/profile/availability';

export const PROFILE_STEP_LABELS: Record<ProfileStep, string> = {
  services: 'Add the services you offer',
  serviceArea: 'Set your base location and travel radius',
};
