/**
 * The copy for a refused tax-export operation, in one place (§4.12, step 203).
 *
 * Covers exactly the slugs these operations narrow `ErrorResponse.type` to:
 * 400 `validation-error` on `setTaxLine`/`createTaxExportRun`, 404 `not-found`
 * for a category or run outside the caller's own scope, 403 for a refused
 * CSRF header, and 429. The download's 409 `ExportNotReady` is handled
 * separately by the screen ("still generating"), never through this switch —
 * it is not a failure.
 */
import type { ApiError } from '@api/client'

export function taxExportErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'validation-error':
      return error.fieldErrors[0]?.message ?? error.detail ?? 'Some of those details were refused.'
    case 'not-found':
      return 'That is not there any more. Reload the page.'
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
