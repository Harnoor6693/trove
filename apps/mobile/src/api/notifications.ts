/**
 * Notifications — the eight operations `openapi/trove-core.yaml` describes
 * for `/v1/notifications*`.
 *
 * **This is the whole of the mobile push story.** There is no push channel
 * and no device registration on this API (owner decision, 2026-09-03: no push
 * until a direct APNs/FCM path exists, no Expo relay) — `POST
 * /v1/notifications/devices` and its DELETE counterpart were dropped from the
 * plan and are deliberately absent from the contract. This app polls
 * {@link fetchUnreadCount} on foreground and on an interval instead; nothing
 * in this module or its hook ever asks for a push permission or mints a
 * device token.
 *
 * **Per member, not per household.** A notification is addressed to one
 * person: another member's row is 404, never 403, everywhere below.
 */

import { api } from './client';
import { pathSegment } from './paths';
import { expectSuccess, unwrap, unwrapPage } from './errors';
import type {
  ApiResponse,
  MarkedReadResponse,
  NotificationPreferencesResponse,
  NotificationPreferencesUpdateRequest,
  NotificationResponse,
  NotificationsQuery,
  Page,
  UnreadCountResponse,
} from './types';

/** `GET /v1/notifications` — one page of the caller's undismissed inbox, newest first. */
export async function fetchNotifications(
  query: NotificationsQuery = {}
): Promise<Page<NotificationResponse>> {
  const response = await api.get<ApiResponse<NotificationResponse[]>>('/v1/notifications', {
    params: query,
  });
  return unwrapPage(response.data);
}

/** `GET /v1/notifications/unread-count` — the number a bell badge shows. */
export async function fetchUnreadNotificationCount(): Promise<UnreadCountResponse> {
  const response = await api.get<ApiResponse<UnreadCountResponse>>(
    '/v1/notifications/unread-count'
  );
  return unwrap(response.data);
}

/** `PATCH /v1/notifications/{id}/read` — idempotent; the row stays in the inbox until dismissed. */
export async function markNotificationRead(id: string): Promise<NotificationResponse> {
  const response = await api.patch<ApiResponse<NotificationResponse>>(
    `/v1/notifications/${pathSegment(id)}/read`
  );
  return unwrap(response.data);
}

/**
 * `POST /v1/notifications/read-all` — marks every unread, undismissed
 * notification of the caller's read in one statement. `marked: 0` when there
 * was nothing unread.
 */
export async function markAllNotificationsRead(): Promise<MarkedReadResponse> {
  const response = await api.post<ApiResponse<MarkedReadResponse>>('/v1/notifications/read-all');
  return unwrap(response.data);
}

/**
 * `DELETE /v1/notifications/{id}` — hides the notification from the inbox and
 * the unread count permanently. The row is retained; idempotent.
 */
export async function dismissNotification(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/notifications/${pathSegment(id)}`);
  expectSuccess(response.data);
}

/**
 * `GET /v1/notifications/preferences` — created with the shipped defaults on
 * first access, so this never 404s. `emailEnabled` defaults to false.
 */
export async function fetchNotificationPreferences(): Promise<NotificationPreferencesResponse> {
  const response = await api.get<ApiResponse<NotificationPreferencesResponse>>(
    '/v1/notifications/preferences'
  );
  return unwrap(response.data);
}

/**
 * `PUT /v1/notifications/preferences` — a partial update despite the verb:
 * every field is optional and an absent one is left exactly as it was.
 */
export async function updateNotificationPreferences(
  body: NotificationPreferencesUpdateRequest
): Promise<NotificationPreferencesResponse> {
  const response = await api.put<ApiResponse<NotificationPreferencesResponse>>(
    '/v1/notifications/preferences',
    body
  );
  return unwrap(response.data);
}
