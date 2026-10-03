import type { ReactNode } from 'react';
import DashboardRoundedIcon from '@mui/icons-material/DashboardRounded';
import PeopleRoundedIcon from '@mui/icons-material/PeopleRounded';
import EngineeringRoundedIcon from '@mui/icons-material/EngineeringRounded';
import FactCheckRoundedIcon from '@mui/icons-material/FactCheckRounded';
import CategoryRoundedIcon from '@mui/icons-material/CategoryRounded';
import PriceChangeRoundedIcon from '@mui/icons-material/PriceChangeRounded';
import TuneRoundedIcon from '@mui/icons-material/TuneRounded';
import EventNoteRoundedIcon from '@mui/icons-material/EventNoteRounded';
import PaymentsRoundedIcon from '@mui/icons-material/PaymentsRounded';
import ReportProblemRoundedIcon from '@mui/icons-material/ReportProblemRounded';
import RateReviewRoundedIcon from '@mui/icons-material/RateReviewRounded';
import LocalOfferRoundedIcon from '@mui/icons-material/LocalOfferRounded';
import MarkEmailReadRoundedIcon from '@mui/icons-material/MarkEmailReadRounded';
import AssessmentRoundedIcon from '@mui/icons-material/AssessmentRounded';
import HistoryRoundedIcon from '@mui/icons-material/HistoryRounded';
import SettingsRoundedIcon from '@mui/icons-material/SettingsRounded';
import ApartmentRoundedIcon from '@mui/icons-material/ApartmentRounded';
import AssignmentIndRoundedIcon from '@mui/icons-material/AssignmentIndRounded';
import GroupsRoundedIcon from '@mui/icons-material/GroupsRounded';
import WorkHistoryRoundedIcon from '@mui/icons-material/WorkHistoryRounded';
import type { UserRole } from '@stores/authStore';
import {
  ADMIN_ROLES,
  BOOKING_ROLES,
  DISPATCH_VIEW_ROLES,
  PAYMENT_ROLES,
  REPORT_ROLES,
  SUPER_ADMIN_ROLES,
  SUPPORT_ROLES,
  TENANT_ROLES,
} from './roles';

/** Live counters a sidebar entry can carry; the shell resolves each one. */
export type NavBadge = 'tenantQueue';

/** A single sidebar navigation entry / operational module (Requirement 19.2). */
export interface NavItem {
  label: string;
  path: string;
  icon: ReactNode;
  /**
   * Roles that may see the item and open the route. Every module sets this and
   * matches the route guard in router.tsx, so the sidebar never offers a link
   * that ends in a Forbidden screen (Requirement 19.6/19.7).
   */
  roles: readonly UserRole[];
  /**
   * Optional live counter shown beside the label, e.g. the number of requests
   * waiting in the Tenant's queue (Requirement MT-11.2).
   */
  badge?: NavBadge;
}

/**
 * The 15 operational modules from Requirement 19.2, plus the Dashboard, the
 * Tenants module (Requirement MT-12) and the Tenant Portal (Requirement MT-11). This
 * list is the single source of truth for both the sidebar (AppShell) and the
 * router, so navigation and routing never drift apart.
 *
 * Order matters beyond looks: within a sidebar section items appear in this
 * order, and a user's landing module is the first one they can see (see
 * homePathFor), which is why Bookings precedes Dispatch Rules — a dispatcher
 * should land on the live booking list, not on a read-only settings page.
 */
export const NAV_ITEMS: readonly NavItem[] = [
  { label: 'Dashboard', path: '/dashboard', icon: <DashboardRoundedIcon />, roles: ADMIN_ROLES },
  { label: 'User Management', path: '/users', icon: <PeopleRoundedIcon />, roles: ADMIN_ROLES },
  {
    label: 'Provider Management',
    path: '/providers',
    icon: <EngineeringRoundedIcon />,
    roles: ADMIN_ROLES,
  },
  {
    label: 'Verification Queue',
    path: '/verification',
    icon: <FactCheckRoundedIcon />,
    roles: ADMIN_ROLES,
  },
  {
    // Agencies that take over bookings automatic matching could not place
    // (Requirement MT-12). Platform administrators only.
    label: 'Tenants',
    path: '/tenants',
    icon: <ApartmentRoundedIcon />,
    roles: ADMIN_ROLES,
  },
  {
    label: 'Service Categories',
    path: '/categories',
    icon: <CategoryRoundedIcon />,
    roles: ADMIN_ROLES,
  },
  {
    label: 'Pricing Configuration',
    path: '/pricing',
    icon: <PriceChangeRoundedIcon />,
    roles: ADMIN_ROLES,
  },
  {
    label: 'Booking Management',
    path: '/bookings',
    icon: <EventNoteRoundedIcon />,
    roles: BOOKING_ROLES,
  },
  {
    label: 'Dispatch Rules',
    path: '/dispatch',
    icon: <TuneRoundedIcon />,
    roles: DISPATCH_VIEW_ROLES,
  },
  {
    label: 'Payments & Refunds',
    path: '/payments',
    icon: <PaymentsRoundedIcon />,
    roles: PAYMENT_ROLES,
  },
  {
    label: 'Complaints',
    path: '/complaints',
    icon: <ReportProblemRoundedIcon />,
    roles: SUPPORT_ROLES,
  },
  {
    label: 'Review Moderation',
    path: '/reviews',
    icon: <RateReviewRoundedIcon />,
    roles: SUPPORT_ROLES,
  },
  {
    label: 'Coupon Management',
    path: '/coupons',
    icon: <LocalOfferRoundedIcon />,
    roles: ADMIN_ROLES,
  },
  {
    label: 'Notification Templates',
    path: '/notifications',
    icon: <MarkEmailReadRoundedIcon />,
    roles: ADMIN_ROLES,
  },
  {
    label: 'Report Generation',
    path: '/reports',
    icon: <AssessmentRoundedIcon />,
    roles: REPORT_ROLES,
  },
  { label: 'Audit Logs', path: '/audit-logs', icon: <HistoryRoundedIcon />, roles: ADMIN_ROLES },
  {
    label: 'System Configuration',
    path: '/system-config',
    icon: <SettingsRoundedIcon />,
    roles: SUPER_ADMIN_ROLES,
  },
  // Tenant Portal (Requirement MT-11.1). Requests comes first: it is the
  // TENANT_ADMIN's landing module (see homePathFor).
  {
    label: 'Requests',
    path: '/tenant/requests',
    icon: <AssignmentIndRoundedIcon />,
    roles: TENANT_ROLES,
    badge: 'tenantQueue',
  },
  { label: 'Team', path: '/tenant/team', icon: <GroupsRoundedIcon />, roles: TENANT_ROLES },
  { label: 'Jobs', path: '/tenant/jobs', icon: <WorkHistoryRoundedIcon />, roles: TENANT_ROLES },
] as const;
