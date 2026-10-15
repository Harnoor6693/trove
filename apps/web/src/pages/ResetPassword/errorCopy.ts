/**
 * Every failure `/reset-password/:token` can show, with the copy for it.
 *
 * The switch is EXACTLY the slugs `redeemPasswordResetLink` declares in
 * `openapi/trove-core.yaml` and nothing invented. In particular `reset-invalid`
 * covers unknown, expired AND already-used tokens with one identical answer, on
 * purpose — the route refuses to say which, so neither does this copy.
 */
import type { ApiError } from '@api/client'

/** Exactly the slugs `redeemPasswordResetLink` declares, and nothing invented. */
export function resetErrorCopy(error: ApiError): string {
  if (error.isNetworkError) return 'Could not reach the Trove server. Check that it is running.'
  switch (error.code) {
    case 'reset-invalid':
      return 'That reset link is no longer usable — it may have expired, already been used, or never been valid. Ask an administrator for a new one.'
    case 'password-policy':
    case 'validation-error':
      // Deliberately NOT the field message, which the input already shows
      // inline. This is the reassurance the field cannot give: the policy is
      // checked BEFORE the token is consumed, so a refusal leaves the link
      // usable and the member does not have to ask for a second one.
      return 'That password was refused. Your reset link is still usable, so you can try another one.'
    case 'csrf-header-missing':
    case 'forbidden':
      return 'Your browser session went stale. Reload the page and try again.'
    case 'rate-limited':
      return 'Too many attempts just now. Wait a few minutes and try again.'
    default:
      return 'Trove is having trouble on its end. Try again in a moment.'
  }
}
