/**
 * The copy for a refused notifications operation, in one place (§4.9, step 203).
 *
 * Covers exactly the slugs these operations narrow `ErrorResponse.type` to:
 * 400 `validation-error` (a bad page/limit, or a rejected preferences body),
 * 404 `not-found` for a notification outside the caller's own inbox, 403 for
 * a refused CSRF header, and 429.
 */
import type { ApiError } from '@api/client'
import type { NotificationTypeCode } from '@api/types'

export function notificationsErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'validation-error':
      return error.fieldErrors[0]?.message ?? error.detail ?? 'Some of those details were refused.'
    case 'not-found':
      return 'That is not in your inbox any more. Reload the page.'
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

/**
 * The inbox's own copy for a notification's TYPE, derived from the CLOSED
 * `NotificationTypeCode` enum. A `Record` over every member means a future
 * sixth value the server starts writing is a compile error here, not a blank
 * row — the whole reason scope item 6 asks for a type-derived map rather than
 * a hand-written `switch` with a `default`.
 */
export const NOTIFICATION_TYPE_LABEL: Record<NotificationTypeCode, string> = {
  BUDGET_THRESHOLD: 'Budget threshold',
  SUBSCRIPTION_PRICE_CHANGE: 'Subscription price change',
  SYNC_FAILED: 'Account sync failed',
  WEEKLY_DIGEST: 'Weekly digest',
  RECEIPT_PROCESSED: 'Receipt needs a look',
}
