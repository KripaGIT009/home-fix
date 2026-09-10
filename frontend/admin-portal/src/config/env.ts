/**
 * Typed access to Vite environment variables.
 * All browser-visible variables must be prefixed with VITE_.
 */
export const env = {
  /** Base path/URL for backend API calls. Proxied by Vite in local dev. */
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL ?? '/api',
  /** True when running the production build. */
  isProduction: import.meta.env.PROD,
} as const;
