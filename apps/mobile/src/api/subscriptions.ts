/**
 * Subscriptions — the seven operations `openapi/trove-core.yaml` describes
 * for `/v1/subscriptions*`.
 *
 * **There is no create or update route.** A subscription is an observation
 * about the household's ledger, written only by detection (the nightly job,
 * `scanSubscriptions`, or the listener that runs after an import/sync writes
 * transactions) — never something a member authors. The only field a member
 * can change is `cancelled`, through {@link cancelSubscription}, and that is a
 * **soft** delete: the row survives, stays readable by id, and is simply
 * excluded from the active list and every summary figure from then on.
 *
 * **Money is never blended across currencies.** `getSubscriptionSummary`'s
 * `byCurrency` and `next14Days[].totals` each carry one entry per currency the
 * household actually holds subscriptions in; there is no top-level total,
 * because a household billed in both CAD and USD has no single figure to show.
 */

import { api } from './client';
import { pathSegment } from './paths';
import { expectSuccess, unwrap } from './errors';
import type {
  ApiResponse,
  SubscriptionAlertResponse,
  SubscriptionResponse,
  SubscriptionSummaryResponse,
} from './types';

/** `GET /v1/subscriptions` — the household's active detections. Not paged. */
export async function fetchSubscriptions(): Promise<SubscriptionResponse[]> {
  const response = await api.get<ApiResponse<SubscriptionResponse[]>>('/v1/subscriptions');
  return unwrap(response.data);
}

/**
 * `GET /v1/subscriptions/summary` — the run-rate and the next fourteen days.
 * `currency` on the response is the CALLING member's own, never a literal.
 */
export async function fetchSubscriptionSummary(): Promise<SubscriptionSummaryResponse> {
  const response = await api.get<ApiResponse<SubscriptionSummaryResponse>>(
    '/v1/subscriptions/summary'
  );
  return unwrap(response.data);
}

/** `GET /v1/subscriptions/alerts` — the household's unread alerts, newest first. Not paged. */
export async function fetchSubscriptionAlerts(): Promise<SubscriptionAlertResponse[]> {
  const response = await api.get<ApiResponse<SubscriptionAlertResponse[]>>(
    '/v1/subscriptions/alerts'
  );
  return unwrap(response.data);
}

/**
 * `PATCH /v1/subscriptions/alerts/{id}/read` — acknowledges one alert. The
 * row is retained; alerts are an audit trail and are never deleted. Idempotent.
 */
export async function markSubscriptionAlertRead(id: string): Promise<SubscriptionAlertResponse> {
  const response = await api.patch<ApiResponse<SubscriptionAlertResponse>>(
    `/v1/subscriptions/alerts/${pathSegment(id)}/read`
  );
  return unwrap(response.data);
}

/**
 * `POST /v1/subscriptions/scan` — runs detection for the caller's own
 * household right now. Idempotent over unchanged history; answers the same
 * body `GET /v1/subscriptions` would.
 */
export async function scanSubscriptions(): Promise<SubscriptionResponse[]> {
  const response = await api.post<ApiResponse<SubscriptionResponse[]>>('/v1/subscriptions/scan');
  return unwrap(response.data);
}

/**
 * `GET /v1/subscriptions/{id}` — one detection, cancelled or not. A cancelled
 * row still answers here with `cancelled: true`.
 */
export async function fetchSubscription(id: string): Promise<SubscriptionResponse> {
  const response = await api.get<ApiResponse<SubscriptionResponse>>(
    `/v1/subscriptions/${pathSegment(id)}`
  );
  return unwrap(response.data);
}

/**
 * `DELETE /v1/subscriptions/{id}` — **soft** delete. Sets `cancelled: true`;
 * the row survives and stays readable by id. Nothing here contacts the
 * merchant — this is Trove no longer tracking the charge, not a cancellation
 * request sent anywhere. Idempotent.
 */
export async function cancelSubscription(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/subscriptions/${pathSegment(id)}`);
  expectSuccess(response.data);
}
