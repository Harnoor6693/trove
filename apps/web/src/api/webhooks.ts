/**
 * `/v1/webhooks` — three operations, all of them, and nothing else (§4.9,
 * step 203). A SECURITY SURFACE: `targetUrl` is checked by the server's own
 * egress policy, never pre-validated here beyond obvious shape, and a refusal
 * is shown in the server's own words rather than invented copy.
 *
 * `WebhookResponse` never carries the shared secret in any form, at creation
 * or otherwise — `hasSecret` (always `true`) is the whole of what this API
 * says about it. There is nothing to show once from the server; the create
 * dialog gives a client-side one-time reminder instead, built from the value
 * the member just typed, never re-read from the query cache.
 */
import { api, unwrap, unwrapVoid } from './client'
import type { ApiResponse, WebhookCreateRequest, WebhookResponse } from './types'

/** `GET /v1/webhooks` — the caller's own subscriptions, disabled ones included. Bare array; capped, not paged. */
export async function listWebhooks(): Promise<WebhookResponse[]> {
  const res = await api.get<ApiResponse<WebhookResponse[]>>('/v1/webhooks')
  return unwrap(res)
}

/** `POST /v1/webhooks` — 201. `secret` is write-only and never echoed, here or in any later read. */
export async function createWebhook(body: WebhookCreateRequest): Promise<WebhookResponse> {
  const res = await api.post<ApiResponse<WebhookResponse>>('/v1/webhooks', body)
  return unwrap(res)
}

/** `DELETE /v1/webhooks/{id}` — the subscription and, by cascade, every delivery-log row. `EmptySuccess`. */
export async function deleteWebhook(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(`/v1/webhooks/${encodeURIComponent(id)}`)
  unwrapVoid(res)
}
