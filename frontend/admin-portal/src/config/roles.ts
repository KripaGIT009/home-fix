import type { UserRole } from '@stores/authStore';

/**
 * Role sets that gate the Admin Portal (Requirement 19.6/19.7).
 *
 * The backend is authoritative — every module's API enforces its own roles and
 * answers 403 — but the portal must not route a user into a module it knows
 * they cannot use, and must not let a non-staff account into the shell at all.
 * Each set below mirrors the owning service's RBAC rule for that module, so the
 * sidebar, the route guard and the API agree on who gets in.
 */

/**
 * Every role that may sign in to the Admin Portal. TENANT_ADMIN signs in to the
 * same application but sees only the Tenant Portal modules (Requirement MT-11.1).
 */
export const STAFF_ROLES: readonly UserRole[] = [
  'ADMIN',
  'SUPER_ADMIN',
  'FINANCE_ADMIN',
  'DISPATCHER',
  'SUPPORT_AGENT',
  'TENANT_ADMIN',
];

/**
 * General operational modules: platform administrators only. Covers the
 * dashboard, users, providers, verification, categories, pricing, coupons,
 * notification templates and the audit log.
 */
export const ADMIN_ROLES: readonly UserRole[] = ['ADMIN', 'SUPER_ADMIN'];

/** Booking Management also serves the support desk and dispatch floor. */
export const BOOKING_ROLES: readonly UserRole[] = [
  'ADMIN',
  'SUPER_ADMIN',
  'SUPPORT_AGENT',
  'DISPATCHER',
];

/** Payments & Refunds additionally serves the finance team. */
export const PAYMENT_ROLES: readonly UserRole[] = ['ADMIN', 'SUPER_ADMIN', 'FINANCE_ADMIN'];

/** Complaints and Review Moderation are the support desk's daily work. */
export const SUPPORT_ROLES: readonly UserRole[] = ['ADMIN', 'SUPER_ADMIN', 'SUPPORT_AGENT'];

/** Dispatch Rules may be viewed by dispatchers, who work with their effects. */
export const DISPATCH_VIEW_ROLES: readonly UserRole[] = ['ADMIN', 'SUPER_ADMIN', 'DISPATCHER'];

/**
 * Changing the matching weights is System Configuration: they steer every job
 * offer on the platform, so the Dispatch Engine accepts the PUT from
 * SUPER_ADMIN only.
 */
export const DISPATCH_EDIT_ROLES: readonly UserRole[] = ['SUPER_ADMIN'];

/** Report generation additionally serves the finance team. */
export const REPORT_ROLES: readonly UserRole[] = ['ADMIN', 'SUPER_ADMIN', 'FINANCE_ADMIN'];

/** System Configuration is SUPER_ADMIN-only (Requirement 19.6/19.7). */
export const SUPER_ADMIN_ROLES: readonly UserRole[] = ['SUPER_ADMIN'];

/**
 * The Tenant Portal modules (Requests, Team, Jobs): TENANT_ADMIN only.
 *
 * Platform staff are deliberately absent. These modules are scoped to the
 * caller's own Tenant, which provider-service and booking-service resolve from
 * the caller's identity (Requirement MT-10.1); a platform admin administers no
 * Tenant, so every call would answer 403/404. Platform admins manage Tenants
 * through the Tenants module instead (Requirement MT-12).
 */
export const TENANT_ROLES: readonly UserRole[] = ['TENANT_ADMIN'];

/** True when the account holds any role that belongs in the Admin Portal. */
export function isStaff(roles: readonly UserRole[]): boolean {
  return roles.some((role) => STAFF_ROLES.includes(role));
}

/** True when the account holds at least one of the allowed roles. */
export function hasAnyRole(roles: readonly UserRole[], allowed: readonly UserRole[]): boolean {
  return roles.some((role) => allowed.includes(role));
}
