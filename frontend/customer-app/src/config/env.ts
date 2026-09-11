import { Capacitor } from '@capacitor/core';

/**
 * Typed access to Vite environment variables.
 * All browser-visible variables must be prefixed with VITE_.
 */

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? '/api';

export const env = {
  /** Base path/URL for backend API calls. Proxied by Vite in local dev. */
  apiBaseUrl,
  /**
   * Base URL for the Auth Service (OTP, social login, token refresh, logout).
   * Defaults to the API base path, which is how the web builds work: nginx and
   * the Vite dev server both route `/api/auth/**` straight to the Auth Service
   * while everything else goes to the API Gateway. A native build has no such
   * proxy, so it must point this at the Auth Service origin itself.
   */
  authBaseUrl: import.meta.env.VITE_AUTH_BASE_URL ?? apiBaseUrl,
  /**
   * Base URL for realtime streams (Location tracking and Chat) that use
   * Server-Sent Events / WebSocket. Defaults to the API base path so it is
   * proxied by the Vite dev server, but can be pointed at a dedicated gateway
   * origin in production. A leading `/` is treated as same-origin.
   */
  realtimeBaseUrl: import.meta.env.VITE_REALTIME_BASE_URL ?? apiBaseUrl,
  /**
   * Google OAuth client id used by Google Sign-In. When unset the Google button
   * is disabled: the Auth Service validates the returned ID token's `aud`
   * against its own GOOGLE_CLIENT_ID, so both sides must carry the same value
   * for social login to work at all.
   */
  googleClientId: import.meta.env.VITE_GOOGLE_CLIENT_ID ?? '',
  /** True when running the production build. */
  isProduction: import.meta.env.PROD,
  /** True when running inside the Capacitor native shell (Android/iOS). */
  isNative: Capacitor.isNativePlatform(),
} as const;

/** Absolute http(s)/ws(s) URL, as opposed to a same-origin path like "/api". */
function isAbsoluteUrl(value: string): boolean {
  return /^(https?|wss?):\/\//i.test(value);
}

/**
 * Validate the runtime configuration before the app renders.
 *
 * In a browser the base URLs are same-origin paths ("/api") that nginx or the
 * Vite dev server proxies onward. A native build has neither: the shell serves
 * the bundle from its own localhost origin, so "/api/bookings" resolves to the
 * WebView itself and every request fails against a server that does not exist.
 * Nothing about that is obvious from the symptom, so refuse to start and name
 * the variable instead (see frontend/MOBILE.md).
 */
export function assertRuntimeConfig(): void {
  if (!env.isNative) return;

  const offenders = (
    [
      ['VITE_API_BASE_URL', env.apiBaseUrl],
      ['VITE_AUTH_BASE_URL', env.authBaseUrl],
      ['VITE_REALTIME_BASE_URL', env.realtimeBaseUrl],
    ] as const
  ).filter(([, value]) => !isAbsoluteUrl(value));

  if (offenders.length === 0) return;

  const details = offenders.map(([name, value]) => `${name}="${value}"`).join(', ');
  throw new Error(
    `Native build is misconfigured: ${details}. A native app has no reverse proxy, ` +
      `so every base URL must be absolute and reachable from the device — for example ` +
      `http://10.0.2.2:8080 for the API Gateway on an Android emulator's host, or ` +
      `http://192.168.1.x:8080 over LAN for a physical device. Set these in .env, ` +
      `rebuild, and run "npm run mobile:sync". See frontend/MOBILE.md.`,
  );
}
