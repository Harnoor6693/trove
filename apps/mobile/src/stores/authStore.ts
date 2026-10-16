/**
 * The app's state machine.
 *
 * Four states, in the order a user moves through them:
 *
 *  - `disconnected` — no Trove server has been paired. Every user runs their
 *    own server, so this, not "logged out", is the true first-launch state
 *    (readiness gap P4-19). Only `app/(auth)/connect.tsx` is reachable.
 *  - `anonymous`   — a server is connected but there is no session.
 *  - `authenticated` — a session is live.
 *  - `locked`      — a session exists but the app lock is engaged: cold start,
 *    or N minutes in the background (readiness gap P4-20). The refresh token
 *    is not used and no data is fetched until the user unlocks.
 *
 * `hydrated` is separate from `status` on purpose. The previous store
 * initialised `isAuthed` to `false` and the root layout guarded on
 * `=== undefined`, so the login screen flashed before the stored session had
 * been read (readiness gap P4-48). Nothing routes until `hydrated` is true.
 *
 * Consumers must select **primitives**, or use `useShallow` — a selector that
 * builds a fresh object on every call re-renders forever under zustand v5,
 * whose default equality is `Object.is` (the bug this store's Settings screen
 * shipped with).
 */

import { create } from 'zustand';
import { logError } from '@lib/logError';
import { queryClient } from '@api/queryClient';
import * as authApi from '@api/auth';
import {
  clearSession,
  getRefreshToken,
  hydrateSession,
  onSessionEnded,
  refreshAccessToken,
  updateSessionUser,
} from '@api/client';
import {
  clearServerConnection,
  getServerConnection,
  hydrateServerConnection,
  setServerConnection,
  type ServerConnection,
} from '@api/serverConfig';
import type { AuthUser, MeResponse, UserPreferences } from '@api/types';

export type AuthStatus = 'disconnected' | 'anonymous' | 'authenticated' | 'locked';

interface AuthState {
  status: AuthStatus;
  /** False until the persisted server and session have been read. */
  hydrated: boolean;
  server: ServerConnection | null;
  user: AuthUser | null;
  me: MeResponse | null;
  preferences: UserPreferences | null;

  hydrate: () => Promise<void>;
  connectServer: (connection: ServerConnection) => Promise<void>;
  disconnectServer: () => Promise<void>;
  onAuthenticated: (user: AuthUser) => void;
  signOut: () => Promise<void>;
  lock: () => void;
  unlock: () => Promise<boolean>;
  setMe: (me: MeResponse) => void;
  setPreferences: (preferences: UserPreferences) => void;
  setUser: (user: AuthUser) => Promise<void>;
}

/**
 * The state a connected-but-signed-out app sits in.
 *
 * Exported because self-deletion (`app/(tabs)/account/delete.tsx`) ends a
 * session the server has already revoked: it has nothing to log out, but it
 * must leave the store in exactly this state — a partial `setState` merges,
 * so setting only `status` would leave the deleted member's `user`, `me` and
 * `preferences` in memory behind the login screen.
 */
export function signedOutState(server: ServerConnection | null) {
  return {
    status: (server === null ? 'disconnected' : 'anonymous') as AuthStatus,
    user: null,
    me: null,
    preferences: null,
  };
}

export const useAuthStore = create<AuthState>()((set, get) => ({
  status: 'disconnected',
  hydrated: false,
  server: null,
  user: null,
  me: null,
  preferences: null,

  hydrate: async () => {
    try {
      const server = await hydrateServerConnection();
      const session = await hydrateSession();

      if (server === null) {
        set({ ...signedOutState(null), server: null, hydrated: true });
        return;
      }
      if (session === null) {
        set({ ...signedOutState(server), server, hydrated: true });
        return;
      }
      // A stored session always starts locked: the app lock gate decides
      // whether an unlock is actually required, and unlocking is what
      // exchanges the refresh token for a live access token.
      set({ status: 'locked', server, user: session.user, hydrated: true });
    } catch (err) {
      logError('AUTH_HYDRATE_FAILED', err);
      set({ ...signedOutState(null), server: null, hydrated: true });
    }
  },

  connectServer: async (connection) => {
    // Pairing with a server invalidates any session held for a different one.
    await clearSession();
    await setServerConnection(connection);
    set({ ...signedOutState(connection), server: getServerConnection() });
  },

  disconnectServer: async () => {
    const refreshToken = getRefreshToken();
    if (refreshToken !== null) {
      try {
        await authApi.logout(refreshToken);
      } catch (err) {
        logError('AUTH_LOGOUT_ON_DISCONNECT_FAILED', err);
      }
    }
    await clearSession();
    await clearServerConnection();
    set({ ...signedOutState(null), server: null });
  },

  onAuthenticated: (user) => {
    set({ status: 'authenticated', user });
  },

  signOut: async () => {
    const refreshToken = getRefreshToken();
    if (refreshToken !== null) {
      try {
        // Server-side revocation of the whole family (§4.1). Local state is
        // cleared either way — a user who taps Sign out must end up signed
        // out even with no connectivity.
        await authApi.logout(refreshToken);
      } catch (err) {
        logError('AUTH_LOGOUT_FAILED', err);
      }
    }
    await clearSession();
    set(signedOutState(get().server));
  },

  lock: () => {
    if (get().status === 'authenticated') set({ status: 'locked' });
  },

  /**
   * Exchange the stored refresh token for a live access token. Returns false
   * when the exchange failed. If the server definitively refused the token
   * (400/401/403) the client has already ended the session and the store is
   * `anonymous`. After a network error, timeout or 5xx the session is kept and
   * the store stays `locked`, so the caller can retry. With no stored refresh
   * token it also returns false and the store stays `locked`. A caller that
   * needs to tell the cases apart reads `status` (and `getRefreshToken()`)
   * after the call, as `AppLock` does for its message.
   */
  unlock: async () => {
    if (get().status !== 'locked') return true;
    try {
      await refreshAccessToken();
      set({ status: 'authenticated' });
      return true;
    } catch (err) {
      logError('AUTH_UNLOCK_REFRESH_FAILED', err);
      return false;
    }
  },

  setMe: (me) => {
    set({ me });
  },

  setPreferences: (preferences) => {
    set({ preferences });
  },

  setUser: async (user) => {
    await updateSessionUser(user);
    set({ user });
  },
}));

/**
 * The client clears the session on a rejected refresh (reuse detection) or an
 * unrecoverable 401; the store follows it down to `anonymous` rather than
 * leaving a signed-in shell over an empty token store.
 */
onSessionEnded(() => {
  const { server } = useAuthStore.getState();
  useAuthStore.setState(signedOutState(server));
});

/**
 * The one place the query cache is emptied (B5-02). Every ending — sign-out,
 * a session the client ended, disconnecting, pairing with a different server —
 * ends in `anonymous` or `disconnected`, or a changed server address. Without
 * this the next member to sign in on the device would be served the previous
 * member's balances from cache.
 */
useAuthStore.subscribe((state, prev) => {
  const ended =
    (state.status === 'anonymous' || state.status === 'disconnected') &&
    prev.status !== state.status;
  if (ended || state.server?.baseUrl !== prev.server?.baseUrl) queryClient.clear();
});
