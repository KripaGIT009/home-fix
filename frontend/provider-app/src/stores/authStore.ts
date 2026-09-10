import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { registerTokenAccessor, registerUnauthorizedHandler } from '@api/tokenBridge';

export type UserRole =
  | 'CUSTOMER'
  | 'SERVICE_PROVIDER'
  | 'DISPATCHER'
  | 'ADMIN'
  | 'SUPER_ADMIN'
  | 'SUPPORT_AGENT'
  | 'FINANCE_ADMIN';

export interface UserProfile {
  id: string;
  displayName: string;
  mobileNumber: string;
  email?: string;
  photoUrl?: string;
  roles: UserRole[];
  /** Provider profile id, present when the account is a service provider. */
  providerId?: string;
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

      hasRole: (role) => get().user?.roles.includes(role) ?? false,
    }),
    {
      name: 'homefix.provider.auth',
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
      // authenticated when a refresh token was restored.
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
