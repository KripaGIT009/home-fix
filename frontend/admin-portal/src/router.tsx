import { createBrowserRouter, Navigate } from 'react-router-dom';
import { PlaceholderScreen } from '@components/PlaceholderScreen';
import { RequireAuth } from '@components/RequireAuth';
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
 * All module routes require authentication via RequireAuth. System Configuration
 * additionally requires the SUPER_ADMIN role (Requirement 19.6/19.7): ADMIN users
 * neither see it in the sidebar nor can navigate to it — the guard renders a
 * Forbidden screen, mirroring the backend's 403.
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
      <RequireAuth>
        <DashboardScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/users',
    element: (
      <RequireAuth>
        <UserManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/providers',
    element: (
      <RequireAuth>
        <ProviderManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/verification',
    element: (
      <RequireAuth>
        <VerificationQueueScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/categories',
    element: (
      <RequireAuth>
        <CategoryManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/pricing',
    element: (
      <RequireAuth>
        <PricingConfigScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/dispatch',
    element: (
      <RequireAuth>
        <DispatchRuleScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/bookings',
    element: (
      <RequireAuth>
        <BookingManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/payments',
    element: (
      <RequireAuth>
        <PaymentManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/complaints',
    element: (
      <RequireAuth>
        <ComplaintManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/reviews',
    element: (
      <RequireAuth>
        <ReviewModerationScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/coupons',
    element: (
      <RequireAuth>
        <CouponManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/notifications',
    element: (
      <RequireAuth>
        <NotificationTemplateScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/reports',
    element: (
      <RequireAuth>
        <ReportGenerationScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/audit-logs',
    element: (
      <RequireAuth>
        <AuditLogScreen />
      </RequireAuth>
    ),
  },
  {
    // System Configuration is SUPER_ADMIN-only (Requirement 19.6/19.7).
    path: '/system-config',
    element: (
      <RequireAuth roles={['SUPER_ADMIN']}>
        <SystemConfigScreen />
      </RequireAuth>
    ),
  },
  {
    path: '*',
    element: <PlaceholderScreen title="Not found" description="This page does not exist." />,
  },
]);
