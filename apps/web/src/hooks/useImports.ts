/**
 * The import pipeline's three mutations and its preview query.
 *
 * Upload and preview write nothing, so neither invalidates anything. Commit and
 * undo both move real rows in and out of the ledger, so both invalidate the
 * accounts family, the transactions family and the dashboard.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { commitImport, createImport, getImportPreview, undoImport, type CreateImportInput } from '@api/imports'
import { invalidateAccountsAndLedger } from './useAccounts'
import type { ApiError } from '@api/client'
import type { ImportCommitRequest, ImportPreviewResponse, SyncLogResponse } from '@api/types'

export const importsKey = ['imports'] as const
export const importPreviewKey = (id: string) => ['imports', 'preview', id] as const

/** `POST /v1/imports` — upload and preview. Writes nothing, so invalidates nothing. */
export function useCreateImportMutation() {
  return useMutation<ImportPreviewResponse, ApiError, CreateImportInput>({
    mutationFn: createImport,
  })
}

/**
 * `GET /v1/imports/{id}/preview`. Used to re-read a batch the member still has
 * open, and to keep the committed-batch card honest after the wizard closes.
 */
export function useImportPreview(id: string | null) {
  return useQuery<ImportPreviewResponse, ApiError>({
    queryKey: importPreviewKey(id ?? ''),
    queryFn: () => getImportPreview(id as string),
    enabled: id !== null,
    staleTime: 0,
  })
}

export function useCommitImportMutation() {
  const qc = useQueryClient()
  return useMutation<SyncLogResponse, ApiError, { id: string; body?: ImportCommitRequest }>({
    mutationFn: ({ id, body }) => commitImport(id, body),
    onSuccess: (_data, { id }) => {
      invalidateAccountsAndLedger(qc)
      void qc.invalidateQueries({ queryKey: importPreviewKey(id) })
    },
  })
}

export function useUndoImportMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: undoImport,
    onSuccess: (_data, id) => {
      invalidateAccountsAndLedger(qc)
      void qc.invalidateQueries({ queryKey: importPreviewKey(id) })
    },
  })
}
