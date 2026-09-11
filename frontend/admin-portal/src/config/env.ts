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
   * Defaults to the API base path: nginx and the Vite dev server both route
   * `/api/auth/**` straight to the Auth Service while everything else goes to
   * the API Gateway. Split it out only where there is no such proxy.
   */
  authBaseUrl: import.meta.env.VITE_AUTH_BASE_URL ?? apiBaseUrl,
  /** True when running the production build. */
  isProduction: import.meta.env.PROD,
} as const;
