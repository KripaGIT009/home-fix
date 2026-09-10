import { createBrowserRouter } from 'react-router-dom';
import { PlaceholderScreen } from '@components/PlaceholderScreen';
import { ProfileScreen } from '@features/profile/ProfileScreen';
import { RequireAuth } from '@components/RequireAuth';
import { LoginScreen } from '@features/auth/LoginScreen';
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
 * Earnings/settlement history. Profile remains a placeholder.
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
    path: '*',
    element: <PlaceholderScreen title="Not found" description="This page does not exist." />,
  },
]);
