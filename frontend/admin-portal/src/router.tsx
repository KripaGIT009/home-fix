import { createBrowserRouter, Navigate } from 'react-router-dom';
import { PlaceholderScreen } from '@components/PlaceholderScreen';
import { RequireAuth } from '@components/RequireAuth';
import { ADMIN_ROLES, REPORT_ROLES, SUPER_ADMIN_ROLES } from '@config/roles';
import { LoginScreen } from '@features/auth/LoginScreen';
import { DashboardScreen } from '@features/dashboard/DashboardScreen';
import { UserManagementScreen } from '@features/users/UserManagementScreen';
import { ProviderManagementScreen } from '@features/providers/ProviderManagementScreen';
import { VerificationQueueScreen } from '@features/verification/VerificationQueueScreen';
import { CategoryManagementScreen } from '@features/categories/CategoryManagementScreen';
import { PricingConfigScreen } from '@features/pricing/PricingConfigScreen';
import { DispatchRuleScreen } from '@features/dispatch/DispatchRuleScreen';
import { BookingManagementScreen } from '@features/bookings/BookingManagementScreen';
import { PaymentManagementScreen } from '@features/payments/PaymentManagementScreen';
import { ComplaintManagementScreen } from '@features/complaints/ComplaintManagementScreen';
import { ReviewModerationScreen } from '@features/reviews/ReviewModerationScreen';
import { CouponManagementScreen } from '@features/coupons/CouponManagementScreen';
import { NotificationTemplateScreen } from '@features/notifications/NotificationTemplateScreen';
import { ReportGenerationScreen } from '@features/reports/ReportGenerationScreen';
import { AuditLogScreen } from '@features/audit/AuditLogScreen';
import { SystemConfigScreen } from '@features/system/SystemConfigScreen';

/**
 * Admin Portal routes (Requirement 19). Each operational module maps to a
 * sidebar entry in @config/navigation, keeping routing and navigation aligned.
 *
 * Every module is role-gated, not merely authentication-gated: holding a session
 * is not the same as being staff, and a CUSTOMER or SERVICE_PROVIDER token is a
 * perfectly valid session. The role sets live in @config/roles and mirror the
 * sidebar's, so a user never sees a link to a module the guard will refuse:
 * - general modules: ADMIN + SUPER_ADMIN
 * - Report Generation: ADMIN + SUPER_ADMIN + FINANCE_ADMIN
 * - System Configuration: SUPER_ADMIN only (Requirement 19.6/19.7)
 *
 * Unauthorized-but-authenticated users get a Forbidden screen, mirroring the
 * backend's 403, rather than being bounced to login.
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <Navigate to="/dashboard" replace />,
  },
  {
    path: '/login',
    element: <LoginScreen />,
  },
  {
    path: '/dashboard',
    element: (
      <RequireAuth roles={ADMIN_ROLES}>
        <DashboardScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/users',
    element: (
      <RequireAuth roles={ADMIN_ROLES}>
        <UserManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/providers',
    element: (
      <RequireAuth roles={ADMIN_ROLES}>
        <ProviderManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/verification',
    element: (
      <RequireAuth roles={ADMIN_ROLES}>
        <VerificationQueueScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/categories',
    element: (
      <RequireAuth roles={ADMIN_ROLES}>
        <CategoryManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/pricing',
    element: (
      <RequireAuth roles={ADMIN_ROLES}>
        <PricingConfigScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/dispatch',
    element: (
      <RequireAuth roles={ADMIN_ROLES}>
        <DispatchRuleScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/bookings',
    element: (
      <RequireAuth roles={ADMIN_ROLES}>
        <BookingManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/payments',
    element: (
      <RequireAuth roles={ADMIN_ROLES}>
        <PaymentManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/complaints',
    element: (
      <RequireAuth roles={ADMIN_ROLES}>
        <ComplaintManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/reviews',
    element: (
      <RequireAuth roles={ADMIN_ROLES}>
        <ReviewModerationScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/coupons',
    element: (
      <RequireAuth roles={ADMIN_ROLES}>
        <CouponManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/notifications',
    element: (
      <RequireAuth roles={ADMIN_ROLES}>
        <NotificationTemplateScreen />
      </RequireAuth>
    ),
  },
  {
    // Reports are also the finance team's job, so FINANCE_ADMIN is allowed here.
    path: '/reports',
    element: (
      <RequireAuth roles={REPORT_ROLES}>
        <ReportGenerationScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/audit-logs',
    element: (
      <RequireAuth roles={ADMIN_ROLES}>
        <AuditLogScreen />
      </RequireAuth>
    ),
  },
  {
    // System Configuration is SUPER_ADMIN-only (Requirement 19.6/19.7).
    path: '/system-config',
    element: (
      <RequireAuth roles={SUPER_ADMIN_ROLES}>
        <SystemConfigScreen />
      </RequireAuth>
    ),
  },
  {
    path: '*',
    element: <PlaceholderScreen title="Not found" description="This page does not exist." />,
  },
]);
