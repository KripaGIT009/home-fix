import { createBrowserRouter } from 'react-router-dom';
import { PlaceholderScreen } from '@components/PlaceholderScreen';
import { ProfileScreen } from '@features/profile/ProfileScreen';
import { ServicesScreen } from '@features/profile/ServicesScreen';
import { ServiceAreaScreen } from '@features/profile/ServiceAreaScreen';
import { AvailabilityScreen } from '@features/profile/AvailabilityScreen';
import { RequireAuth } from '@components/RequireAuth';
import { LoginScreen } from '@features/auth/LoginScreen';
import { SignUpScreen } from '@features/auth/SignUpScreen';
import { EmailCodeScreen } from '@features/auth/EmailCodeScreen';
import { ForgotPasswordScreen } from '@features/auth/ForgotPasswordScreen';
import { SplashScreen } from '@features/auth/SplashScreen';
import { DashboardScreen } from '@features/dashboard/DashboardScreen';
import { VerificationStatusScreen } from '@features/verification/VerificationStatusScreen';
import { JobRequestScreen } from '@features/jobs/JobRequestScreen';
import { JobDetailsScreen } from '@features/jobs/JobDetailsScreen';
import { ActiveJobScreen } from '@features/jobs/ActiveJobScreen';
import { JobCompletionScreen } from '@features/jobs/JobCompletionScreen';
import { EarningsScreen } from '@features/earnings/EarningsScreen';

/**
 * Provider App routes. Screen set follows Requirement 28.8:
 * Login/OTP, Dashboard (earnings + active jobs), Job Request, Job Details,
 * Active Job, Job Completion, Earnings/settlement history, Profile/Verification.
 *
 * Task 34 implemented Login/OTP, Dashboard, and Verification status. Task 35
 * implements Job Request, Job Details, Active Job, Job Completion, and
 * Earnings/settlement history and the provider's profile. The work-profile
 * editors under /profile/* (services, service area, availability) are what
 * make a newly signed-up provider matchable by dispatch.
 *
 * Email sign-up, its code screen and the forgotten-password flow (email-auth
 * spec) are public, like /login.
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <SplashScreen />,
  },
  {
    path: '/login',
    element: <LoginScreen />,
  },
  {
    path: '/signup',
    element: <SignUpScreen />,
  },
  {
    path: '/signup/verify',
    element: <EmailCodeScreen />,
  },
  {
    path: '/forgot-password',
    element: <ForgotPasswordScreen />,
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
    path: '/verification',
    element: (
      <RequireAuth>
        <VerificationStatusScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/jobs/:bookingId/request',
    element: (
      <RequireAuth>
        <JobRequestScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/jobs/:bookingId',
    element: (
      <RequireAuth>
        <JobDetailsScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/jobs/:bookingId/active',
    element: (
      <RequireAuth>
        <ActiveJobScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/jobs/:bookingId/complete',
    element: (
      <RequireAuth>
        <JobCompletionScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/earnings',
    element: (
      <RequireAuth>
        <EarningsScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/profile',
    element: (
      <RequireAuth>
        <ProfileScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/profile/services',
    element: (
      <RequireAuth>
        <ServicesScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/profile/service-area',
    element: (
      <RequireAuth>
        <ServiceAreaScreen />
      </RequireAuth>
    ),
  },
  {
    path: '/profile/availability',
    element: (
      <RequireAuth>
        <AvailabilityScreen />
      </RequireAuth>
    ),
  },
  {
    path: '*',
    element: <PlaceholderScreen title="Not found" description="This page does not exist." />,
  },
]);
