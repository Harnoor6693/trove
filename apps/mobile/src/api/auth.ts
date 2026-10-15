/**
 * Auth endpoints — `docs/recovery/06-COLLAPSE-PLAN.md` §4.1.
 *
 * Mobile is bearer-mode throughout: no `X-Trove-Auth-Mode: cookie` header, so
 * the response body carries the token pair and the `user` object (§1.5 — the
 * user object exists precisely so mobile's session store has something to
 * persist without a second round trip), and the server's CSRF filters skip
 * bearer-authenticated requests.
 *
 * Every call here sets `skipAuthRefresh`: a 401 from an auth route is the
 * answer (wrong password, expired TOTP challenge, revoked refresh family),
 * not a signal that the access token needs rotating.
 */

import { api, setSession, updateSessionUser } from './client';
import { expectSuccess, unwrap } from './errors';
import { pathSegment } from './paths';
import type {
  ApiResponse,
  AuthSession,
  AuthUser,
  ChangePasswordRequest,
  ChangePasswordResult,
  InvitePreviewResponse,
  LoginRequest,
  LoginResult,
  PasswordResetRequest,
  RegisterRequest,
  TotpConfirmed,
  TotpDisableRequest,
  TotpLoginRequest,
  TotpSetup,
} from './types';
import { isTotpChallenge } from './types';

const AUTH_CALL = { skipAuthRefresh: true } as const;

/**
 * `POST /v1/auth/login`. Returns either a full session or a 60-second
 * `typ=totp_challenge` token when the account has TOTP enabled (§1.5).
 * A session result is persisted before returning.
 */
export async function login(body: LoginRequest): Promise<LoginResult> {
  const response = await api.post<ApiResponse<LoginResult>>('/v1/auth/login', body, AUTH_CALL);
  const result = unwrap(response.data);
  if (!isTotpChallenge(result)) {
    await setSession(result, result.user);
  }
  return result;
}

/** `POST /v1/auth/login/totp` — second factor, or a single-use recovery code. */
export async function loginWithTotp(body: TotpLoginRequest): Promise<AuthSession> {
  const response = await api.post<ApiResponse<AuthSession>>('/v1/auth/login/totp', body, AUTH_CALL);
  const session = unwrap(response.data);
  await setSession(session, session.user);
  return session;
}

/**
 * `POST /v1/auth/register` (§4.1). Registration is closed by default; an
 * invite token is required unless the server advertises `registrationOpen`
 * on `GET /v1/auth/providers`.
 */
export async function register(body: RegisterRequest): Promise<AuthSession> {
  const response = await api.post<ApiResponse<AuthSession>>('/v1/auth/register', body, AUTH_CALL);
  const session = unwrap(response.data);
  await setSession(session, session.user);
  return session;
}

/**
 * `POST /v1/auth/logout` — revokes the refresh-token family server-side.
 * Clearing local state is the caller's job (`authStore.signOut`), which does
 * it whether or not this call succeeds.
 */
export async function logout(refreshToken: string): Promise<void> {
  await api.post('/v1/auth/logout', { refreshToken }, AUTH_CALL);
}

/**
 * `POST /v1/auth/password` — step-up change; revokes every other session but
 * **keeps this one working**. The server sets no cookies and mints no new
 * tokens — the caller's existing pair is still valid — and answers `{user}`,
 * not an empty body (contract change from the fix round: this used to be
 * typed `Promise<void>`). The cached session user is refreshed in place so a
 * caller that only reads `authStore.user` sees the same object it would from
 * `GET /v1/auth/me`.
 */
export async function changePassword(body: ChangePasswordRequest): Promise<AuthUser> {
  const response = await api.post<ApiResponse<ChangePasswordResult>>(
    '/v1/auth/password',
    body,
    AUTH_CALL
  );
  const { user } = unwrap(response.data);
  await updateSessionUser(user);
  return user;
}

/** `POST /v1/auth/totp/setup` — begins enrolment; requires a fresh password. */
export async function startTotpEnrolment(password: string): Promise<TotpSetup> {
  const response = await api.post<ApiResponse<TotpSetup>>(
    '/v1/auth/totp/setup',
    { password },
    AUTH_CALL
  );
  return unwrap(response.data);
}

/** `POST /v1/auth/totp/confirm` — returns the 10 recovery codes, shown once. */
export async function confirmTotpEnrolment(code: string): Promise<TotpConfirmed> {
  const response = await api.post<ApiResponse<TotpConfirmed>>(
    '/v1/auth/totp/confirm',
    { code },
    AUTH_CALL
  );
  return unwrap(response.data);
}

/**
 * `POST /v1/auth/totp/disable` — step-up: the current password **and** a
 * current code. Either one being wrong is 403 `step-up-required`; requiring
 * only one of them would make the second factor removable by exactly the
 * attacker it exists to stop — whoever has the password but not the phone, or
 * the unlocked device but not the password.
 *
 * **The POST twin, not `DELETE /v1/auth/totp`.** The two are the identical
 * operation with the identical body and the identical answers, and the
 * contract says so at both: a DELETE that carries a request body is legal but
 * poorly supported — RFC 9110 leaves its semantics undefined and
 * intermediaries drop the body without saying so, which would turn a
 * deliberate action into an unexplained refusal in front of somebody changing
 * how their account is secured. This app used to call the DELETE (which is the
 * stated reason the spec keeps it); step 215 moved it to the verb that always
 * works, exactly as it uses `POST /v1/me/delete` over `DELETE /v1/me`.
 *
 * The password and code are credentials: passed through, not retained, not
 * logged, not cached.
 */
export async function disableTotp(password: string, code: string): Promise<void> {
  const body: TotpDisableRequest = { password, code };
  const response = await api.post<ApiResponse<never>>('/v1/auth/totp/disable', body, AUTH_CALL);
  expectSuccess(response.data);
}

/**
 * `GET /v1/auth/invites/{token}` — check an invitation link before redeeming
 * it. Public, and **always 200**: the verdict is in the body, never in the
 * status, so an unknown, revoked, used or expired token all answer
 * `{valid: false, email: null, expiresAt: null}` and the route cannot be used
 * to tell a real token from a near miss. A screen must therefore branch on
 * `valid`, not on a caught error.
 */
export async function previewInvite(token: string): Promise<InvitePreviewResponse> {
  const response = await api.get<ApiResponse<InvitePreviewResponse>>(
    `/v1/auth/invites/${pathSegment(token)}`,
    AUTH_CALL
  );
  return unwrap(response.data);
}

/**
 * `POST /v1/auth/password/reset` — set a new password with an admin-minted
 * reset token. Public: the token is the whole authorisation.
 *
 * Unknown, expired and already-used tokens get the identical 400
 * `reset-invalid`, so the route cannot be used to test guesses and the copy
 * for it must not distinguish them either. The password policy is checked
 * **before** the token is consumed, so a refused password leaves the link
 * usable and the member can try again rather than having to ask for a second
 * one. On success every session of that account is revoked.
 */
export async function redeemPasswordResetLink(body: PasswordResetRequest): Promise<void> {
  const response = await api.post<ApiResponse<never>>('/v1/auth/password/reset', body, AUTH_CALL);
  expectSuccess(response.data);
}
