/**
 * Every failure the sign-in surface can show, with the copy for it.
 *
 * Defect I4 was half a missing key: the axios interceptor redirected to
 * `/login?error=expired` and `expired` was not in this map, so the first thing a
 * brand-new visitor ever saw was "Sign-in failed. Please try again." — a message
 * about a thing they had not done. The map is now exhaustive over the union
 * below, so adding a code without copy is a compile error.
 *
 * `totp_required` deserves one too. It is the only code here that is not a
 * failure: it arrives as a query parameter on the OIDC return leg for a member
 * with a second factor, and the code form it triggers carries NO challenge token
 * — the token is in an HttpOnly cookie the SPA cannot read, and
 * `TotpLoginRequest.challengeToken` is optional precisely so the cookie can
 * carry it.
 *
 * `locked` deserves a note. §1.5 makes login enumeration-safe: a soft-locked
 * account and a wrong password return an identical 401 body, so `locked` can only
 * ever be reached through the per-IP 429 (20 failures / 10 minutes). A 401 always
 * reads as `invalid`, which is exactly the intent.
 */
import { ApiError } from '@api/client'

export type LoginErrorCode =
  | 'expired'
  | 'invalid'
  | 'locked'
  | 'totp_invalid'
  | 'totp_required'
  | 'csrf'
  | 'server'
  | 'network'
  | 'denied'
  | 'oidc'
  | 'session'
  | 'link_required'
  | 'registration_closed'
  | 'invite_invalid'

export const ERROR_MESSAGES: Record<LoginErrorCode, string> = {
  expired: 'Your session expired. Please sign in again.',
  invalid: 'That email and password did not match. Please try again.',
  locked: 'Too many sign-in attempts. Wait a few minutes and try again.',
  totp_invalid: 'That code was not accepted. Try the next one, or use a recovery code.',
  // The OIDC half of the two-factor sign-in. The server redirects the browser
  // back to `<publicUrl>/login?error=totp_required` and sets the HttpOnly
  // `__Host-trove_totp_challenge` cookie, which the SPA cannot read — so this is
  // an INSTRUCTION, not a failure, and the page renders it as a status rather
  // than an alert.
  totp_required:
    'Your identity provider signed you in. Enter the code from your authenticator app to finish.',
  csrf: 'Your browser session went stale. Reload the page and try again.',
  server: 'Trove is having trouble on its end. Try again in a moment.',
  network: 'Could not reach the Trove server. Check that it is running.',
  denied: 'Sign-in was cancelled.',
  oidc: 'Your identity provider could not sign you in. Contact your administrator.',
  session: 'Your sign-in took too long to finish. Please try again.',
  link_required:
    'Your identity provider has not verified that email address. Sign in with your Trove password once to link the two.',
  registration_closed:
    'This Trove is invite-only. Ask an administrator for an invite link.',
  invite_invalid: 'That invite link is no longer valid. Ask your administrator for a new one.',
}

const QUERY_CODES = new Set<string>(Object.keys(ERROR_MESSAGES))

/** Maps a `?error=` query parameter onto a known code; unknown values read as generic. */
export function codeFromQuery(value: string | null): LoginErrorCode | null {
  if (!value) return null
  return QUERY_CODES.has(value) ? (value as LoginErrorCode) : 'oidc'
}

/** Maps a failed sign-in call onto a code. */
export function codeFromError(error: unknown, context: 'login' | 'totp' = 'login'): LoginErrorCode {
  if (!(error instanceof ApiError)) return 'server'
  if (error.isNetworkError) return 'network'
  switch (error.status) {
    case 400:
    case 401:
      return context === 'totp' ? 'totp_invalid' : 'invalid'
    case 403:
      return error.code === 'registration-closed' ? 'registration_closed' : 'csrf'
    case 404:
    case 410:
      return 'invite_invalid'
    case 429:
      return 'locked'
    default:
      return 'server'
  }
}

export function messageFor(code: LoginErrorCode | null): string | null {
  return code ? ERROR_MESSAGES[code] : null
}
