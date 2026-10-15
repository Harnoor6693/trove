/**
 * Webhooks — the three operations `openapi/trove-core.yaml` describes for
 * `/v1/webhooks*`.
 *
 * **A security surface, not a settings toggle.** `targetUrl` is checked by the
 * shared egress policy at creation and again on every delivery attempt; a
 * refusal is 400 `outbound-url-rejected` with a sentence naming the rule
 * (never the resolved address, which would turn this route into a port
 * scanner). **The secret is never returned, in any form, at any time** —
 * `WebhookResponse` has no field for it and the create operation's own 201
 * says "the secret is not echoed" — so there is no show-once value for this
 * app to hold, copy, or accidentally leave in a query cache. `hasSecret` is
 * the whole of what the API says about it.
 *
 * Per member, not per household: a household mate's subscriptions are 404 by
 * id and absent from the list.
 */

import { api } from './client';
import { pathSegment } from './paths';
import { expectSuccess, unwrap } from './errors';
import type { ApiResponse, WebhookCreateRequest, WebhookResponse } from './types';

/** `GET /v1/webhooks` — the caller's own subscriptions, disabled ones included. Not paged. */
export async function fetchWebhooks(): Promise<WebhookResponse[]> {
  const response = await api.get<ApiResponse<WebhookResponse[]>>('/v1/webhooks');
  return unwrap(response.data);
}

/**
 * `POST /v1/webhooks` — subscribes an HTTPS URL to the caller's events. The
 * response never carries `secret`; it is stored encrypted and write-only.
 */
export async function createWebhook(body: WebhookCreateRequest): Promise<WebhookResponse> {
  const response = await api.post<ApiResponse<WebhookResponse>>('/v1/webhooks', body);
  return unwrap(response.data);
}

/** `DELETE /v1/webhooks/{id}` — removes the subscription and its delivery log with it. */
export async function deleteWebhook(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/webhooks/${pathSegment(id)}`);
  expectSuccess(response.data);
}
