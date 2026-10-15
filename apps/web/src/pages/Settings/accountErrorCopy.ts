/**
 * Copy for every failure the account-security, data and admin surfaces can show.
 *
 * Each switch below is EXACTLY the slugs the relevant operations declare in
 * `openapi/trove-core.yaml`, and nothing invented — a slug a route cannot emit
 * gets no copy, because copy for an impossible state is a lie nobody ever sees
 * corrected.
 *
 * The one rule these all share: **403 `step-up-required` is never a generic
 * failure.** It means the password or the code was not accepted, and saying
 * anything vaguer trains a member to retype a credential that was in fact
 * correct — or to believe a correct one was wrong.
 */
import type { ApiError } from '@api/client'

function shared(error: ApiError): string | null {
  if (error.isNetworkError) {
    return 'Trove could not reach the server. Check your connection and try again.'
  }
  switch (error.code) {
    case 'step-up-required':
      // The server's own detail is more specific than anything this client can
      // say — an OIDC-only member with no second factor is told to ask an
      // administrator for a reset link, which no generic string would cover.
      return error.detail ?? 'That password or code was not accepted.'
    case 'validation-error':
      return error.fieldErrors[0]?.message ?? error.detail ?? 'Some of those details were refused.'
    case 'rate-limited':
      // A 429 is "wait and retry", never a logout and never a token refresh.
      return 'Too many requests just now. Wait a few moments, then try again.'
    case 'csrf-header-missing':
      return 'Your browser session went stale. Reload the page and try again.'
    case 'unauthorized':
      return 'Your session has ended. Sign in again.'
    default:
      return null
  }
}

/** `changePassword`, `setupTotp`, `confirmTotp`, `disableTotpByPost`, `getMySecurityEvents`. */
export function securityErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'password-policy':
      return (
        error.fieldErrors[0]?.message ??
        error.detail ??
        'That new password was refused. Choose a longer one.'
      )
    case 'totp-invalid':
      return 'That code was not accepted. Try the next one from your authenticator app.'
    case 'totp-already-enabled':
      return 'Two-factor authentication is already on for this account.'
    case 'totp-not-enrolled':
      return 'There is no enrolment in progress. Start again from "Set up two-factor".'
    case 'limiter-unavailable':
      return 'Trove cannot check its rate limits right now, so it refused this to be safe. Try again shortly.'
    default:
      return shared(error) ?? 'That did not go through.'
  }
}

/** `createDataExport`, `listDataExports`, `getDataExport`, `downloadDataExport`. */
export function dataExportErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'conflict':
      // Two different 409s reach here: the one-run-per-day cap on create, and
      // "not ready or expired" on download. Both carry the server's own message,
      // which is the only thing that knows WHEN the member may ask again — a
      // client-side timer would be guessing.
      return error.detail ?? 'Trove refused that for now. Try again later.'
    case 'not-found':
      return 'That export is not there any more. Reload the page.'
    default:
      return shared(error) ?? 'That export did not go through.'
  }
}

/** `deleteMyAccountByPost`. */
export function deleteAccountErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'conflict':
      // The last-ADMIN refusal. It is an EXPLANATION, not an error: the member
      // did nothing wrong and there is a clear way forward.
      return (
        error.detail ??
        'You are the only administrator of this household, so your account cannot be deleted yet. Give another member the administrator role first, then try again.'
      )
    default:
      return shared(error) ?? 'That did not go through.'
  }
}

/** Every `/v1/admin/*` operation and `/v1/audit`. */
export function adminErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'forbidden':
      return 'Only an administrator of this household can do that.'
    case 'not-found':
      return 'That member is not in this household any more. Reload the page.'
    case 'conflict':
      return error.detail ?? 'Trove refused that. Reload the page to see the current state.'
    default:
      return shared(error) ?? 'That did not go through.'
  }
}
