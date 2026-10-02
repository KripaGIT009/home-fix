import type { UserRole } from '@stores/authStore';
import { NAV_ITEMS, type NavItem } from './navigation';
import { NAV_SECTIONS } from './navSections';

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

/**
 * The module a user lands on: the first entry of their sidebar, in the same
 * section-then-item order the AppShell renders. Administrators get the
 * Dashboard; staff who cannot see it (support agents, dispatchers, finance)
 * get their first working module instead of a Forbidden screen. Null when the
 * roles open no module at all.
 */
export function homePathFor(userRoles: readonly UserRole[]): string | null {
  const items = visibleNavItems(userRoles);
  for (const section of NAV_SECTIONS) {
    const first = items.find((item) => section.paths.includes(item.path));
    if (first) return first.path;
  }
  return null;
}

/**
 * Whether the roles may open a path. Only module paths are gated; anything
 * else (e.g. "/" or an unknown path) is left to the router to resolve.
 */
export function canAccessPath(userRoles: readonly UserRole[], path: string): boolean {
  const item = NAV_ITEMS.find((candidate) => candidate.path === path);
  return !item || item.roles.some((role) => userRoles.includes(role));
}
