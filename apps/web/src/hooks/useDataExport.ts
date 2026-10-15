/**
 * The member's own full-data export, and the self-deletion that sits beside it
 * (§4.2, readiness gap P2-16, step 205).
 *
 * The run list polls itself while any row on the current page is non-terminal,
 * which is the same shape `useTaxExportRuns` uses — a run has no separate poll
 * channel and the list answers fresher data on every fetch.
 *
 * The download is NOT a query: it is a one-shot authenticated fetch a button
 * triggers, exactly like the tax export and the receipt download, so it has no
 * hook here — see `Settings/DataSection.tsx`.
 */
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { createDataExport, listDataExports } from '@api/dataexport'
import { deleteMyAccount } from '@api/me'
import { useCredentialMutation } from './useCredentialMutation'
import type { ApiError } from '@api/client'
import type { DataExportRunResponse, DataExportsQuery, Page, StepUpRequest } from '@api/types'

export const dataExportKey = ['me', 'export'] as const
export const dataExportsListKey = (q: DataExportsQuery) => ['me', 'export', 'list', q] as const

/**
 * `GET /v1/me/export?page&limit`. Polls every three seconds while the current
 * page holds a `PENDING` or `GENERATING` row and stops the moment every row on
 * it is terminal (`COMPLETE` / `FAILED`).
 */
export function useDataExports(q: DataExportsQuery = {}) {
  return useQuery<Page<DataExportRunResponse>, ApiError>({
    queryKey: dataExportsListKey(q),
    queryFn: () => listDataExports(q),
    placeholderData: keepPreviousData,
    refetchInterval: (query) => {
      const rows = query.state.data?.rows ?? []
      const generating = rows.some((r) => r.status === 'PENDING' || r.status === 'GENERATING')
      return generating ? 3000 : false
    },
  })
}

/**
 * `POST /v1/me/export` — step-up. The `StepUpRequest` goes out of band through
 * `useCredentialMutation`, so the member's password and TOTP code are never a
 * mutation variable and never reach the mutation cache.
 */
export function useCreateDataExportMutation() {
  const qc = useQueryClient()
  return useCredentialMutation<DataExportRunResponse, StepUpRequest>(createDataExport, {
    onSuccess: () => void qc.invalidateQueries({ queryKey: dataExportKey }),
  })
}

/**
 * `POST /v1/me/delete` — step-up, the POST twin. Same credential handling.
 * On success the caller clears the local session: every server-side session of
 * this account was revoked the instant the call landed.
 */
export function useDeleteMyAccountMutation() {
  return useCredentialMutation<void, StepUpRequest>(deleteMyAccount)
}
