/**
 * The copy for a refused net-worth operation, in one place (§4.7, step 203).
 *
 * Covers exactly the slugs the net-worth operations narrow `ErrorResponse.type`
 * to: 400 `validation-error` on every create/update, 404 `not-found` for an id
 * outside the caller's own household, 403 for a refused CSRF header, and 429.
 */
import type { ApiError } from '@api/client'

export function netWorthErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'validation-error':
      return error.fieldErrors[0]?.message ?? error.detail ?? 'Some of those details were refused.'
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
        : 'That change did not go through.'
  }
}
