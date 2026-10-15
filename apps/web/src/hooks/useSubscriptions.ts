/**
 * Subscriptions — the active list, the summary, alerts and the scan/cancel
 * actions (§4.8, step 203).
 *
 * Every mutation invalidates the whole `['subscriptions']` prefix: a scan can
 * change the list, the summary AND the alerts in one call, and cancelling one
 * changes the summary's run-rate too.
 */
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import {
  cancelSubscription,
  getSubscription,
  getSubscriptionSummary,
  listSubscriptionAlerts,
  listSubscriptions,
  markSubscriptionAlertRead,
  scanSubscriptions,
} from '@api/subscriptions'
import type { ApiError } from '@api/client'
import type {
  SubscriptionAlertResponse,
  SubscriptionResponse,
  SubscriptionSummaryResponse,
} from '@api/types'

export const subscriptionsKey = ['subscriptions'] as const
export const subscriptionsListKey = ['subscriptions', 'list'] as const
export const subscriptionSummaryKey = ['subscriptions', 'summary'] as const
export const subscriptionAlertsKey = ['subscriptions', 'alerts'] as const
export const subscriptionKey = (id: string) => ['subscriptions', 'detail', id] as const

function invalidateSubscriptions(qc: QueryClient): void {
  void qc.invalidateQueries({ queryKey: subscriptionsKey })
}

/** `GET /v1/subscriptions` — active only. Not paged. */
export function useSubscriptions() {
  return useQuery<SubscriptionResponse[], ApiError>({
    queryKey: subscriptionsListKey,
    queryFn: listSubscriptions,
    staleTime: 30 * 1000,
  })
}

/** `GET /v1/subscriptions/summary` — per-currency totals, never blended. */
export function useSubscriptionSummary() {
  return useQuery<SubscriptionSummaryResponse, ApiError>({
    queryKey: subscriptionSummaryKey,
    queryFn: getSubscriptionSummary,
    staleTime: 30 * 1000,
  })
}

/** `GET /v1/subscriptions/alerts` — unread only. Not paged. */
export function useSubscriptionAlerts() {
  return useQuery<SubscriptionAlertResponse[], ApiError>({
    queryKey: subscriptionAlertsKey,
    queryFn: listSubscriptionAlerts,
    staleTime: 30 * 1000,
  })
}

/** `GET /v1/subscriptions/{id}`. A cancelled row is still readable. */
export function useSubscription(id: string | null) {
  return useQuery<SubscriptionResponse, ApiError>({
    queryKey: subscriptionKey(id ?? ''),
    queryFn: () => getSubscription(id as string),
    enabled: id !== null,
  })
}

export function useMarkSubscriptionAlertReadMutation() {
  const qc = useQueryClient()
  return useMutation<SubscriptionAlertResponse, ApiError, string>({
    mutationFn: markSubscriptionAlertRead,
    onSuccess: () => invalidateSubscriptions(qc),
  })
}

/** `POST /v1/subscriptions/scan` — a manual detection trigger; the caller renders its own in-flight state from `isPending`. */
export function useScanSubscriptionsMutation() {
  const qc = useQueryClient()
  return useMutation<SubscriptionResponse[], ApiError, void>({
    mutationFn: scanSubscriptions,
    onSuccess: () => invalidateSubscriptions(qc),
  })
}

/**
 * `DELETE /v1/subscriptions/{id}` — a SOFT delete. Trove stops tracking the
 * merchant; nothing is cancelled with the merchant itself.
 */
export function useCancelSubscriptionMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: cancelSubscription,
    onSuccess: () => invalidateSubscriptions(qc),
  })
}
