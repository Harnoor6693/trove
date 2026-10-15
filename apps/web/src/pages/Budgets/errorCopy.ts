/**
 * The copy for a refused write anywhere in this family — categories, merchant
 * rules, budget periods, envelopes and goals. One switch, shared, because a
 * member should hear the same voice whichever of the four screens they were on
 * (the `src/pages/Transactions/errorCopy.ts` pattern).
 *
 * The switch covers exactly the slugs these operations narrow `ErrorResponse.type`
 * to (§4.5/§4.6). A 429 is a wait-and-retry state with real copy, never a
 * generic failure and never a logout — `src/api/client.ts` owns the session.
 */
import type { ApiError } from '@api/client'

export function budgetsErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'not-found':
      return 'That is not in this household any more. Reload the page.'
    case 'conflict':
      return error.detail ?? 'That conflicts with something already there.'
    case 'validation-error':
      return error.detail ?? 'Some of those details were refused.'
    case 'forbidden':
    case 'csrf-header-missing':
      return 'You do not have permission to do that.'
    case 'rate-limited':
      return 'Too many requests just now. Wait a few seconds, then try again.'
    default:
      return error.isNetworkError
        ? 'Trove could not reach the server. Check your connection and try again.'
        : 'That change did not go through.'
  }
}
