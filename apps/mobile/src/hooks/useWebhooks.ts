/**
 * React Query bindings for `/v1/webhooks*`.
 *
 * Nothing here ever holds `secret`: it is write-only component state on the
 * form that is typing it, handed straight to {@link createWebhook}, and never
 * written to this cache — `WebhookResponse` has no field for it, so there is
 * nothing to cache even if a screen tried.
 */

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { createWebhook, deleteWebhook, fetchWebhooks } from '@api/webhooks';
import type { WebhookCreateRequest, WebhookResponse } from '@api/types';
import { retryUnlessRefused } from './retryPolicy';

export const webhookKeys = {
  all: () => ['webhooks'] as const,
  list: () => ['webhooks', 'list'] as const,
};

/** `GET /v1/webhooks` — the caller's own subscriptions, disabled ones included. Not paged. */
export function useWebhooks(): UseQueryResult<WebhookResponse[]> {
  return useQuery({
    queryKey: webhookKeys.list(),
    queryFn: fetchWebhooks,
    staleTime: 30_000,
    retry: retryUnlessRefused,
  });
}

/** `POST /v1/webhooks`. The response never carries `secret`. */
export function useCreateWebhook(): UseMutationResult<
  WebhookResponse,
  Error,
  WebhookCreateRequest
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: WebhookCreateRequest) => createWebhook(body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: webhookKeys.list() });
    },
  });
}

/** `DELETE /v1/webhooks/{id}` — removes the subscription and its delivery log with it. */
export function useDeleteWebhook(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteWebhook(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: webhookKeys.list() });
    },
  });
}
