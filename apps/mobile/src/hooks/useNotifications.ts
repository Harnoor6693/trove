/**
 * React Query bindings for `/v1/notifications*`.
 *
 * **`useUnreadNotificationCount` is the whole of this app's push story.**
 * There is no push channel on this API (owner decision, 2026-09-03) so this
 * hook polls on a plain interval AND refetches the moment the app returns to
 * the foreground (`AppState` — React Native has no window-focus event), and
 * nothing here ever asks for a notification permission or mints a device
 * token.
 *
 * The inbox is a `useInfiniteQuery`, bounded by `meta` the same way
 * `useReceipts`/`useTaxExportRuns` are: `GET /v1/notifications`'s 200 requires
 * `data` *and* `meta`, and a `FlatList` fed from page one alone would show a
 * member a truncated inbox they were never told about.
 */

import { useEffect } from 'react';
import { AppState, type AppStateStatus } from 'react-native';
import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
  type UseInfiniteQueryResult,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  dismissNotification,
  fetchNotificationPreferences,
  fetchNotifications,
  fetchUnreadNotificationCount,
  markAllNotificationsRead,
  markNotificationRead,
  updateNotificationPreferences,
} from '@api/notifications';
import type {
  MarkedReadResponse,
  NotificationPreferencesResponse,
  NotificationPreferencesUpdateRequest,
  NotificationResponse,
  Page,
  UnreadCountResponse,
} from '@api/types';
import { retryUnlessRefused } from './retryPolicy';

export const notificationKeys = {
  all: () => ['notifications'] as const,
  list: () => ['notifications', 'list'] as const,
  unreadCount: () => ['notifications', 'unread-count'] as const,
  preferences: () => ['notifications', 'preferences'] as const,
};

/** How often the unread badge is re-asked while the app sits open. */
export const UNREAD_COUNT_POLL_MS = 60_000;

/** The next zero-based page, or `undefined` at the end — same rule as {@link nextReceiptPage}. */
export function nextNotificationPage(last: Page<NotificationResponse>): number | undefined {
  const { page, limit, total } = last.meta;
  if (limit <= 0) return undefined;
  const seen = (page + 1) * limit;
  return seen < total ? page + 1 : undefined;
}

/** `GET /v1/notifications`, page by page — the caller's own undismissed inbox, newest first. */
export function useNotifications(): UseInfiniteQueryResult<
  InfiniteData<Page<NotificationResponse>>
> {
  return useInfiniteQuery({
    queryKey: notificationKeys.list(),
    initialPageParam: 0,
    queryFn: ({ pageParam }) => fetchNotifications({ page: pageParam as number }),
    getNextPageParam: nextNotificationPage,
    staleTime: 15_000,
    retry: retryUnlessRefused,
  });
}

/**
 * `GET /v1/notifications/unread-count`, kept fresh two ways: a plain interval
 * and an immediate refetch on foreground. Neither path is a push
 * notification — both are this app asking the server, on its own, while it is
 * open or just reopened.
 */
export function useUnreadNotificationCount(): UseQueryResult<UnreadCountResponse> {
  const client = useQueryClient();

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (next: AppStateStatus) => {
      if (next === 'active') {
        void client.invalidateQueries({ queryKey: notificationKeys.unreadCount() });
      }
    });
    return () => subscription.remove();
  }, [client]);

  return useQuery({
    queryKey: notificationKeys.unreadCount(),
    queryFn: fetchUnreadNotificationCount,
    staleTime: 15_000,
    refetchInterval: UNREAD_COUNT_POLL_MS,
    retry: retryUnlessRefused,
  });
}

/** `PATCH /v1/notifications/{id}/read`. Idempotent; the row stays in the inbox until dismissed. */
export function useMarkNotificationRead(): UseMutationResult<NotificationResponse, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => markNotificationRead(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: notificationKeys.list() });
      void client.invalidateQueries({ queryKey: notificationKeys.unreadCount() });
    },
  });
}

/**
 * `POST /v1/notifications/read-all` — the response's `marked` count is what
 * lets the badge zero out without a second fetch, so it is written directly
 * rather than waiting on the invalidated query.
 */
export function useMarkAllNotificationsRead(): UseMutationResult<MarkedReadResponse, Error, void> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => markAllNotificationsRead(),
    onSuccess: () => {
      client.setQueryData(notificationKeys.unreadCount(), { unread: 0 });
      void client.invalidateQueries({ queryKey: notificationKeys.list() });
    },
  });
}

/**
 * `DELETE /v1/notifications/{id}` — hides the row from the inbox and the
 * unread count permanently. Idempotent.
 */
export function useDismissNotification(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => dismissNotification(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: notificationKeys.list() });
      void client.invalidateQueries({ queryKey: notificationKeys.unreadCount() });
    },
  });
}

/**
 * `GET /v1/notifications/preferences` — created with the shipped defaults on
 * first access, so `data === undefined` here means "not loaded yet" and
 * never "not configured".
 */
export function useNotificationPreferences(): UseQueryResult<NotificationPreferencesResponse> {
  return useQuery({
    queryKey: notificationKeys.preferences(),
    queryFn: fetchNotificationPreferences,
    staleTime: 60_000,
    retry: retryUnlessRefused,
  });
}

/**
 * `PUT /v1/notifications/preferences` — a partial update. The response *is*
 * the whole row after the write, so the cache is written directly.
 */
export function useUpdateNotificationPreferences(): UseMutationResult<
  NotificationPreferencesResponse,
  Error,
  NotificationPreferencesUpdateRequest
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: NotificationPreferencesUpdateRequest) => updateNotificationPreferences(body),
    onSuccess: (preferences) => {
      client.setQueryData(notificationKeys.preferences(), preferences);
    },
  });
}
