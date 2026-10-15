/**
 * The copy for a refused write on one transaction, in one place.
 *
 * Two surfaces make the SAME call — `PATCH /v1/transactions/{id}` — and must
 * therefore speak with one voice: the detail dialog's Save and transfer
 * controls, and the list's inline category select. It lives in its own module
 * (the `src/pages/Login/errorCopy.ts` pattern) rather than being exported from
 * the dialog, because a component file that also exports a function loses fast
 * refresh, which the lint gate enforces.
 *
 * The switch covers exactly the slugs these operations narrow `ErrorResponse.type`
 * to. A 429 is a wait-and-retry state with real copy, never a generic failure and
 * never a logout — `src/api/client.ts` owns the session.
 */
import type { ApiError } from '@api/client'

export function detailErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'not-found':
      return 'That transaction is not in this household. It may have been deleted, or it may belong to someone else.'
    case 'conflict':
      return error.detail ?? 'Only a hand-entered transaction can be deleted here.'
    case 'validation-error':
      return error.detail ?? 'That change was refused.'
    case 'forbidden':
    case 'csrf-header-missing':
      return 'You do not have permission to change this.'
    case 'rate-limited':
      return 'Too many requests just now. Wait a few seconds, then try again.'
    default:
      return 'That change did not go through.'
  }
}
