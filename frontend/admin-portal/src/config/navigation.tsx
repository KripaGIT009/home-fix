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
import type { UserRole } from '@stores/authStore';

/** A single sidebar navigation entry / operational module (Requirement 19.2). */
export interface NavItem {
  label: string;
  path: string;
  icon: ReactNode;
  /**
   * When set, only users holding one of these roles see the item and may open
   * the route. Undefined means any authenticated admin (ADMIN or SUPER_ADMIN).
   * System Configuration is SUPER_ADMIN-only (Requirement 19.6/19.7).
   */
  roles?: UserRole[];
}

/**
 * The 15 operational modules from Requirement 19.2, plus the Dashboard. This
 * list is the single source of truth for both the sidebar (AppShell) and the
 * router, so navigation and routing never drift apart.
 */
export const NAV_ITEMS: readonly NavItem[] = [
  { label: 'Dashboard', path: '/dashboard', icon: <DashboardRoundedIcon /> },
  { label: 'User Management', path: '/users', icon: <PeopleRoundedIcon /> },
  { label: 'Provider Management', path: '/providers', icon: <EngineeringRoundedIcon /> },
  { label: 'Verification Queue', path: '/verification', icon: <FactCheckRoundedIcon /> },
  { label: 'Service Categories', path: '/categories', icon: <CategoryRoundedIcon /> },
  { label: 'Pricing Configuration', path: '/pricing', icon: <PriceChangeRoundedIcon /> },
  { label: 'Dispatch Rules', path: '/dispatch', icon: <TuneRoundedIcon /> },
  { label: 'Booking Management', path: '/bookings', icon: <EventNoteRoundedIcon /> },
  { label: 'Payments & Refunds', path: '/payments', icon: <PaymentsRoundedIcon /> },
  { label: 'Complaints', path: '/complaints', icon: <ReportProblemRoundedIcon /> },
  { label: 'Review Moderation', path: '/reviews', icon: <RateReviewRoundedIcon /> },
  { label: 'Coupon Management', path: '/coupons', icon: <LocalOfferRoundedIcon /> },
  { label: 'Notification Templates', path: '/notifications', icon: <MarkEmailReadRoundedIcon /> },
  { label: 'Report Generation', path: '/reports', icon: <AssessmentRoundedIcon /> },
  { label: 'Audit Logs', path: '/audit-logs', icon: <HistoryRoundedIcon /> },
  {
    label: 'System Configuration',
    path: '/system-config',
    icon: <SettingsRoundedIcon />,
    roles: ['SUPER_ADMIN'],
  },
] as const;
