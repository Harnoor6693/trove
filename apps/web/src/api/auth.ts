/**
 * `/v1/auth/*` — §4.1 of the collapse plan.
 *
 * Web is cookie mode: `X-Trove-Auth-Mode: cookie` on login makes the server set
 * `__Host-trove_access` / `__Host-trove_refresh` (HttpOnly) and keep tokens out of
 * the body (§1.5). Nothing in this module ever sees or stores a token.
 *
 * Every body here is the `ApiResponse` envelope — §4.1 states that explicitly for
 * `/v1/auth/*`, with no legacy bare shape carved out. `/v1/status` is the one
 * unenveloped route in this file.
 */
import { api, unwrap, unwrapVoid } from './client'
import type {
  ApiResponse,
  AuthProviders,
  CookieSession,
  InvitePreviewResponse,
  LoginRequest,
  LoginResponse,
  MeResponse,
  PasswordChangeRequest,
  PasswordResetLinkRequest,
  PasswordResetLinkResponse,
  PasswordResetRequest,
  RegisterRequest,
  ServerStatus,
  TotpConfirmRequest,
  TotpConfirmResponse,
  TotpDisableRequest,
  TotpEnrolment,
  TotpLoginRequest,
  TotpSetupRequest,
} from './types'

/** Cookie mode. Bearer mode (`apps/mobile`) omits this header. */
const COOKIE_MODE = { 'X-Trove-Auth-Mode': 'cookie' } as const

/** `POST /v1/auth/login` → a session, or a TOTP challenge when the user has TOTP on. */
export async function login(req: LoginRequest): Promise<LoginResponse> {
  const res = await api.post<ApiResponse<LoginResponse>>('/v1/auth/login', req, {
    headers: COOKIE_MODE,
    skipAuthRefresh: true,
  })
  return unwrap(res)
}

/** `POST /v1/auth/login/totp` — `code` is a TOTP code or a single-use recovery code. */
export async function loginTotp(req: TotpLoginRequest): Promise<LoginResponse> {
  const res = await api.post<ApiResponse<LoginResponse>>('/v1/auth/login/totp', req, {
    headers: COOKIE_MODE,
    skipAuthRefresh: true,
  })
  return unwrap(res)
}

/**
 * `POST /v1/auth/register` — needs `inviteToken` unless `registrationOpen`.
 * 201 with the same body shape as a login (a session, or a TOTP challenge when
 * the account already has a second factor) — NOT `{user}` alone, which is what
 * this client assumed before the spec existed.
 */
export async function register(req: RegisterRequest): Promise<LoginResponse> {
  const res = await api.post<ApiResponse<LoginResponse>>('/v1/auth/register', req, {
    headers: COOKIE_MODE,
    skipAuthRefresh: true,
  })
  return unwrap(res)
}

/** `POST /v1/auth/logout` — revokes the refresh-token family server-side. */
export async function logout(): Promise<void> {
  const res = await api.post<ApiResponse<null>>('/v1/auth/logout', null, {
    skipAuthRefresh: true,
  })
  unwrapVoid(res)
}

/**
 * `GET /v1/auth/me`.
 *
 * `skipAuthRefresh` is passed by the session-hydration caller so a first-visit 401
 * stays an anonymous 401 (defect I4). A caller that already knows a session exists
 * may pass `{ skipAuthRefresh: false }` to get the normal refresh-and-replay.
 */
export async function getMe(opts: { skipAuthRefresh?: boolean } = {}): Promise<MeResponse> {
  const res = await api.get<ApiResponse<MeResponse>>('/v1/auth/me', {
    skipAuthRefresh: opts.skipAuthRefresh ?? true,
  })
  return unwrap(res)
}

/**
 * `GET /v1/auth/providers` — `{local, oidc:{enabled,displayName}, registrationOpen}`.
 * The Login page gates the OIDC button on this rather than linking `/v1/auth/start`
 * unconditionally (which 404s when OIDC is off), and Register refuses to render a
 * form when registration is closed and no invite token was supplied.
 */
export async function getAuthProviders(): Promise<AuthProviders> {
  const res = await api.get<ApiResponse<AuthProviders>>('/v1/auth/providers', {
    skipAuthRefresh: true,
  })
  return unwrap(res)
}

/**
 * `GET /v1/status` (§4.2, gap P1-1) — unauthenticated and detail-free, and the one
 * route in this module that is NOT enveloped (verified against
 * `core/src/main/java/finance/trove/app/HealthController.java`).
 */
export async function getServerStatus(): Promise<ServerStatus> {
  const res = await api.get<ServerStatus>('/v1/status', { skipAuthRefresh: true })
  return res.data
}

/** The OIDC entry point Spring serves under the `/api` context path (§1.4). */
export const OIDC_START_URL = '/api/v1/auth/start'

/* ==========================================================================
 * Family E (step 205) — the rest of `/v1/auth/*`: the invite preview, the
 * password routes and the TOTP enrolment lifecycle.
 * ======================================================================== */

/**
 * The OIDC entry point to actually use.
 *
 * `GET /v1/auth/providers` advertises an absolute `oidc.startUrl`, and the
 * advertised value is the contract; `OIDC_START_URL` above is a same-origin
 * constant that resolves identically on this install today but would be wrong
 * the moment the provider entry point moves. Prefer what the server said, fall
 * back to the constant when it said nothing.
 */
export function oidcStartUrl(providers: AuthProviders | undefined): string {
  return providers?.oidc?.startUrl ?? OIDC_START_URL
}

/**
 * `GET /v1/auth/invites/{token}` — public, and **always 200**: the verdict is in
 * the body, never in the status. Unknown, revoked, used and expired tokens all
 * answer the identical `{valid: false, email: null, expiresAt: null}`, so this
 * route cannot be used to tell a real token from a near miss.
 *
 * `skipAuthRefresh`: a visitor holding an invite link has no session, and an
 * anonymous call must never set a refresh going.
 */
export async function previewInvite(token: string): Promise<InvitePreviewResponse> {
  const res = await api.get<ApiResponse<InvitePreviewResponse>>(
    `/v1/auth/invites/${encodeURIComponent(token)}`,
    { skipAuthRefresh: true }
  )
  return unwrap(res)
}

/**
 * `POST /v1/auth/password` — change the signed-in account's password.
 *
 * **Ends every other session and leaves this one working**, and deliberately
 * mints nothing: the response is `{user}` with no tokens and no `Set-Cookie`,
 * because the caller's existing pair is still valid. A wrong current password is
 * 403 `step-up-required`, NOT 401 — the interceptor must not go refresh a
 * session that is perfectly good.
 */
export async function changePassword(req: PasswordChangeRequest): Promise<CookieSession> {
  const res = await api.post<ApiResponse<CookieSession>>('/v1/auth/password', req)
  return unwrap(res)
}

/**
 * `POST /v1/auth/password/reset-request` — **ADMIN only**
 * (`x-trove-required-role: ADMIN`). There is no SMTP on a typical install, so
 * reset is an admin minting a one-hour single-use link and handing it over out
 * of band. Resolution is scoped to the admin's own household; any other address
 * is 404. The returned `url` is readable exactly once.
 */
export async function requestPasswordResetLink(
  req: PasswordResetLinkRequest
): Promise<PasswordResetLinkResponse> {
  const res = await api.post<ApiResponse<PasswordResetLinkResponse>>(
    '/v1/auth/password/reset-request',
    req
  )
  return unwrap(res)
}

/**
 * `POST /v1/auth/password/reset` — public; the token is the whole authorisation.
 *
 * Unknown, expired and already-used tokens all get the identical 400
 * `reset-invalid`, so the route cannot be used to test guesses. The policy is
 * checked BEFORE the token is consumed, so a refused password leaves the link
 * usable. Answers the closed `EmptySuccess` — there is no `data` to read.
 */
export async function redeemPasswordResetLink(req: PasswordResetRequest): Promise<void> {
  const res = await api.post<ApiResponse<never>>('/v1/auth/password/reset', req, {
    skipAuthRefresh: true,
  })
  unwrapVoid(res)
}

/**
 * `POST /v1/auth/totp/setup` — behind a fresh password. Returns the secret but
 * **does not enable the factor**: it stays off until `confirmTotp` sees one live
 * code, which is what stops a member scanning a QR badly and locking themselves
 * out of their own finance server. Already enrolled is 409 `totp-already-enabled`.
 */
export async function setupTotp(req: TotpSetupRequest): Promise<TotpEnrolment> {
  const res = await api.post<ApiResponse<TotpEnrolment>>('/v1/auth/totp/setup', req)
  return unwrap(res)
}

/**
 * `POST /v1/auth/totp/confirm` — one live code enables the factor and mints the
 * recovery codes. **This is the only time they are readable**; they are stored
 * hashed, so they are never re-fetched and never written anywhere.
 */
export async function confirmTotp(req: TotpConfirmRequest): Promise<TotpConfirmResponse> {
  const res = await api.post<ApiResponse<TotpConfirmResponse>>('/v1/auth/totp/confirm', req)
  return unwrap(res)
}

/**
 * `POST /v1/auth/totp/disable` — password AND a current code; either one wrong
 * is 403 `step-up-required`.
 *
 * The POST twin, not `DELETE /v1/auth/totp`, and deliberately: a DELETE that
 * carries a request body is legal but poorly supported — RFC 9110 leaves its
 * semantics undefined and intermediaries drop the body without saying so, which
 * would turn "remove my second factor" into an unexplained 400. The spec's own
 * words for this operation: "**This is the one that always works.**"
 */
export async function disableTotp(req: TotpDisableRequest): Promise<void> {
  const res = await api.post<ApiResponse<never>>('/v1/auth/totp/disable', req)
  unwrapVoid(res)
}
