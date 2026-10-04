import { createBrowserRouter } from 'react-router-dom';
import { PlaceholderScreen } from '@components/PlaceholderScreen';
import { HomeRedirect } from '@components/HomeRedirect';
import { RequireAuth } from '@components/RequireAuth';
import {
  ADMIN_ROLES,
  BOOKING_ROLES,
  DISPATCH_VIEW_ROLES,
  PAYMENT_ROLES,
  REPORT_ROLES,
  SUPER_ADMIN_ROLES,
  SUPPORT_ROLES,
  TENANT_ROLES,
} from '@config/roles';
import { LoginScreen } from '@features/auth/LoginScreen';
import { AcceptInvitationScreen } from '@features/auth/AcceptInvitationScreen';
import { RegisterAgencyScreen } from '@features/agency/RegisterAgencyScreen';
import { AgencyApplicationScreen } from '@features/agency/AgencyApplicationScreen';
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
import { TenantManagementScreen } from '@features/tenants/TenantManagementScreen';
import { RequestsScreen } from '@features/tenant-portal/RequestsScreen';
import { TeamScreen } from '@features/tenant-portal/TeamScreen';
import { JobsScreen } from '@features/tenant-portal/JobsScreen';

/**
 * Admin Portal routes (Requirement 19). Each operational module maps to a
 * sidebar entry in @config/navigation, keeping routing and navigation aligned.
 *
 * Every module is role-gated, not merely authentication-gated: holding a session
 * is not the same as being staff, and a CUSTOMER or SERVICE_PROVIDER token is a
 * perfectly valid session. The role sets live in @config/roles and mirror the
 * sidebar's, so a user never sees a link to a module the guard will refuse:
 * - general modules (incl. the Dashboard): ADMIN + SUPER_ADMIN
 * - Booking Management: + SUPPORT_AGENT + DISPATCHER
 * - Payments & Refunds, Report Generation: + FINANCE_ADMIN
 * - Complaints, Review Moderation: + SUPPORT_AGENT
 * - Dispatch Rules: + DISPATCHER to view; saving is SUPER_ADMIN-only (in-screen)
 * - System Configuration: SUPER_ADMIN only (Requirement 19.6/19.7)
 * - Tenants: ADMIN + SUPER_ADMIN (Requirement MT-12)
 * - Tenant Portal (Requests, Team, Jobs): TENANT_ADMIN only (Requirement MT-11.1);
 *   a TENANT_ADMIN holds none of the platform roles, so every platform module
 *   above answers Forbidden for them, and platform staff — who administer no
 *   Tenant — are kept out of these
 *
 * `/` resolves to the user's landing module (HomeRedirect), so staff without
 * the Dashboard never start on a Forbidden screen. Unauthorized-but-
 * authenticated staff get a Forbidden screen, mirroring the backend's 403,
 * rather than being bounced to login.
 *
 * A session with no staff role at all is an agency applicant's (email-auth
 * Requirement 5.5): every guarded route sends it to `/agency`, its application
 * status page, instead. Three routes stand outside the console:
 * - `/agency/register`: "Register your agency", step 1 — an account (public);
 * - `/agency`: the application form or status (any signed-in account);
 * - `/invite/:token`: accept a staff invitation (public, Requirement 6.3).
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: (
      <RequireAuth>
        <HomeRedirect />
      </RequireAuth>
    ),
  },
  {
    path: '/login',
    element: <LoginScreen />,
  },
  {
    path: '/agency/register',
    element: <RegisterAgencyScreen />,
  },
  {
    path: '/agency',
    element: (
      <RequireAuth>
        <AgencyApplicationScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/invite/:token',
    element: <AcceptInvitationScreen />,
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
    path: '/tenants',
    element: (
      <RequireAuth roles={ADMIN_ROLES}>
        <TenantManagementScreen />
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
      <RequireAuth roles={DISPATCH_VIEW_ROLES}>
        <DispatchRuleScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/bookings',
    element: (
      <RequireAuth roles={BOOKING_ROLES}>
        <BookingManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/payments',
    element: (
      <RequireAuth roles={PAYMENT_ROLES}>
        <PaymentManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/complaints',
    element: (
      <RequireAuth roles={SUPPORT_ROLES}>
        <ComplaintManagementScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/reviews',
    element: (
      <RequireAuth roles={SUPPORT_ROLES}>
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
    path: '/tenant/requests',
    element: (
      <RequireAuth roles={TENANT_ROLES}>
        <RequestsScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/tenant/team',
    element: (
      <RequireAuth roles={TENANT_ROLES}>
        <TeamScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/tenant/jobs',
    element: (
      <RequireAuth roles={TENANT_ROLES}>
        <JobsScreen />
      </RequireAuth>
    ),
  },
  {
    path: '*',
    element: <PlaceholderScreen title="Not found" description="This page does not exist." />,
  },
]);
