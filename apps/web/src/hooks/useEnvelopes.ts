/**
 * Envelopes — one period's list, and every envelope mutation (§4.6, step 202).
 *
 * See `useBudgetPeriods.ts` for why every mutation here invalidates the whole
 * `['budgets']` prefix rather than just this period's envelope key: `spent` is
 * the ledger's own rewritten figure, so a create/update/delete here can also
 * change what `healthPercentage` and `overPace` say for a period the member is
 * looking at from the current-period view at the same time.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createEnvelope, deleteEnvelope, listEnvelopes, updateEnvelope } from '@api/budgets'
import { invalidateBudgets } from './useBudgetPeriods'
import type { ApiError } from '@api/client'
import type { EnvelopeRequest, EnvelopeResponse, EnvelopeUpdateRequest } from '@api/types'

export const envelopesKey = (periodId: string) => ['budgets', 'envelopes', periodId] as const

/** `GET /v1/budgets/{id}/envelopes`. Not paged; a period accumulates a handful of rows. */
export function useEnvelopes(periodId: string | null) {
  return useQuery<EnvelopeResponse[], ApiError>({
    queryKey: envelopesKey(periodId ?? ''),
    queryFn: () => listEnvelopes(periodId as string),
    enabled: periodId !== null,
  })
}

export function useCreateEnvelopeMutation(periodId: string) {
  const qc = useQueryClient()
  return useMutation<EnvelopeResponse, ApiError, EnvelopeRequest>({
    mutationFn: (body) => createEnvelope(periodId, body),
    onSuccess: () => invalidateBudgets(qc),
  })
}

export function useUpdateEnvelopeMutation(periodId: string) {
  const qc = useQueryClient()
  return useMutation<EnvelopeResponse, ApiError, { envelopeId: string; body: EnvelopeUpdateRequest }>({
    mutationFn: ({ envelopeId, body }) => updateEnvelope(periodId, envelopeId, body),
    onSuccess: () => invalidateBudgets(qc),
  })
}

export function useDeleteEnvelopeMutation(periodId: string) {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: (envelopeId) => deleteEnvelope(periodId, envelopeId),
    onSuccess: () => invalidateBudgets(qc),
  })
}
