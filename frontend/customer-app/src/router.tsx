import { createBrowserRouter } from 'react-router-dom';
import { PlaceholderScreen } from '@components/PlaceholderScreen';
import { RequireAuth } from '@components/RequireAuth';
import { RootLayout } from '@components/RootLayout';
import { LoginScreen } from '@features/auth/LoginScreen';
import { SplashScreen } from '@features/auth/SplashScreen';
import { HomeScreen } from '@features/catalog/HomeScreen';
import { SubcategoryScreen } from '@features/catalog/SubcategoryScreen';
import { ServiceRequestScreen } from '@features/booking/ServiceRequestScreen';
import { PriceEstimateScreen } from '@features/booking/PriceEstimateScreen';
import { AvailableProfessionalsScreen } from '@features/tracking/AvailableProfessionalsScreen';
import { LiveTrackingScreen } from '@features/tracking/LiveTrackingScreen';
import { ChatScreen } from '@features/tracking/ChatScreen';
import { ServiceHistoryScreen } from '@features/history/ServiceHistoryScreen';
import { BookingDetailScreen } from '@features/history/BookingDetailScreen';
import { ProfileScreen } from '@features/profile/ProfileScreen';
import { HelpScreen } from '@features/help/HelpScreen';

/**
 * Customer App routes. Screen set follows Requirement 28.7:
 * Splash, Login/OTP, Home, Subcategory selection, Service Request,
 * Price Estimate, Available Professionals, Live Tracking, Service History.
 *
 * Profile and Help back the remaining two bottom-navigation destinations.
 */
export const router = createBrowserRouter([
  {
    element: <RootLayout />,
    children: [
      {
        path: '/',
        element: <SplashScreen />,
      },
      {
        path: '/login',
        element: <LoginScreen />,
      },
      {
        path: '/home',
        element: (
          <RequireAuth>
            <HomeScreen />
          </RequireAuth>
        ),
      },
      {
        path: '/categories/:categoryId',
        element: (
          <RequireAuth>
            <SubcategoryScreen />
          </RequireAuth>
        ),
      },
      {
        path: '/book/:subcategoryId',
        element: (
          <RequireAuth>
            <ServiceRequestScreen />
          </RequireAuth>
        ),
      },
      {
        path: '/book/:subcategoryId/estimate',
        element: (
          <RequireAuth>
            <PriceEstimateScreen />
          </RequireAuth>
        ),
      },
      {
        path: '/book/:bookingId/professionals',
        element: (
          <RequireAuth>
            <AvailableProfessionalsScreen />
          </RequireAuth>
        ),
      },
      {
        path: '/bookings/:bookingId/track',
        element: (
          <RequireAuth>
            <LiveTrackingScreen />
          </RequireAuth>
        ),
      },
      {
        path: '/bookings/:bookingId/chat',
        element: (
          <RequireAuth>
            <ChatScreen />
          </RequireAuth>
        ),
      },
      {
        path: '/bookings/:bookingId',
        element: (
          <RequireAuth>
            <BookingDetailScreen />
          </RequireAuth>
        ),
      },
      {
        path: '/history',
        element: (
          <RequireAuth>
            <ServiceHistoryScreen />
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
        path: '/help',
        element: (
          <RequireAuth>
            <HelpScreen />
          </RequireAuth>
        ),
      },
      {
        path: '*',
        element: (
          <PlaceholderScreen
            title="Page not found"
            description="The page you were looking for doesn’t exist or has moved."
          />
        ),
      },
    ],
  },
]);
