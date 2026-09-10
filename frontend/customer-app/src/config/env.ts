/**
 * Typed access to Vite environment variables.
 * All browser-visible variables must be prefixed with VITE_.
 */
export const env = {
  /** Base path/URL for backend API calls. Proxied by Vite in local dev. */
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL ?? '/api',
  /**
   * Base URL for realtime streams (Location tracking and Chat) that use
   * Server-Sent Events / WebSocket. Defaults to the API base path so it is
   * proxied by the Vite dev server, but can be pointed at a dedicated gateway
   * origin in production. A leading `/` is treated as same-origin.
   */
  realtimeBaseUrl:
    import.meta.env.VITE_REALTIME_BASE_URL ?? import.meta.env.VITE_API_BASE_URL ?? '/api',
  /**
   * Google OAuth client id used by Google Sign-In. When unset the Google button
   * is disabled: the Auth Service validates the returned ID token's `aud`
   * against its own GOOGLE_CLIENT_ID, so both sides must carry the same value
   * for social login to work at all.
   */
  googleClientId: import.meta.env.VITE_GOOGLE_CLIENT_ID ?? '',
  /** True when running the production build. */
  isProduction: import.meta.env.PROD,
} as const;
