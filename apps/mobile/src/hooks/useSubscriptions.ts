/**
 * React Query bindings for `/v1/subscriptions*`.
 *
 * There is no create or update mutation here because there is no such route:
 * a subscription is a detection, not something a member authors. `useScanSubscriptions`
 * and `useCancelSubscription` are the only two writes, and both invalidate the
 * summary and the alert list alongside the subscription list — a scan can
 * raise a fresh price-change alert, and a cancellation removes the row from
 * every aggregate figure.
 */

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  cancelSubscription,
  fetchSubscription,
  fetchSubscriptionAlerts,
  fetchSubscriptionSummary,
  fetchSubscriptions,
  markSubscriptionAlertRead,
  scanSubscriptions,
} from '@api/subscriptions';
import type {
  SubscriptionAlertResponse,
  SubscriptionResponse,
  SubscriptionSummaryResponse,
} from '@api/types';
import { retryUnlessRefused } from './retryPolicy';

export const subscriptionKeys = {
  all: () => ['subscriptions'] as const,
  list: () => ['subscriptions', 'list'] as const,
  summary: () => ['subscriptions', 'summary'] as const,
  alerts: () => ['subscriptions', 'alerts'] as const,
  detail: (id: string) => ['subscriptions', id] as const,
};

/** `GET /v1/subscriptions` — the household's active detections, merchant order. Not paged. */
export function useSubscriptions(): UseQueryResult<SubscriptionResponse[]> {
  return useQuery({
    queryKey: subscriptionKeys.list(),
    queryFn: fetchSubscriptions,
    staleTime: 30_000,
    retry: retryUnlessRefused,
  });
}

/** `GET /v1/subscriptions/summary` — per-currency run-rate and the next fourteen days. */
export function useSubscriptionSummary(): UseQueryResult<SubscriptionSummaryResponse> {
  return useQuery({
    queryKey: subscriptionKeys.summary(),
    queryFn: fetchSubscriptionSummary,
    staleTime: 30_000,
    retry: retryUnlessRefused,
  });
}

/** `GET /v1/subscriptions/alerts` — the household's unread alerts, newest first. Not paged. */
export function useSubscriptionAlerts(): UseQueryResult<SubscriptionAlertResponse[]> {
  return useQuery({
    queryKey: subscriptionKeys.alerts(),
    queryFn: fetchSubscriptionAlerts,
    staleTime: 30_000,
    retry: retryUnlessRefused,
  });
}

/** `PATCH /v1/subscriptions/alerts/{id}/read`. Idempotent. */
export function useMarkSubscriptionAlertRead(): UseMutationResult<
  SubscriptionAlertResponse,
  Error,
  string
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => markSubscriptionAlertRead(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: subscriptionKeys.alerts() });
    },
  });
}

/**
 * `POST /v1/subscriptions/scan` — runs detection for the caller's own
 * household right now. The response *is* the fresh active list, so it seeds
 * the cache directly; the summary and the alerts are invalidated because a
 * scan can change either.
 */
export function useScanSubscriptions(): UseMutationResult<SubscriptionResponse[], Error, void> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => scanSubscriptions(),
    onSuccess: (subscriptions) => {
      client.setQueryData(subscriptionKeys.list(), subscriptions);
      void client.invalidateQueries({ queryKey: subscriptionKeys.summary() });
      void client.invalidateQueries({ queryKey: subscriptionKeys.alerts() });
    },
  });
}

/** `GET /v1/subscriptions/{id}` — one detection, cancelled or not. Idle until an id is known. */
export function useSubscription(id: string): UseQueryResult<SubscriptionResponse> {
  return useQuery({
    queryKey: subscriptionKeys.detail(id),
    queryFn: () => fetchSubscription(id),
    enabled: id !== '',
    retry: retryUnlessRefused,
  });
}

/**
 * `DELETE /v1/subscriptions/{id}` — **soft** delete: Trove stops tracking the
 * charge. The row is retained and stays readable, so the detail cache is
 * invalidated (to pick up `cancelled: true`) rather than removed.
 */
export function useCancelSubscription(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => cancelSubscription(id),
    onSuccess: (_result, id) => {
      void client.invalidateQueries({ queryKey: subscriptionKeys.list() });
      void client.invalidateQueries({ queryKey: subscriptionKeys.summary() });
      void client.invalidateQueries({ queryKey: subscriptionKeys.detail(id) });
    },
  });
}
