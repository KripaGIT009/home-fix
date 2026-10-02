import { Outlet, ScrollRestoration } from 'react-router-dom';

/**
 * Root route element. Restores scroll position on back/forward and starts new
 * screens at the top — without it, opening a service from low on Home would
 * land partway down the booking form.
 */
export function RootLayout() {
  return (
    <>
      <ScrollRestoration />
      <Outlet />
    </>
  );
}
