import type { CapacitorConfig } from '@capacitor/cli';

/**
 * Capacitor wraps the existing Vite build as a native app: `webDir` is the
 * production bundle, which the native shell serves locally over its own
 * scheme. See frontend/MOBILE.md for the build/run workflow.
 *
 * Note there is no proxy inside the native shell, so the API base URLs must be
 * absolute — src/config/env.ts refuses to start a native build otherwise.
 */
const config: CapacitorConfig = {
  appId: 'com.homefix.provider',
  appName: 'HomeFix Pro',
  webDir: 'dist',
};

export default config;
