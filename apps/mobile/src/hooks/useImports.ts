/**
 * React Query bindings for `/v1/imports*`.
 *
 * The preview is a *query* and the three writes are *mutations*, which matches
 * what the contract says each does: `POST /v1/imports` and
 * `GET /v1/imports/{id}/preview` both answer the same `ImportPreviewResponse`
 * and neither writes a transaction, but only the second is safe to refetch, so
 * only the second is a query.
 *
 * A commit and an undo both change the ledger, so both invalidate the
 * transaction and dashboard roots as well as the batch's own preview.
 */

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { commitImport, createImport, fetchImportPreview, undoImport } from '@api/imports';
import type { CreateImportInput } from '@api/imports';
import { discardPickedFile } from '@lib/importFilePicker';
import type { ImportCommitRequest, ImportPreviewResponse, SyncLogResponse } from '@api/types';
import { accountKeys, dashboardKeys, importKeys, transactionKeys } from './queryKeys';
import { retryUnlessRefused } from './retryPolicy';

export { importKeys } from './queryKeys';

/**
 * `GET /v1/imports/{id}/preview`. Idle until a batch id is known.
 *
 * `staleTime: 0` on purpose: for a batch still in `PREVIEW` the server
 * re-classifies the file against the ledger as it is *now*, so a row another
 * member has since entered by hand shows up as a duplicate. A cached preview
 * would tell the member the counts were something they no longer are, and they
 * are about to commit on the strength of them.
 */
export function useImportPreview(id: string): UseQueryResult<ImportPreviewResponse> {
  return useQuery({
    queryKey: importKeys.preview(id),
    queryFn: () => fetchImportPreview(id),
    enabled: id !== '',
    staleTime: 0,
    retry: retryUnlessRefused,
  });
}

/** `POST /v1/imports` — uploads the file and answers the preview. Writes nothing. */
export function useCreateImport(): UseMutationResult<
  ImportPreviewResponse,
  unknown,
  CreateImportInput
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateImportInput) => createImport(input),
    onSuccess: (preview) => {
      client.setQueryData(importKeys.preview(preview.id), preview);
    },
    // Success or failure, the statement has been read: iOS's copy of it goes.
    onSettled: (_preview, _error, input) => discardPickedFile(input.file),
  });
}

/** `POST /v1/imports/{id}/commit` — writes the rows; the optional mapping supersedes the detected one. */
export function useCommitImport(
  id: string
): UseMutationResult<SyncLogResponse, unknown, ImportCommitRequest | undefined> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: ImportCommitRequest | undefined) => commitImport(id, body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: importKeys.preview(id) });
      void client.invalidateQueries({ queryKey: transactionKeys.all() });
      void client.invalidateQueries({ queryKey: accountKeys.all() });
      void client.invalidateQueries({ queryKey: dashboardKeys.all() });
    },
  });
}

/** `DELETE /v1/imports/{id}` — takes back the rows this batch inserted that nobody has touched. */
export function useUndoImport(): UseMutationResult<void, unknown, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => undoImport(id),
    onSuccess: (_void, id) => {
      void client.invalidateQueries({ queryKey: importKeys.preview(id) });
      void client.invalidateQueries({ queryKey: transactionKeys.all() });
      void client.invalidateQueries({ queryKey: accountKeys.all() });
      void client.invalidateQueries({ queryKey: dashboardKeys.all() });
    },
  });
}
