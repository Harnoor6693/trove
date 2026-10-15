/**
 * The copy for a refused subscriptions operation, in one place (§4.8, step 203).
 *
 * None of these seven operations declares a 400 — there is no create or
 * update route, so validation never applies here. The switch covers exactly
 * the slugs that remain: 404 for an id outside the caller's own household,
 * 403 for a refused CSRF header, and 429.
 */
import type { ApiError } from '@api/client'

export function subscriptionsErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'not-found':
      return 'That is not in this household any more. Reload the page.'
    case 'forbidden':
    case 'csrf-header-missing':
      return 'That request was refused. Reload the page and try again.'
    case 'rate-limited':
      return 'Too many requests just now. Wait a few seconds, then try again.'
    default:
      return error.isNetworkError
        ? 'Trove could not reach the server. Check your connection and try again.'
        : 'That did not go through.'
  }
}
