/**
 * The single HTTP client for the app.
 *
 * Everything the app sends goes through this axios instance:
 *
 *  - **Base URL is resolved per request** from `serverConfig`, not captured at
 *    module load, because the user can change servers from Settings without
 *    restarting the app (readiness gap P4-19). The instance's `baseURL` is
 *    `<server>/api`; every call site passes a `/v1/...` path, which is exactly
 *    the wire shape §1.4/§4 specifies.
 *  - **Bearer access token** on every authenticated call (§1.5 — mobile uses
 *    bearer mode, no `X-Trove-Auth-Mode: cookie` header, and bearer requests
 *    skip CSRF server-side). `X-Trove-Auth-Mode: bearer` and
 *    `X-Requested-With: XMLHttpRequest` go on every request, including the
 *    anonymous `POST /v1/auth/login` where the server has no authenticated
 *    principal to decide the CSRF exemption from.
 *  - **Single-flight refresh with rotation.** Refresh tokens rotate on every
 *    use and a stale token revokes the whole family (§1.5). Two requests
 *    racing a 401 must therefore not both call `/v1/auth/refresh` — the second
 *    would present a token the first already rotated away and force-log-out
 *    the household. One in-flight refresh promise is shared by every waiter,
 *    and each request is retried at most once (`_retried`).
 *  - **Reuse detection → forced logout.** A refresh that is itself rejected
 *    means the token was stale or the family was revoked; there is no recovery
 *    on the client, so the session is cleared and listeners are told.
 *
 * The access token lives in memory only; SecureStore holds the refresh token
 * (readiness gap P4-20). The two are written in one `SecureStore` item so a
 * rotation can never leave half a pair behind.
 */

import axios, {
  AxiosError,
  AxiosHeaders,
  type AxiosInstance,
  type InternalAxiosRequestConfig,
} from 'axios';
import { logError } from '@lib/logError';
import { getBaseUrl } from './serverConfig';
import { readJson, remove, StorageKeys, writeJson } from './secureStorage';
import type { AuthUser, TokenPair } from './types';

declare module 'axios' {
  export interface AxiosRequestConfig {
    /**
     * Set on calls that must never trigger a refresh-and-retry: the auth
     * endpoints themselves, where a 401 is the answer (wrong password,
     * expired challenge), not an expired access token.
     */
    skipAuthRefresh?: boolean;
  }
}

/** Spring's context path (§1.4). Route paths passed to `api` start `/v1/`. */
export const API_CONTEXT_PATH = '/api';

/** Selects the auth mode on `/v1/auth/login` and marks the request thereafter. */
export const AUTH_MODE_HEADER = 'X-Trove-Auth-Mode';
/** Mobile is always bearer mode — it has no cookie jar to rely on (§1.5). */
export const AUTH_MODE = 'bearer';

/**
 * The header `CsrfRequiresHeaderFilter` requires on cookie-authenticated
 * mutations (§1.5 / contract point 9).
 *
 * The server does not require it for bearer calls: the filter only enforces
 * the header when the request authenticated through the cookie
 * (`AuthSource.COOKIE`), and an anonymous bearer-mode `POST /v1/auth/login`
 * carries no `AuthSource` at all. It is sent anyway because it costs nothing
 * and matches what `apps/web` sends on every request.
 */
export const REQUESTED_WITH_HEADER = 'X-Requested-With';
export const REQUESTED_WITH = 'XMLHttpRequest';

/** Thrown when a call is attempted before a server has been connected. */
export class NoServerConnectedError extends Error {
  constructor() {
    super('No Trove server is connected.');
    this.name = 'NoServerConnectedError';
  }
}

export type SessionEndReason =
  /** The refresh token was stale or its family was revoked (§1.5 reuse detection). */
  | 'REFRESH_REJECTED'
  /** A 401 arrived with no refresh token to try. */
  | 'NO_REFRESH_TOKEN'
  /** The server revoked the session while we held a valid-looking token. */
  | 'UNAUTHORIZED';

interface PersistedSession {
  refreshToken: string;
  user: AuthUser;
}

interface RetriableConfig extends InternalAxiosRequestConfig {
  _retried?: boolean;
}

// --- session state ---------------------------------------------------------

let accessToken: string | null = null;
let refreshToken: string | null = null;
let currentUser: AuthUser | null = null;
let refreshInFlight: Promise<string> | null = null;

const sessionEndListeners = new Set<(reason: SessionEndReason) => void>();

/**
 * Subscribe to forced logouts. `authStore` uses this to drop to `anonymous`
 * without `client.ts` having to import the store (which would be a cycle).
 */
export function onSessionEnded(listener: (reason: SessionEndReason) => void): () => void {
  sessionEndListeners.add(listener);
  return () => sessionEndListeners.delete(listener);
}

function isPersistedSession(value: unknown): value is PersistedSession {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Partial<PersistedSession>;
  return typeof candidate.refreshToken === 'string' && typeof candidate.user?.id === 'string';
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function getRefreshToken(): string | null {
  return refreshToken;
}

export function getCurrentUser(): AuthUser | null {
  return currentUser;
}

/**
 * Persist and activate a token pair. One SecureStore write, so a rotation is
 * atomic: there is no window in which the old refresh token has been erased
 * but the new one is not yet stored.
 */
export async function setSession(pair: TokenPair, user: AuthUser): Promise<void> {
  await writeJson(StorageKeys.session, { refreshToken: pair.refreshToken, user });
  accessToken = pair.accessToken;
  refreshToken = pair.refreshToken;
  currentUser = user;
}

/** Update the cached user (e.g. after a display-name change) in place. */
export async function updateSessionUser(user: AuthUser): Promise<void> {
  currentUser = user;
  if (refreshToken) {
    await writeJson(StorageKeys.session, { refreshToken, user });
  }
}

/** Drop every token, in memory and at rest. */
export async function clearSession(): Promise<void> {
  accessToken = null;
  refreshToken = null;
  currentUser = null;
  refreshInFlight = null;
  await remove(StorageKeys.session);
}

/** Load the persisted refresh token and user. Call once on launch. */
export async function hydrateSession(): Promise<PersistedSession | null> {
  const stored = await readJson<PersistedSession>(StorageKeys.session, isPersistedSession);
  if (stored) {
    refreshToken = stored.refreshToken;
    currentUser = stored.user;
  }
  return stored;
}

async function endSession(reason: SessionEndReason): Promise<void> {
  await clearSession();
  for (const listener of sessionEndListeners) {
    listener(reason);
  }
}

/**
 * End the session from a call that does **not** go through the response
 * interceptor below.
 *
 * There is exactly one such call in this app — the Coach completion stream in
 * `coach.ts`, which goes through the `sse.ts` transport (an `XMLHttpRequest`)
 * because axios cannot hand back a body incrementally. It has to reach the
 * same three endings the interceptor reaches (`REFRESH_REJECTED`,
 * `NO_REFRESH_TOKEN`, `UNAUTHORIZED`), or the
 * auth surface behaves differently depending on which transport made the
 * call: a member whose refresh token is gone would sit on an authenticated UI
 * whose every request 401s, because `refreshAccessToken` rejects *before* its
 * try block when there is no token and therefore never ends anything.
 *
 * Exported instead of `endSession` itself so the only thing a caller can do is
 * end the session — not clear it silently, and not fabricate a reason for a
 * call that did go through the interceptor.
 */
export function endSessionOutsideInterceptor(reason: SessionEndReason): Promise<void> {
  return endSession(reason);
}

// --- the instance ----------------------------------------------------------

const DEFAULT_TIMEOUT_MS = 15_000;
/** A camera JPEG or a household export over weak cellular outlasts 15 s (B5-05). */
const TRANSFER_TIMEOUT_MS = 120_000;

export const api: AxiosInstance = axios.create({ timeout: DEFAULT_TIMEOUT_MS });

/**
 * A bare instance for the refresh call itself. Using `api` would put the
 * refresh through the 401 interceptor that is currently running it.
 */
const refreshClient: AxiosInstance = axios.create({ timeout: DEFAULT_TIMEOUT_MS });

function resolveBaseUrl(): string {
  const base = getBaseUrl();
  if (base === null) throw new NoServerConnectedError();
  return `${base}${API_CONTEXT_PATH}`;
}

api.interceptors.request.use((config) => {
  config.baseURL = resolveBaseUrl();
  // Uploads and file downloads get a longer budget unless the caller set one.
  const isTransfer =
    config.data instanceof FormData ||
    config.responseType === 'arraybuffer' ||
    config.responseType === 'text';
  if (isTransfer && config.timeout === DEFAULT_TIMEOUT_MS) config.timeout = TRANSFER_TIMEOUT_MS;
  const headers = AxiosHeaders.from(config.headers);
  // Mobile is bearer-mode, always and explicitly (§1.5). The header selects
  // the mode on `/v1/auth/login`, and on every other route it is what tells
  // the server this request is not cookie-authenticated, so the CSRF filters
  // skip it rather than 403-ing a mutating bearer call.
  headers.set(AUTH_MODE_HEADER, AUTH_MODE);
  headers.set(REQUESTED_WITH_HEADER, REQUESTED_WITH);
  if (accessToken !== null) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }
  config.headers = headers;
  return config;
});

/**
 * Rotate the token pair. At most one call is ever in flight; concurrent
 * callers share the same promise (§1.5 — the server's 30-second reuse-grace
 * window covers races the client cannot coalesce, but the client must not
 * manufacture them).
 */
export function refreshAccessToken(): Promise<string> {
  if (refreshInFlight !== null) return refreshInFlight;

  const current = refreshToken;
  if (current === null) {
    return Promise.reject(new Error('No refresh token'));
  }

  refreshInFlight = (async () => {
    try {
      const response = await refreshClient.post<{ success: boolean; data?: TokenPair }>(
        '/v1/auth/refresh',
        { refreshToken: current },
        {
          baseURL: resolveBaseUrl(),
          headers: {
            [AUTH_MODE_HEADER]: AUTH_MODE,
            [REQUESTED_WITH_HEADER]: REQUESTED_WITH,
          },
        }
      );
      const pair = response.data?.data;
      if (!pair || typeof pair.accessToken !== 'string' || typeof pair.refreshToken !== 'string') {
        throw new Error('Malformed refresh response');
      }
      const user = currentUser;
      if (user === null) throw new Error('No session user to rotate');
      await setSession(pair, user);
      return pair.accessToken;
    } catch (err) {
      // Only a definitive refusal is terminal: the token was stale, already
      // rotated, or its family was revoked by the server's reuse detection.
      // A network error, timeout or 5xx says nothing about the token, so the
      // stored session is kept and the caller can simply retry (B5-01).
      logError('AUTH_REFRESH_FAILED', err);
      const status = axios.isAxiosError(err) ? err.response?.status : undefined;
      if (status === 400 || status === 401 || status === 403) {
        await endSession('REFRESH_REJECTED');
      }
      throw err;
    } finally {
      refreshInFlight = null;
    }
  })();

  return refreshInFlight;
}

api.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    if (!axios.isAxiosError(error)) throw error;
    const axiosError = error as AxiosError;
    const config = axiosError.config as RetriableConfig | undefined;

    if (axiosError.response?.status !== 401 || config === undefined) throw error;
    if (config.skipAuthRefresh === true) throw error;

    if (config._retried === true) {
      // The retry after a successful refresh 401'd as well — the server has
      // revoked this session outright.
      await endSession('UNAUTHORIZED');
      throw error;
    }

    if (refreshToken === null) {
      await endSession('NO_REFRESH_TOKEN');
      throw error;
    }

    config._retried = true;
    let fresh: string;
    try {
      fresh = await refreshAccessToken();
    } catch {
      // `refreshAccessToken` has already cleared the session and notified
      // listeners; surface the original 401 to the caller.
      throw error;
    }

    const headers = AxiosHeaders.from(config.headers);
    headers.set('Authorization', `Bearer ${fresh}`);
    config.headers = headers;
    return api.request(config);
  }
);

/**
 * Test seam: drop in-memory session state without touching SecureStore.
 * Listeners are deliberately left registered — `authStore` subscribes once at
 * import time, and clearing them here would silently unwire the forced-logout
 * path the tests are there to check.
 */
export function __resetClientForTests(): void {
  accessToken = null;
  refreshToken = null;
  currentUser = null;
  refreshInFlight = null;
}
