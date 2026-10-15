/**
 * The one HTTP client. Everything the SPA sends to `trove-core` goes through here.
 *
 * Contract, from `docs/recovery/06-COLLAPSE-PLAN.md`:
 *  - §1.4 — `server.servlet.context-path: /api`, controllers keep `/v1/…`, so the
 *    wire path is `/api/v1/…` and `baseURL` is `/api`. Same origin: Traefik routes
 *    `Host(domain) && PathPrefix('/api')` to `trove-core` and everything else to the
 *    SPA (`deploy/docker-compose.yml`), so `connect-src 'self'` holds and the
 *    `__Host-` session cookies stick to one origin.
 *  - §1.5 — cookie session (`__Host-trove_access` / `__Host-trove_refresh`,
 *    HttpOnly) plus BOTH CSRF defences: the `X-Requested-With: XMLHttpRequest`
 *    header that `CsrfRequiresHeaderFilter` demands of cookie-authenticated
 *    mutations, and the double-submit pair `XSRF-TOKEN` cookie → `X-XSRF-TOKEN`
 *    header that Spring's `CookieCsrfTokenRepository.withHttpOnlyFalse()` +
 *    `SpaCsrfTokenRequestHandler` expects (§5 item 10). The cookie is materialised
 *    for anonymous callers by `CsrfCookieFilter`, so it is present before the very
 *    first login POST.
 *  - §5.4 — the error body is `ApiResponse.error`, an RFC 7807 problem-details
 *    record with camelCase `fieldErrors`. `ApiError` below is that body, typed.
 *
 * Defect I4 (see `docs/recovery/inventory/web.md` §2, §10) is fixed here: a
 * brand-new anonymous visitor's `GET /v1/auth/me` must NOT trigger a refresh, and
 * must NOT hard-redirect to `/login?error=expired`. An anonymous 401 is not an
 * error. Callers opt out of the refresh dance with `skipAuthRefresh: true`; the
 * hydrate probe in `authStore` sets it.
 */
import axios, {
  AxiosError,
  AxiosHeaders,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import type { ApiResponse, ErrorResponse, FieldErrorItem } from './types'

declare module 'axios' {
  export interface AxiosRequestConfig {
    /**
     * Never attempt a token refresh for this request, and never treat its 401 as
     * an expired session. Set on the session-hydration probe and on every
     * `/v1/auth/*` call that is itself part of establishing a session.
     */
    skipAuthRefresh?: boolean
    /** Internal: set once a request has already been replayed after a refresh. */
    _retried?: boolean
  }
}

export const CSRF_COOKIE_NAME = 'XSRF-TOKEN'
export const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'

/** Paths that must never trigger a refresh: they either mint or destroy the session. */
const NEVER_REFRESH = [
  '/v1/auth/login',
  '/v1/auth/login/totp',
  '/v1/auth/register',
  '/v1/auth/refresh',
  '/v1/auth/logout',
  '/v1/auth/providers',
  '/v1/status',
  '/v1/health',
]

export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
  xsrfCookieName: CSRF_COOKIE_NAME,
  xsrfHeaderName: CSRF_HEADER_NAME,
  headers: {
    'Content-Type': 'application/json',
  },
})

/* --------------------------------------------------------------- ApiError */

/**
 * A `trove-core` failure, typed from the §5.4 error body.
 *
 * Every rejection out of `api` is one of these — call sites never unwrap an
 * `AxiosError` again, and never read `error.field_errors`.
 */
export class ApiError extends Error {
  readonly status: number
  /** Problem-details URI, e.g. `https://trove.finance/errors/not-found`. */
  readonly type: string | null
  readonly title: string | null
  readonly detail: string | null
  readonly requestId: string | null
  readonly fieldErrors: FieldErrorItem[]
  /** True when no response arrived at all (DNS, TLS, offline, CORS). */
  readonly isNetworkError: boolean

  constructor(init: {
    status: number
    type?: string | null
    title?: string | null
    detail?: string | null
    requestId?: string | null
    fieldErrors?: FieldErrorItem[]
    isNetworkError?: boolean
    message: string
  }) {
    super(init.message)
    this.name = 'ApiError'
    this.status = init.status
    this.type = init.type ?? null
    this.title = init.title ?? null
    this.detail = init.detail ?? null
    this.requestId = init.requestId ?? null
    this.fieldErrors = init.fieldErrors ?? []
    this.isNetworkError = init.isNetworkError ?? false
  }

  /** The trailing slug of `type`, e.g. `not-found`. Null when the server sent none. */
  get code(): string | null {
    if (!this.type) return null
    const slash = this.type.lastIndexOf('/')
    return slash === -1 ? this.type : this.type.slice(slash + 1)
  }

  /** The first validation message for one field, for inline form errors. */
  fieldError(field: string): string | undefined {
    return this.fieldErrors.find((f) => f.field === field)?.message
  }
}

function errorBodyOf(data: unknown): ErrorResponse | null {
  if (!data || typeof data !== 'object') return null
  const envelope = data as ApiResponse<unknown>
  if (envelope.error && typeof envelope.error === 'object') return envelope.error
  // A bare problem-details body (an entry point that wrote one without the envelope).
  const bare = data as ErrorResponse
  if (typeof bare.status === 'number' && typeof bare.title === 'string') return bare
  return null
}

/** Normalises anything thrown by axios into an `ApiError`. */
export function toApiError(err: unknown): ApiError {
  if (err instanceof ApiError) return err
  if (axios.isAxiosError(err)) {
    const axiosErr = err as AxiosError
    const status = axiosErr.response?.status ?? 0
    const body = errorBodyOf(axiosErr.response?.data)
    return new ApiError({
      status,
      type: body?.type,
      title: body?.title,
      detail: body?.detail,
      requestId:
        body?.requestId ??
        (axiosErr.response?.headers as Record<string, string> | undefined)?.['x-request-id'] ??
        null,
      fieldErrors: body?.fieldErrors ?? [],
      isNetworkError: axiosErr.response === undefined,
      message: body?.detail ?? body?.title ?? axiosErr.message,
    })
  }
  return new ApiError({
    status: 0,
    isNetworkError: true,
    message: err instanceof Error ? err.message : String(err),
  })
}

/* ------------------------------------------------------- session handover */

type SessionExpiredHandler = () => void

function defaultSessionExpired() {
  if (typeof window === 'undefined') return
  if (window.location.pathname === '/login') return
  window.location.assign('/login?error=expired')
}

let onSessionExpired: SessionExpiredHandler = defaultSessionExpired

/**
 * Registered by `authStore` so this module never imports the store (which imports
 * this module). Fired only when a session that DID exist has gone: a refresh was
 * attempted and failed. Never fired for an anonymous 401.
 */
export function setSessionExpiredHandler(handler: SessionExpiredHandler | null): void {
  onSessionExpired = handler ?? defaultSessionExpired
}

/* ------------------------------------------------------- request pipeline */

function readCookie(name: string): string | null {
  if (typeof document === 'undefined') return null
  for (const part of document.cookie.split(';')) {
    const eq = part.indexOf('=')
    if (eq === -1) continue
    if (part.slice(0, eq).trim() === name) return decodeURIComponent(part.slice(eq + 1))
  }
  return null
}

api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const headers = AxiosHeaders.from(config.headers)
  // Demanded by `CsrfRequiresHeaderFilter` for every cookie-authenticated mutation.
  headers.set('X-Requested-With', 'XMLHttpRequest')
  // Double-submit half. Set by hand rather than relying on axios' built-in xsrf
  // support so it is observable in tests and identical in every environment.
  const token = readCookie(CSRF_COOKIE_NAME)
  if (token && !headers.has(CSRF_HEADER_NAME)) headers.set(CSRF_HEADER_NAME, token)
  config.headers = headers
  return config
})

/* ------------------------------------------------------ single-flight refresh */

let refreshInFlight: Promise<void> | null = null

/**
 * §4.13: two tabs racing a 401 must not both rotate the refresh token — the
 * second rotation is a reuse of a stale token and family-revokes the session.
 * `navigator.locks` serialises across tabs; the module-level promise serialises
 * within one tab. Where `navigator.locks` is absent (jsdom, older Safari) the
 * server's 30-second reuse-grace window (§1.5) is the remaining safety net.
 */
async function withCrossTabLock<T>(fn: () => Promise<T>): Promise<T> {
  const locks = (globalThis.navigator as Navigator | undefined)?.locks
  if (!locks || typeof locks.request !== 'function') return fn()
  return locks.request('trove-token-refresh', fn) as Promise<T>
}

function refreshSession(): Promise<void> {
  if (refreshInFlight) return refreshInFlight
  const started = withCrossTabLock(async () => {
    await api.post('/v1/auth/refresh', null, { skipAuthRefresh: true })
  })
  refreshInFlight = started
  const clear = () => {
    if (refreshInFlight === started) refreshInFlight = null
  }
  void started.then(clear, clear)
  return started
}

/** Test seam: drop any in-flight refresh so cases do not leak into each other. */
export function __resetRefreshState(): void {
  refreshInFlight = null
}

function neverRefreshes(config: InternalAxiosRequestConfig): boolean {
  if (config.skipAuthRefresh) return true
  const url = config.url ?? ''
  return NEVER_REFRESH.some((p) => url.startsWith(p) || url.includes(p))
}

api.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    if (!axios.isAxiosError(error)) return Promise.reject(toApiError(error))
    const original = error.config as InternalAxiosRequestConfig | undefined
    const status = error.response?.status

    if (!original || status !== 401) return Promise.reject(toApiError(error))

    // The I4 fix. An anonymous `/v1/auth/me` (or any opted-out call) that 401s
    // means "no session was ever established". There is nothing to refresh and
    // nothing has expired, so no refresh fires and no redirect happens.
    if (neverRefreshes(original)) return Promise.reject(toApiError(error))

    // A 401 on the replay means the refreshed session is still not good enough.
    if (original._retried) {
      onSessionExpired()
      return Promise.reject(toApiError(error))
    }

    original._retried = true
    try {
      await refreshSession()
    } catch {
      onSessionExpired()
      return Promise.reject(toApiError(error))
    }
    try {
      return await api(original)
    } catch (replayError) {
      return Promise.reject(toApiError(replayError))
    }
  }
)

/* ---------------------------------------------------------------- unwrap */

/**
 * Peels the §5.3 `ApiResponse` envelope. A 2xx that carries `success: false`, or
 * one with no `data`, is a server contract violation and is raised as such rather
 * than handed to a component as `undefined`.
 */
export function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  const body = res.data
  if (body && typeof body === 'object' && 'success' in body) {
    if (!body.success) {
      throw new ApiError({
        status: body.error?.status ?? res.status,
        type: body.error?.type,
        title: body.error?.title,
        detail: body.error?.detail,
        requestId: body.error?.requestId,
        fieldErrors: body.error?.fieldErrors ?? [],
        message: body.error?.detail ?? body.error?.title ?? 'Request failed',
      })
    }
    if (body.data === undefined || body.data === null) {
      throw new ApiError({
        status: res.status,
        message: 'Server returned a success envelope with no data',
      })
    }
    return body.data
  }
  throw new ApiError({
    status: res.status,
    message: 'Server response was not the expected ApiResponse envelope',
  })
}

/** As `unwrap`, but tolerates the deliberate empty success (`ApiResponse.ok(null)`). */
export function unwrapVoid(res: AxiosResponse<ApiResponse<unknown>>): void {
  const body = res.data
  if (body && typeof body === 'object' && 'success' in body && !body.success) {
    throw new ApiError({
      status: body.error?.status ?? res.status,
      type: body.error?.type,
      title: body.error?.title,
      detail: body.error?.detail,
      requestId: body.error?.requestId,
      fieldErrors: body.error?.fieldErrors ?? [],
      message: body.error?.detail ?? body.error?.title ?? 'Request failed',
    })
  }
}

/* --------------------------------------------------- the fetch twin (step 204) */

/**
 * `sessionFetch` and its two helpers exist because two response bodies on this
 * API are not JSON and cannot travel through axios' JSON pipeline:
 *
 *  - `POST /v1/chat/completions?stream=true` answers `text/event-stream`, written
 *    frame by frame. Axios buffers a response body; `EventSource` cannot POST,
 *    cannot send `X-Requested-With` or the CSRF header, and cannot carry a
 *    request body at all. The reader in `./sse.ts` therefore needs `fetch` and a
 *    `ReadableStream` reader.
 *  - `GET /v1/receipts/{id}/download` and `/thumbnail` answer `image/jpeg` behind
 *    the session. They are NOT an `<img src>`: the bytes need the caller's own
 *    credentials, so `./binary.ts` fetches them and hands back a Blob.
 *
 * This is the ONE place that duplication is allowed to live. It carries the same
 * two CSRF defences the axios request interceptor sets, the same
 * `credentials: 'include'`, and — the part that must not be re-implemented — the
 * SAME single-flight refresh, by delegating to the module-level `refreshSession`
 * above rather than posting `/v1/auth/refresh` itself. Two refreshes racing is a
 * token-reuse family revocation (§4.13), and a second implementation of the
 * dance is exactly how that happens.
 *
 * Families C (tax export) and E (data export) reuse `sessionFetch` for their own
 * non-JSON bodies; nothing else in the app should call `fetch` directly.
 */

/** The wire path for a `/v1/…` route, matching `api`'s `baseURL`. */
export function apiPath(path: string): string {
  const base = api.defaults.baseURL ?? ''
  return `${base}${path}`
}

/**
 * The headers the axios request interceptor sets, for a `fetch` that cannot go
 * through it. `X-Requested-With` is what `CsrfRequiresHeaderFilter` demands of a
 * cookie-authenticated mutation; `X-XSRF-TOKEN` is the double-submit half. Both
 * are sent on reads too, exactly as the interceptor does — the server ignores
 * them on a GET and a client that sets them conditionally is a client with two
 * code paths to get wrong.
 */
export function sessionHeaders(extra: Record<string, string> = {}): Record<string, string> {
  // `X-Requested-With` is written AFTER the caller's own headers, on purpose: no
  // caller — including families C and E, who are told to reuse this seam — can
  // replace it, and losing it turns a mutation into a 403 with no local signal.
  const headers: Record<string, string> = { ...extra, 'X-Requested-With': 'XMLHttpRequest' }
  const token = readCookie(CSRF_COOKIE_NAME)
  if (token && !(CSRF_HEADER_NAME in headers)) headers[CSRF_HEADER_NAME] = token
  return headers
}

/**
 * Normalises a non-2xx `fetch` Response into the same `ApiError` every axios call
 * site already handles, so a screen has one error type whatever transport the
 * call used. The body is the ordinary `ApiResponse.error` problem-details shape;
 * a body that is not JSON at all (a proxy's HTML error page) still produces an
 * `ApiError` carrying the status.
 */
export async function apiErrorFromResponse(res: Response): Promise<ApiError> {
  let body: ErrorResponse | null = null
  try {
    body = errorBodyOf(await res.json())
  } catch {
    body = null
  }
  return new ApiError({
    status: res.status,
    type: body?.type,
    title: body?.title,
    detail: body?.detail,
    requestId: body?.requestId ?? res.headers.get('x-request-id'),
    fieldErrors: body?.fieldErrors ?? [],
    message: body?.detail ?? body?.title ?? `Request failed with status ${res.status}`,
  })
}

/**
 * The one `fetch` call site in the app, read off the global at call time so the
 * test environment's interceptor is the `fetch` that is actually used.
 */
function globalFetch(input: string, init: RequestInit): Promise<Response> {
  return fetch(input, init)
}

export interface SessionFetchInit extends Omit<RequestInit, 'credentials' | 'headers'> {
  /**
   * Deliberately NARROWER than `RequestInit['headers']`. `sessionHeaders` spreads
   * what it is given, and a `Headers` instance or an array of `[name, value]`
   * pairs — both valid on a plain `RequestInit` — would spread to nothing and be
   * silently dropped. A plain record makes the other two shapes a compile error
   * instead of a request that quietly loses its headers.
   */
  headers?: Record<string, string>
  /** As on an axios config: never refresh, and never treat a 401 as an expiry. */
  skipAuthRefresh?: boolean
}

/**
 * One request to `trove-core` over `fetch`, through the session.
 *
 * On a 401 it runs the shipped single-flight refresh once and replays, exactly
 * as the axios response interceptor does, and hands the session over to
 * `onSessionExpired` when the refresh itself fails. A non-2xx response is
 * REJECTED with an `ApiError` — callers get a Response only when there is a body
 * worth reading, which is what lets the SSE reader assume `res.body` exists.
 *
 * A 429 is rejected like any other status and is NOT a refresh and NOT a logout:
 * the caller reads `Retry-After` off `ApiError`-adjacent state by asking the
 * response itself, and the slug is `rate-limited`.
 */
export async function sessionFetch(path: string, init: SessionFetchInit = {}): Promise<Response> {
  const { skipAuthRefresh, headers, ...rest } = init
  const send = () =>
    globalFetch(apiPath(path), {
      ...rest,
      credentials: 'include',
      headers: sessionHeaders(headers),
    })

  let res = await send()
  if (res.status === 401 && !skipAuthRefresh) {
    try {
      await refreshSession()
    } catch {
      onSessionExpired()
      throw await apiErrorFromResponse(res)
    }
    res = await send()
    if (res.status === 401) {
      onSessionExpired()
      throw await apiErrorFromResponse(res)
    }
  }
  if (!res.ok) throw await apiErrorFromResponse(res)
  return res
}
