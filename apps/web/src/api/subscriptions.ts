/**
 * `/v1/subscriptions/*` — seven operations, all of them, and nothing else
 * (§4.8, step 203).
 *
 * There is no create or update route: a subscription is an observation about
 * the ledger, written only by detection. The one write a member makes is
 * `cancelSubscription`, a SOFT delete — the row survives and stays readable,
 * it only leaves the active list and every summary figure. It does not
 * contact the merchant; it only stops Trove tracking it.
 */
import { api, unwrap, unwrapVoid } from './client'
import type {
  ApiResponse,
  SubscriptionAlertResponse,
  SubscriptionResponse,
  SubscriptionSummaryResponse,
} from './types'

/** `GET /v1/subscriptions` — active (non-cancelled) only. Bare array; not paged. */
export async function listSubscriptions(): Promise<SubscriptionResponse[]> {
  const res = await api.get<ApiResponse<SubscriptionResponse[]>>('/v1/subscriptions')
  return unwrap(res)
}

/** `GET /v1/subscriptions/summary` — per-currency totals and upcoming charges. Never blended. */
export async function getSubscriptionSummary(): Promise<SubscriptionSummaryResponse> {
  const res = await api.get<ApiResponse<SubscriptionSummaryResponse>>('/v1/subscriptions/summary')
  return unwrap(res)
}

/** `GET /v1/subscriptions/alerts` — the household's UNREAD alerts, newest first. Bare array; not paged. */
export async function listSubscriptionAlerts(): Promise<SubscriptionAlertResponse[]> {
  const res = await api.get<ApiResponse<SubscriptionAlertResponse[]>>('/v1/subscriptions/alerts')
  return unwrap(res)
}

/** `PATCH /v1/subscriptions/alerts/{id}/read` — idempotent; no request body. */
export async function markSubscriptionAlertRead(id: string): Promise<SubscriptionAlertResponse> {
  const res = await api.patch<ApiResponse<SubscriptionAlertResponse>>(
    `/v1/subscriptions/alerts/${encodeURIComponent(id)}/read`
  )
  return unwrap(res)
}

/**
 * `POST /v1/subscriptions/scan` — runs detection synchronously for the
 * caller's own household. Idempotent; answers the active list as it stands
 * after the scan, the same body `listSubscriptions` returns.
 */
export async function scanSubscriptions(): Promise<SubscriptionResponse[]> {
  const res = await api.post<ApiResponse<SubscriptionResponse[]>>('/v1/subscriptions/scan')
  return unwrap(res)
}

/** `GET /v1/subscriptions/{id}` — a cancelled row is still readable, with `cancelled: true`. */
export async function getSubscription(id: string): Promise<SubscriptionResponse> {
  const res = await api.get<ApiResponse<SubscriptionResponse>>(
    `/v1/subscriptions/${encodeURIComponent(id)}`
  )
  return unwrap(res)
}

/**
 * `DELETE /v1/subscriptions/{id}` — a SOFT delete: sets `cancelled` true. The
 * row survives and is still readable by id; this only removes it from the
 * active list and every summary figure. It does not contact the merchant.
 */
export async function cancelSubscription(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(
    `/v1/subscriptions/${encodeURIComponent(id)}`
  )
  unwrapVoid(res)
}
