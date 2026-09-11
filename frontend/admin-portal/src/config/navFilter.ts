import type { UserRole } from '@stores/authStore';
import { NAV_ITEMS, type NavItem } from './navigation';

/**
 * Filter the navigation to the modules a given set of roles may access.
 *
 * Lives in its own module (not navigation.tsx) so the JSX-bearing navigation
 * file only exports components/constants — keeping React Fast Refresh happy.
 * The role sets match the route guards in router.tsx, so a visible link never
 * leads to a Forbidden screen (Requirement 19.6/19.7).
 */
export function visibleNavItems(userRoles: readonly UserRole[]): NavItem[] {
  return NAV_ITEMS.filter((item) => item.roles.some((role) => userRoles.includes(role)));
}
