import { env } from '@config/env';

/**
 * Minimal binding to Google Identity Services (the `google.accounts.id` SDK).
 *
 * The Auth Service verifies a real Google-issued OIDC ID token: signature against
 * Google's JWKS, `iss` of https://accounts.google.com, and `aud` equal to its own
 * configured client id. So the browser must obtain a genuine ID token — there is
 * nothing to fake — and the SPA's client id must match the Auth Service's
 * `GOOGLE_CLIENT_ID`.
 *
 * The SDK script is loaded on demand rather than from index.html, so an app
 * without a configured client id makes no third-party request at all.
 */

const GIS_SRC = 'https://accounts.google.com/gsi/client';

/** The slice of the GIS surface this module uses. */
interface GoogleIdentityServices {
  accounts: {
    id: {
      initialize(config: {
        client_id: string;
        callback: (response: { credential?: string }) => void;
        cancel_on_tap_outside?: boolean;
      }): void;
      prompt(listener?: (notification: PromptNotification) => void): void;
    };
  };
}

interface PromptNotification {
  isNotDisplayed(): boolean;
  isSkippedMoment(): boolean;
  getNotDisplayedReason(): string;
  getSkippedReason(): string;
}

declare global {
  interface Window {
    google?: GoogleIdentityServices;
  }
}

/** Raised when sign-in cannot start or the user dismisses the Google prompt. */
export class GoogleSignInError extends Error {}

/** True when a client id is configured and Google Sign-In can be attempted. */
export function isGoogleSignInConfigured(): boolean {
  return env.googleClientId.trim().length > 0;
}

let scriptPromise: Promise<GoogleIdentityServices> | null = null;

/** Loads the GIS script once and resolves with the global it installs. */
function loadGoogleIdentityServices(): Promise<GoogleIdentityServices> {
  if (window.google?.accounts?.id) {
    return Promise.resolve(window.google);
  }
  if (scriptPromise) {
    return scriptPromise;
  }

  scriptPromise = new Promise<GoogleIdentityServices>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = GIS_SRC;
    script.async = true;
    script.defer = true;
    script.onload = () => {
      if (window.google?.accounts?.id) {
        resolve(window.google);
      } else {
        reject(new GoogleSignInError('Google Sign-In loaded but is unavailable.'));
      }
    };
    script.onerror = () => {
      // Let a later attempt retry rather than caching the failure forever.
      scriptPromise = null;
      reject(new GoogleSignInError('Could not reach Google Sign-In. Check your connection.'));
    };
    document.head.appendChild(script);
  });

  return scriptPromise;
}

/**
 * Prompts the user with Google Sign-In and resolves with the ID token to post to
 * {@code POST /auth/login/social}.
 *
 * @throws GoogleSignInError when no client id is configured, the SDK cannot load,
 *         or the user dismisses the prompt.
 */
export async function requestGoogleIdentityToken(): Promise<string> {
  if (!isGoogleSignInConfigured()) {
    throw new GoogleSignInError(
      'Google Sign-In is not configured. Set VITE_GOOGLE_CLIENT_ID for this app and the ' +
        'matching GOOGLE_CLIENT_ID for the Auth Service.',
    );
  }

  const google = await loadGoogleIdentityServices();

  return new Promise<string>((resolve, reject) => {
    google.accounts.id.initialize({
      client_id: env.googleClientId,
      cancel_on_tap_outside: true,
      callback: (response) => {
        if (response.credential) {
          resolve(response.credential);
        } else {
          reject(new GoogleSignInError('Google did not return an identity token.'));
        }
      },
    });

    google.accounts.id.prompt((notification) => {
      // The prompt can be suppressed (third-party cookies blocked, previous
      // dismissals); surface that instead of hanging on a promise that never
      // settles.
      if (notification.isNotDisplayed()) {
        reject(
          new GoogleSignInError(
            `Google Sign-In could not be shown (${notification.getNotDisplayedReason()}).`,
          ),
        );
      } else if (notification.isSkippedMoment()) {
        reject(new GoogleSignInError('Google Sign-In was dismissed.'));
      }
    });
  });
}
