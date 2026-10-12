import { isApiError } from '@api/client';
import { toE164 } from '@features/auth/phone';

/**
 * Shapes and rules shared by the platform Tenants module (Requirement MT-12)
 * and the Tenant Portal (Requirement MT-11). Both talk to provider-service's
 * Tenant endpoints, so the JSON shapes below are written once, exactly as
 * design.md specifies them.
 */

/**
 * ACTIVE and SUSPENDED are set by platform admins. PENDING_APPROVAL and
 * REJECTED are an agency's own application (email-auth Requirement 5.2): such a
 * Tenant covers no bookings and has no administrator until it is approved.
 */
export type TenantStatus = 'ACTIVE' | 'SUSPENDED' | 'PENDING_APPROVAL' | 'REJECTED';

/**
 * True for an application not yet made a working Tenant. Its status changes
 * only by approval or rejection, never through the edit form, which the
 * service refuses with 409 APPLICATION_NOT_DECIDED.
 */
export function isApplication(status: TenantStatus): boolean {
  return status === 'PENDING_APPROVAL' || status === 'REJECTED';
}

/** The provider-service `Tenant` payload (`/admin/tenants`, `/tenant/me`). */
export interface Tenant {
  id: string;
  name: string;
  status: TenantStatus;
  contactPhone?: string | null;
  contactEmail?: string | null;
  baseLatitude: number;
  baseLongitude: number;
  serviceRadiusKm: number;
  categoryIds: string[];
  providerCount: number;
  adminCount: number;
  createdAt: string;
  updatedAt: string;
  /** The account that applied, for a self-registered agency (email-auth Requirement 5.2). */
  applicantUserId?: string | null;
  /** Why the application was rejected; shown to the applicant (Requirement 5.4). */
  rejectionReason?: string | null;
}

/**
 * A Provider in a Tenant's team. `assignable` is provider-service's verdict
 * (APPROVED and not under review, Requirement MT glossary "Assignable_Provider");
 * the portal shows it and never re-derives it from `verificationStatus`.
 */
export interface TeamProvider {
  providerId: string;
  displayName?: string | null;
  mobileNumber?: string | null;
  primarySkill?: string | null;
  /** Null when verification-service could not be reached for this Provider. */
  verificationStatus?: string | null;
  rating?: number | null;
  availableNow: boolean;
  assignable: boolean;
}

/**
 * Why a Provider cannot take a job, in words an agency understands. The reason is a hint
 * derived from the verification status; provider-service's `assignable` flag
 * alone decides.
 */
export function notAssignableReason(provider: TeamProvider): string {
  switch (provider.verificationStatus) {
    case 'APPROVED':
      return 'Under review by HomeFix; cannot take jobs until the review ends.';
    case null:
    case undefined:
      return 'Verification status unknown right now; try again shortly.';
    default:
      return 'Not verified yet; documents must be approved before they can take jobs.';
  }
}

/** A Tenant administrator as the members endpoint lists them. */
export interface TenantAdmin {
  userId: string;
  mobileNumber?: string | null;
}

/** `GET /admin/tenants/{id}/members`. */
export interface TenantMembers {
  admins: TenantAdmin[];
  providers: TeamProvider[];
}

/** The E.164 rule the Auth Service applies to every mobile number. */
export const E164_PATTERN = /^\+[1-9]\d{7,14}$/;

/**
 * Normalise a typed mobile number to E.164, or null when it cannot be one.
 * Members are looked up by the number on their account, which is stored in
 * E.164, so "98765 43210" must become "+919876543210" before it is sent.
 */
export function normaliseMobile(input: string): string | null {
  if (!input.trim()) return null;
  const e164 = toE164(input);
  return E164_PATTERN.test(e164) ? e164 : null;
}

/** A Google Maps link that drops a pin on the coordinates. */
export function mapLink(latitude: number, longitude: number): string {
  return `https://www.google.com/maps/search/?api=1&query=${latitude},${longitude}`;
}

/** Coordinates rounded for display: 4 decimals is about 11 m, plenty to orient. */
export function formatCoordinates(latitude: number, longitude: number): string {
  return `${latitude.toFixed(4)}, ${longitude.toFixed(4)}`;
}

/**
 * Plain-language messages for the Tenant error codes (design.md "Error Codes"),
 * so an operator reads what to do rather than a code. Anything else falls back
 * to the service's own message.
 */
const TENANT_ERROR_MESSAGES: Record<string, string> = {
  TENANT_NOT_FOUND: 'This agency no longer exists. Refresh the list.',
  TENANT_SUSPENDED:
    'This agency is suspended, so its portal is read-only. Contact HomeFix support to reactivate it.',
  USER_NOT_FOUND:
    'No HomeFix account uses that mobile number. The person must sign up before they can be made an administrator.',
  ADMIN_OF_OTHER_TENANT: 'That person already administers another agency.',
  PROVIDER_NOT_FOUND:
    'No service provider uses that mobile number. The person must sign up in the HomeFix provider app and create a profile first.',
  PROVIDER_IN_OTHER_TENANT: 'That provider already belongs to another agency.',
  BOOKING_NOT_ASSIGNABLE:
    'This request was just assigned by another partner or has moved on, so it has left the queue.',
  PROVIDER_NOT_ASSIGNABLE:
    'That provider can no longer be assigned (their verification changed or they left the team). Choose someone else.',
  APPLICATION_NOT_DECIDED:
    'This agency is still an application. Approve it from Pending applications first; approval makes the applicant its administrator.',
  APPLICATION_NOT_PENDING:
    'This application has already been decided by someone else. Refresh the list.',
  APPLICATION_EXISTS:
    'This account already has an agency application or runs an agency, so it cannot apply again.',
  REASON_REQUIRED: 'Give a reason; it is emailed to the applicant.',
};

/**
 * The form field each Tenant validation code names (design.md "Error Codes"),
 * so the server's verdict lands under the field it is about.
 */
export const TENANT_FIELD_ERRORS: Record<
  string,
  | 'name'
  | 'contactPhone'
  | 'contactEmail'
  | 'baseLatitude'
  | 'baseLongitude'
  | 'serviceRadiusKm'
  | 'categoryIds'
> = {
  INVALID_TENANT_NAME: 'name',
  INVALID_CONTACT_PHONE: 'contactPhone',
  INVALID_CONTACT_EMAIL: 'contactEmail',
  INVALID_LATITUDE: 'baseLatitude',
  INVALID_LONGITUDE: 'baseLongitude',
  INVALID_SERVICE_RADIUS: 'serviceRadiusKm',
  CATEGORIES_REQUIRED: 'categoryIds',
  INACTIVE_CATEGORY: 'categoryIds',
};

/** The message to show for a failed Tenant call. */
export function tenantErrorMessage(error: unknown): string {
  if (isApiError(error)) {
    return TENANT_ERROR_MESSAGES[error.code] ?? error.message;
  }
  return 'Something went wrong. Please try again.';
}

/** True when the error carries the given errorCode. */
export function hasErrorCode(error: unknown, code: string): boolean {
  return isApiError(error) && error.code === code;
}
