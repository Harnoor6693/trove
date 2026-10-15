/**
 * `/v1/notifications/*` — seven operations, all of them, and nothing else
 * (§4.9, step 203).
 *
 * There is no push channel and no device registration on this API (owner
 * decision, 2026-09-03) — `getUnreadNotificationCount` is the whole of the
 * story, polled or refetched on a sane interval and on window focus, never
 * over a socket.
 */
import { api, unwrap, unwrapVoid } from './client'
import type {
  ApiResponse,
  MarkedReadResponse,
  NotificationPreferencesResponse,
  NotificationPreferencesUpdateRequest,
  NotificationResponse,
  NotificationsQuery,
  Page,
  PaginationMeta,
  UnreadCountResponse,
} from './types'

/** `GET /v1/notifications?page&limit` — the caller's own inbox, newest first, undismissed only. PAGED. */
export async function listNotifications(
  q: NotificationsQuery = {}
): Promise<Page<NotificationResponse>> {
  const params: Record<string, number> = {}
  if (q.page !== undefined) params.page = q.page
  if (q.limit !== undefined) params.limit = q.limit

  const res = await api.get<ApiResponse<NotificationResponse[]>>('/v1/notifications', { params })
  const rows = unwrap(res)
  const meta = res.data.meta
  if (!meta) {
    throw new Error('GET /v1/notifications answered without the required pagination meta')
  }
  return { rows, meta: meta as PaginationMeta }
}

/** `GET /v1/notifications/unread-count` — what the shell's bell badge shows. */
export async function getUnreadNotificationCount(): Promise<UnreadCountResponse> {
  const res = await api.get<ApiResponse<UnreadCountResponse>>('/v1/notifications/unread-count')
  return unwrap(res)
}

/** `PATCH /v1/notifications/{id}/read` — idempotent; no request body. */
export async function markNotificationRead(id: string): Promise<NotificationResponse> {
  const res = await api.patch<ApiResponse<NotificationResponse>>(
    `/v1/notifications/${encodeURIComponent(id)}/read`
  )
  return unwrap(res)
}

/** `POST /v1/notifications/read-all` — marks every unread row read; answers how many. */
export async function markAllNotificationsRead(): Promise<MarkedReadResponse> {
  const res = await api.post<ApiResponse<MarkedReadResponse>>('/v1/notifications/read-all')
  return unwrap(res)
}

/** `GET /v1/notifications/preferences` — created with the shipped defaults on first access. */
export async function getNotificationPreferences(): Promise<NotificationPreferencesResponse> {
  const res = await api.get<ApiResponse<NotificationPreferencesResponse>>(
    '/v1/notifications/preferences'
  )
  return unwrap(res)
}

/**
 * `PUT /v1/notifications/preferences` — a PARTIAL update despite the verb.
 * Send only the fields the member changed; an explicit `null` clears
 * `emailAddress`, the one field where `null` and "omitted" differ.
 */
export async function updateNotificationPreferences(
  body: NotificationPreferencesUpdateRequest
): Promise<NotificationPreferencesResponse> {
  const res = await api.put<ApiResponse<NotificationPreferencesResponse>>(
    '/v1/notifications/preferences',
    body
  )
  return unwrap(res)
}

/** `DELETE /v1/notifications/{id}` — hides it from the inbox and the unread count. Idempotent. `EmptySuccess`. */
export async function dismissNotification(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(
    `/v1/notifications/${encodeURIComponent(id)}`
  )
  unwrapVoid(res)
}
