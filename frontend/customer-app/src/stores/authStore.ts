import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import {
  registerSessionRefresher,
  registerTokenAccessor,
  registerUnauthorizedHandler,
} from '@api/tokenBridge';
import { refreshSession, revokeRefreshToken } from '@features/auth/api';

export type UserRole =
  | 'CUSTOMER'
  | 'SERVICE_PROVIDER'
  | 'DISPATCHER'
  | 'ADMIN'
  | 'SUPER_ADMIN'
  | 'SUPPORT_AGENT'
  | 'FINANCE_ADMIN';

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
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      accessToken: null,
      refreshToken: null,
      user: null,
      isAuthenticated: false,

      setSession: (tokens, user) =>
        set({
          accessToken: tokens.accessToken,
          refreshToken: tokens.refreshToken,
          user,
          isAuthenticated: true,
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
        } catch {
          // Expired, revoked or replayed refresh token: the session is over.
          get().clearSession();
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
    }),
    {
      name: 'homefix.auth',
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
      onRehydrateStorage: () => (state) => {
        if (!state) return;
        state.isAuthenticated = Boolean(state.refreshToken);
        if (state.refreshToken && !state.accessToken) {
          void state.refreshAccessToken();
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
