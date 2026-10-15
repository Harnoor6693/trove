/**
 * The caller's own AI endpoint settings — one query and four mutations.
 *
 * **What is deliberately NOT cached here: the API key.** `AiSettingsResponse`
 * cannot carry one, so the QUERY cache cannot hold one, and the update mutation
 * keeps it out of its `variables` so the MUTATION cache cannot either — see
 * `useUpdateAiSettingsMutation`. The key the member types lives in the settings
 * form's component state and, for the length of one request, in a ref. No hook
 * here returns, caches or logs a key value.
 *
 * Every mutation invalidates the settings query, and `updateAiSettings` also
 * invalidates the model list, because changing the endpoint changes which models
 * exist.
 */
import { useRef } from 'react'
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import {
  deleteAiApiKey,
  getAiSettings,
  listAiModels,
  testAiConnection,
  updateAiSettings,
} from '@api/ai'
import type { ApiError } from '@api/client'
import type {
  AiConnectionTestResponse,
  AiModel,
  AiSettingsResponse,
  AiSettingsUpdateRequest,
} from '@api/types'

export const aiSettingsKey = ['ai', 'settings'] as const
export const aiModelsKey = ['ai', 'models'] as const

function invalidateAi(qc: QueryClient): void {
  void qc.invalidateQueries({ queryKey: aiSettingsKey })
  void qc.invalidateQueries({ queryKey: aiModelsKey })
}

/**
 * `GET /v1/ai/settings`. Never 404s — a member who has saved nothing gets the
 * defaults — so `isError` here really is a failure and not "unconfigured".
 *
 * The Coach reads this too, to tell an unconfigured endpoint apart from a broken
 * one before it ever calls the completion route.
 */
export function useAiSettings() {
  return useQuery<AiSettingsResponse, ApiError>({
    queryKey: aiSettingsKey,
    queryFn: getAiSettings,
    staleTime: 30 * 1000,
  })
}

/**
 * `GET /v1/ai/models` — the picker's options.
 *
 * `enabled` is the caller's, because the operation needs a saved `endpointUrl`
 * and nothing else: asking before one is saved is a guaranteed 409, and asking
 * once one is saved works even though no model has been chosen yet. That is the
 * whole point — the member picks from the endpoint's real list rather than
 * typing a model id.
 *
 * `retry: false`: a 409 or a 502 here is an answer about the member's endpoint,
 * not a transient failure worth retrying at them.
 */
export function useAiModels(opts: { enabled?: boolean } = {}) {
  return useQuery<AiModel[], ApiError>({
    queryKey: aiModelsKey,
    queryFn: listAiModels,
    enabled: opts.enabled ?? true,
    retry: false,
    staleTime: 60 * 1000,
  })
}

/**
 * The part of the update that a mutation is allowed to remember: everything
 * except the key.
 */
export type AiSettingsSave = Omit<AiSettingsUpdateRequest, 'apiKey'>

/**
 * `PUT /v1/ai/settings` — a REPLACE. The caller must send the whole shape.
 *
 * **THE TYPED KEY IS NOT A MUTATION VARIABLE.** It is handed to `saveSettings`
 * out of band and read from a ref inside `mutationFn`, because TanStack Query
 * KEEPS `variables` on a settled mutation — the reducer's `success` branch
 * spreads the previous state and never clears them — and the `Mutation` object
 * stays in the mutation cache for the gc window after its last observer
 * detaches. A key passed as a variable would therefore still be readable from a
 * state dump or a devtools panel minutes after the save, which is the "the
 * client kept a copy of my secret" defect this whole surface exists to avoid.
 * The ref holds it only for the length of the request; `onSettled` clears it.
 */
export function useUpdateAiSettingsMutation() {
  const qc = useQueryClient()
  const apiKeyRef = useRef<string | null>(null)

  const mutation = useMutation<AiSettingsResponse, ApiError, AiSettingsSave>({
    mutationFn: (body) => {
      const apiKey = apiKeyRef.current
      // The request object is transient: nothing retains it after the call.
      return updateAiSettings(apiKey === null || apiKey === '' ? body : { ...body, apiKey })
    },
    onSuccess: (settings) => {
      qc.setQueryData(aiSettingsKey, settings)
      invalidateAi(qc)
    },
    // Cleared here rather than inside `mutationFn` so a retry still carries it.
    onSettled: () => {
      apiKeyRef.current = null
    },
  })

  /**
   * Send the whole settings shape, with an optional write-only key attached out
   * of band. `null` means "leave the stored key alone": a BLANK `apiKey` is a
   * 400 rather than a silent clear, so it is never sent.
   */
  function saveSettings(
    body: AiSettingsSave,
    apiKey: string | null,
    options?: Parameters<typeof mutation.mutate>[1]
  ): void {
    apiKeyRef.current = apiKey
    mutation.mutate(body, options)
  }

  return { ...mutation, saveSettings }
}

/** `DELETE /v1/ai/settings/api-key`. Idempotent; there is deliberately no 404. */
export function useDeleteAiApiKeyMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, void>({
    mutationFn: deleteAiApiKey,
    onSuccess: () => invalidateAi(qc),
  })
}

/**
 * `POST /v1/ai/settings/test`. Writes `lastTestAt` / `lastTestOk` on the row, so
 * a success invalidates the settings query even though the test itself changes
 * no configuration.
 */
export function useTestAiConnectionMutation() {
  const qc = useQueryClient()
  return useMutation<AiConnectionTestResponse, ApiError, void>({
    mutationFn: testAiConnection,
    onSettled: () => void qc.invalidateQueries({ queryKey: aiSettingsKey }),
  })
}
