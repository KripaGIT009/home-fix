/**
 * Presentational grouping of the modules for the sidebar.
 *
 * Sections carry no access meaning whatsoever — every item still declares its
 * own `roles`, and the shell filters by those first and groups second, so a
 * section can never widen access to what it contains. A section whose items are
 * all filtered out renders nothing.
 *
 * Paths are listed rather than tagged onto NavItem so the grouping stays a
 * property of the sidebar's presentation. The trade-off is that a new module
 * must be added here as well as to NAV_ITEMS: a path in no section is filtered
 * out of every section and so never reaches the rail.
 */
export interface NavSection {
  title: string;
  paths: readonly string[];
}

export const NAV_SECTIONS: readonly NavSection[] = [
  { title: 'Overview', paths: ['/dashboard'] },
  { title: 'Marketplace', paths: ['/users', '/providers', '/verification', '/tenants'] },
  { title: 'Catalogue', paths: ['/categories', '/pricing', '/coupons'] },
  { title: 'Operations', paths: ['/bookings', '/dispatch', '/complaints', '/reviews'] },
  { title: 'Finance', paths: ['/payments', '/reports'] },
  { title: 'Platform', paths: ['/notifications', '/audit-logs', '/system-config'] },
  // The Tenant Portal (Requirement MT-11.1). Last, so an account that somehow
  // holds a platform role as well still lands on its platform module; a
  // TENANT_ADMIN sees nothing else, so lands on Requests.
  { title: 'My agency', paths: ['/tenant/requests', '/tenant/team', '/tenant/jobs'] },
];
