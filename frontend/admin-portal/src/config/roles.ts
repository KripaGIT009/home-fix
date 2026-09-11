import type { UserRole } from '@stores/authStore';

/**
 * Role sets that gate the Admin Portal (Requirement 19.6/19.7).
 *
 * The backend is authoritative — every module's API enforces its own roles and
 * answers 403 — but the portal must not route a user into a module it knows
 * they cannot use, and must not let a non-staff account into the shell at all.
 */

/** Every role that may sign in to the Admin Portal. */
export const STAFF_ROLES: readonly UserRole[] = [
  'ADMIN',
  'SUPER_ADMIN',
  'FINANCE_ADMIN',
  'DISPATCHER',
  'SUPPORT_AGENT',
];

/** General operational modules: platform administrators only. */
export const ADMIN_ROLES: readonly UserRole[] = ['ADMIN', 'SUPER_ADMIN'];

/** Report generation additionally serves the finance team. */
export const REPORT_ROLES: readonly UserRole[] = ['ADMIN', 'SUPER_ADMIN', 'FINANCE_ADMIN'];

/** System Configuration is SUPER_ADMIN-only (Requirement 19.6/19.7). */
export const SUPER_ADMIN_ROLES: readonly UserRole[] = ['SUPER_ADMIN'];

/** True when the account holds any role that belongs in the Admin Portal. */
export function isStaff(roles: readonly UserRole[]): boolean {
  return roles.some((role) => STAFF_ROLES.includes(role));
}
