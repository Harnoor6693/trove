/**
 * Webhooks — the list and the two mutations (§4.9, step 203). A security
 * surface: see `@api/webhooks` for why no secret ever appears here.
 */
import { useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createWebhook, deleteWebhook, listWebhooks } from '@api/webhooks'
import type { ApiError } from '@api/client'
import type { WebhookCreateRequest, WebhookResponse } from '@api/types'

export const webhooksKey = ['webhooks'] as const

/** The part of the create body a mutation is allowed to remember: everything except the secret. */
export type WebhookCreateSave = Omit<WebhookCreateRequest, 'secret'>

/** `GET /v1/webhooks` — the caller's own subscriptions, disabled ones included. */
export function useWebhooks() {
  return useQuery<WebhookResponse[], ApiError>({
    queryKey: webhooksKey,
    queryFn: listWebhooks,
    staleTime: 60 * 1000,
  })
}

/**
 * `POST /v1/webhooks`. **THE TYPED SECRET IS NOT A MUTATION VARIABLE.** Same
 * reasoning as `useUpdateAiSettingsMutation`: TanStack Query keeps `variables`
 * on a settled mutation for the gc window after its last observer detaches, so
 * a secret passed as a variable would still be readable from
 * `queryClient.getMutationCache().getAll()` minutes after the dialog that
 * showed it has closed. The secret is instead handed to `saveWebhook` out of
 * band, read from a ref inside `mutationFn`, and cleared in `onSettled`.
 */
export function useCreateWebhookMutation() {
  const qc = useQueryClient()
  const secretRef = useRef<string>('')

  const mutation = useMutation<WebhookResponse, ApiError, WebhookCreateSave>({
    mutationFn: (body) => createWebhook({ ...body, secret: secretRef.current }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: webhooksKey }),
    // Cleared here rather than inside `mutationFn` so a retry still carries it.
    onSettled: () => {
      secretRef.current = ''
    },
  })

  function saveWebhook(
    body: WebhookCreateSave,
    secret: string,
    options?: Parameters<typeof mutation.mutate>[1]
  ): void {
    secretRef.current = secret
    mutation.mutate(body, options)
  }

  return { ...mutation, saveWebhook }
}

export function useDeleteWebhookMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: deleteWebhook,
    onSuccess: () => void qc.invalidateQueries({ queryKey: webhooksKey }),
  })
}
