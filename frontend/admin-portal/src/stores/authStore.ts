import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import {
  refreshAccessToken as refreshViaBridge,
  registerSessionRefresher,
  registerTokenAccessor,
  registerUnauthorizedHandler,
} from '@api/tokenBridge';
import { isApiError } from '@api/client';
import { refreshSession, revokeRefreshToken } from '@features/auth/api';

/** errorCode the Auth Service sends for a suspended or deactivated account. */
export const ACCOUNT_DISABLED_CODE = 'ACCOUNT_DISABLED';

export type UserRole =
  | 'CUSTOMER'
  | 'SERVICE_PROVIDER'
  | 'DISPATCHER'
  | 'ADMIN'
  | 'SUPER_ADMIN'
  | 'SUPPORT_AGENT'
  | 'FINANCE_ADMIN'
  /** Administers exactly one Tenant (service agency) — Requirement MT-2. */
  | 'TENANT_ADMIN';

export interface UserProfile {
  /** Account id, from the Auth Service's `userId`. */
  id: string;
  /**
   * Not returned at authentication time — the Auth Service issues tokens, not a
   * profile. Stays unset until a profile fetch fills it in; the UI falls back to
   * a neutral label rather than showing a fabricated name.
   */
  displayName?: string;
  /** Known from the OTP flow (E.164); absent after a social login. */
  mobileNumber?: string;
  email?: string;
  photoUrl?: string;
  roles: UserRole[];
}

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
}

interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  user: UserProfile | null;
  /** Convenience derived flag; kept in sync by the actions below. */
  isAuthenticated: boolean;
  /**
   * True until the persisted session has been rehydrated *and* the silent
   * refresh it triggers has settled.
   *
   * The access token is deliberately memory-only, so immediately after a reload
   * the store knows it is authenticated (it has a refresh token) but holds no
   * access token yet. Without this flag the route guard renders the screen at
   * once, every query fires unauthenticated, 401s, and is retried behind a
   * second refresh -- so each page load burned a wasted request and rotated the
   * single-use refresh token twice.
   */
  isHydrating: boolean;
  /**
   * Why the session ended, when the user did not end it themselves -- e.g. the
   * account was suspended mid-session and the silent refresh answered 403
   * ACCOUNT_DISABLED. Shown once on the login screen; memory-only.
   */
  signOutNotice: string | null;

  /** Persist tokens + profile after a successful login/OTP verification. */
  setSession: (tokens: AuthTokens, user: UserProfile) => void;
  /** Update only the access token, e.g. after a silent refresh. */
  setAccessToken: (accessToken: string) => void;
  /** Merge partial profile updates. */
  updateProfile: (patch: Partial<UserProfile>) => void;
  /** Clear all auth state (logout or 401). */
  clearSession: () => void;
  /**
   * Silent refresh: exchange the persisted refresh token for a new access
   * token. Resolves to the new access token, or to null when the session could
   * not be renewed — in which case it has been cleared.
   */
  refreshAccessToken: () => Promise<string | null>;
  /**
   * Revoke the refresh token server-side, then clear local state. A failed
   * revoke call is ignored; local state is cleared either way.
   */
  logout: () => Promise<void>;
  /** Role check helper. */
  hasRole: (role: UserRole) => boolean;
  /** True when the account has any admin-level role (ADMIN or SUPER_ADMIN). */
  isAdmin: () => boolean;
  /** True only for SUPER_ADMIN — gates System Configuration (Req 19.6/19.7). */
  isSuperAdmin: () => boolean;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      accessToken: null,
      refreshToken: null,
      user: null,
      isAuthenticated: false,
      // Flipped to false by onRehydrateStorage below, which always runs --
      // including when there is nothing stored to rehydrate.
      isHydrating: true,
      signOutNotice: null,

      setSession: (tokens, user) =>
        set({
          accessToken: tokens.accessToken,
          refreshToken: tokens.refreshToken,
          user,
          isAuthenticated: true,
          signOutNotice: null,
        }),

      setAccessToken: (accessToken) => set({ accessToken, isAuthenticated: true }),

      updateProfile: (patch) => {
        const current = get().user;
        if (!current) return;
        set({ user: { ...current, ...patch } });
      },

      clearSession: () =>
        set({
          accessToken: null,
          refreshToken: null,
          user: null,
          isAuthenticated: false,
        }),

      refreshAccessToken: async () => {
        const refreshToken = get().refreshToken;
        if (!refreshToken) {
          get().clearSession();
          return null;
        }
        try {
          const session = await refreshSession({ refreshToken });
          const current = get().user;
          set({
            accessToken: session.accessToken,
            // Refresh tokens are single-use and rotate on every call, so the
            // replacement must be persisted: reusing the old one is treated as
            // a replay and invalidates the whole token family.
            refreshToken: session.refreshToken,
            user: current ? { ...current, roles: session.roles } : current,
            isAuthenticated: true,
          });
          return session.accessToken;
        } catch (error) {
          // Expired, revoked or replayed refresh token, or a disabled account:
          // the session is over either way. A disabled account is the one case
          // worth explaining, or the user just finds themselves signed out.
          get().clearSession();
          if (isApiError(error) && error.code === ACCOUNT_DISABLED_CODE) {
            set({ signOutNotice: error.message });
          }
          return null;
        }
      },

      logout: async () => {
        const refreshToken = get().refreshToken;
        if (refreshToken) {
          try {
            await revokeRefreshToken({ refreshToken });
          } catch {
            // Best effort: a failing revoke must never trap the user in a
            // session they asked to leave.
          }
        }
        get().clearSession();
      },

      hasRole: (role) => get().user?.roles.includes(role) ?? false,

      isAdmin: () => {
        const roles = get().user?.roles ?? [];
        return roles.includes('ADMIN') || roles.includes('SUPER_ADMIN');
      },

      isSuperAdmin: () => get().user?.roles.includes('SUPER_ADMIN') ?? false,
    }),
    {
      name: 'homefix.admin.auth',
      // Token storage strategy (Task 31):
      // - Access token lives in memory only. Short-lived (15 min, Req 1.8) and
      //   kept out of persistent storage to limit XSS token-theft exposure.
      // - Refresh token + profile are persisted so the session survives a
      //   reload; a silent refresh then mints a fresh access token.
      partialize: (state) => ({
        refreshToken: state.refreshToken,
        user: state.user,
      }),
      // On rehydration the access token is absent, so treat the session as
      // authenticated when a refresh token was restored, and immediately mint
      // an access token from it. Without that silent refresh the first API call
      // after a reload goes out with no Authorization header, 401s, and bounces
      // the user back to login despite a perfectly good session.
      // Restoring a refresh token means the session is live even though the
      // access token, being memory-only, is not. The silent refresh that mints
      // one is kicked off by hydrateSession() below rather than here, because a
      // refresh started here calls the store action directly and so bypasses
      // the token bridge's in-flight dedupe. It therefore could not collapse
      // with the 401-driven refresh the first unauthenticated query triggers,
      // and a page load rotated the single-use refresh token twice.
      onRehydrateStorage: () => (state) => {
        if (state) {
          state.isAuthenticated = Boolean(state.refreshToken);
        }
      },
    },
  ),
);

// Wire the store into the API client's token bridge. Reading via getState()
// avoids a React subscription and always returns the current token.
registerTokenAccessor(() => useAuthStore.getState().accessToken);
registerUnauthorizedHandler(() => useAuthStore.getState().clearSession());
registerSessionRefresher(() => useAuthStore.getState().refreshAccessToken());

/**
 * Exchange a rehydrated refresh token for an access token, then release the
 * route guard.
 *
 * Runs after the bridge registrations above, which it depends on: the refresher
 * must be registered before this call, since going through the bridge is what
 * collapses this refresh with any 401-driven one onto a single request. Refresh
 * tokens are single-use with replay detection, so two live refreshes presenting
 * the same token would revoke the family.
 *
 * Every path must clear isHydrating, including the failure paths: a guard left
 * hydrating renders a spinner forever.
 */
function hydrateSession(): void {
  const finish = () => useAuthStore.setState({ isHydrating: false });
  const { refreshToken, accessToken } = useAuthStore.getState();

  if (refreshToken && !accessToken) {
    void refreshViaBridge().finally(finish);
    return;
  }
  finish();
}

// localStorage is synchronous, so hydration has normally already finished by
// the time this module finishes evaluating. onFinishHydration covers the
// asynchronous-storage case; hasHydrated() covers the common one. Guarding on
// both means the refresh is kicked off exactly once either way.
if (useAuthStore.persist.hasHydrated()) {
  hydrateSession();
} else {
  useAuthStore.persist.onFinishHydration(() => hydrateSession());
}
