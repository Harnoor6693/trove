/**
 * Notifications — the paged inbox, the unread count the shell polls, and the
 * mark-read / dismiss / preferences mutations (§4.9, step 203).
 *
 * `useUnreadCount` is what drives the shell's bell badge: refetched on a sane
 * interval AND on window focus, never over a socket (owner decision,
 * 2026-09-03: no push, no device token, no Expo relay).
 */
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import {
  dismissNotification,
  getNotificationPreferences,
  getUnreadNotificationCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  updateNotificationPreferences,
} from '@api/notifications'
import type { ApiError } from '@api/client'
import type {
  MarkedReadResponse,
  NotificationPreferencesResponse,
  NotificationPreferencesUpdateRequest,
  NotificationResponse,
  NotificationsQuery,
  Page,
  UnreadCountResponse,
} from '@api/types'

export const notificationsKey = ['notifications'] as const
export const notificationsListKey = (q: NotificationsQuery) =>
  ['notifications', 'list', q] as const
export const unreadCountKey = ['notifications', 'unread-count'] as const
export const notificationPreferencesKey = ['notifications', 'preferences'] as const

function invalidateNotifications(qc: QueryClient): void {
  void qc.invalidateQueries({ queryKey: notificationsKey })
}

/** `GET /v1/notifications?page&limit` — undismissed only. PAGED. */
export function useNotifications(q: NotificationsQuery = {}) {
  return useQuery<Page<NotificationResponse>, ApiError>({
    queryKey: notificationsListKey(q),
    queryFn: () => listNotifications(q),
    staleTime: 15 * 1000,
  })
}

/**
 * `GET /v1/notifications/unread-count`, polled every thirty seconds AND
 * refetched when the tab regains focus (`refetchOnWindowFocus`, on by
 * default and left explicit here) — no socket, ever.
 */
export function useUnreadCount() {
  return useQuery<UnreadCountResponse, ApiError>({
    queryKey: unreadCountKey,
    queryFn: getUnreadNotificationCount,
    refetchInterval: 30 * 1000,
    refetchOnWindowFocus: true,
    staleTime: 10 * 1000,
  })
}

export function useMarkNotificationReadMutation() {
  const qc = useQueryClient()
  return useMutation<NotificationResponse, ApiError, string>({
    mutationFn: markNotificationRead,
    onSuccess: () => invalidateNotifications(qc),
  })
}

export function useMarkAllNotificationsReadMutation() {
  const qc = useQueryClient()
  return useMutation<MarkedReadResponse, ApiError, void>({
    mutationFn: markAllNotificationsRead,
    onSuccess: () => invalidateNotifications(qc),
  })
}

export function useDismissNotificationMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: dismissNotification,
    onSuccess: () => invalidateNotifications(qc),
  })
}

export function useNotificationPreferences() {
  return useQuery<NotificationPreferencesResponse, ApiError>({
    queryKey: notificationPreferencesKey,
    queryFn: getNotificationPreferences,
    staleTime: 60 * 1000,
  })
}

export function useUpdateNotificationPreferencesMutation() {
  const qc = useQueryClient()
  return useMutation<
    NotificationPreferencesResponse,
    ApiError,
    NotificationPreferencesUpdateRequest
  >({
    mutationFn: updateNotificationPreferences,
    onSuccess: () => void qc.invalidateQueries({ queryKey: notificationPreferencesKey }),
  })
}
