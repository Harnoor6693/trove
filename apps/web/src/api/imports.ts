/**
 * `/v1/imports` — the CSV / OFX preview → commit → undo pipeline.
 *
 * The shape of this pipeline is the whole reason there is no "update the
 * mapping" route: `POST /v1/imports` uploads and PREVIEWS (it writes not one
 * transaction row), the member corrects what the sniffer guessed, and the
 * correction travels with `POST /v1/imports/{id}/commit`. `DELETE
 * /v1/imports/{id}` takes a committed batch back out again.
 */
import { api, unwrap, unwrapVoid } from './client'
import type {
  ApiResponse,
  ImportCommitRequest,
  ImportPreviewResponse,
  ImportSourceKind,
  SyncLogResponse,
} from './types'

/** The parts `POST /v1/imports` accepts, as a caller supplies them. */
export interface CreateImportInput {
  file: File
  /** Required for CSV — a CSV file carries no account identity. Optional for OFX. */
  accountId?: string
  /** Narrows the format sniff; it never overrides it. */
  sourceKind?: ImportSourceKind
}

/**
 * `POST /v1/imports` — multipart, 201, status `PREVIEW`. **Nothing is written to
 * the ledger by this call.**
 *
 * `Content-Type` is declared as multipart so axios' JSON request transform
 * leaves the `FormData` alone; the XHR adapter then drops the header again so
 * the browser can supply the real one with its boundary. Setting it is
 * therefore load-bearing, not decoration — with the instance's default
 * `application/json` still in force, axios would serialise the FormData to JSON
 * and the upload would arrive with no file at all.
 *
 * 413 `upload-too-large` is the file exceeding one of three ceilings; 400
 * `validation-error` covers a format that is not what it claims to be, a missing
 * `accountId` on a CSV, and an `accountId` naming an account of another kind.
 */
export async function createImport(input: CreateImportInput): Promise<ImportPreviewResponse> {
  const form = new FormData()
  // The filename is passed explicitly rather than left to be inferred from the
  // File: the third argument is the one the multipart part's `filename`
  // parameter is built from, and without it some XHR paths send `blob`. The
  // server keeps `originalFilename` for display only — it is never part of a
  // storage key — so a lost name is a preview that cannot say which file it is.
  form.append('file', input.file, input.file.name)
  if (input.accountId !== undefined) form.append('accountId', input.accountId)
  if (input.sourceKind !== undefined) form.append('sourceKind', input.sourceKind)

  const res = await api.post<ApiResponse<ImportPreviewResponse>>('/v1/imports', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return unwrap(res)
}

/**
 * `GET /v1/imports/{id}/preview`.
 *
 * For a batch still in `PREVIEW` the file is re-read and re-classified against
 * the ledger as it is NOW, so the counts are current rather than remembered. For
 * a committed or undone batch the stored counts come back, `rows` is empty and
 * `warnings` says why — which is what lets the committed-batch card stay honest
 * after the wizard has closed.
 */
export async function getImportPreview(id: string): Promise<ImportPreviewResponse> {
  const res = await api.get<ApiResponse<ImportPreviewResponse>>(
    `/v1/imports/${encodeURIComponent(id)}/preview`
  )
  return unwrap(res)
}

/**
 * `POST /v1/imports/{id}/commit` — writes the rows and answers with the
 * `sync_log` the commit produced.
 *
 * The body is optional and supersedes the detected mapping; fields it omits keep
 * their detected values, so only the member's actual corrections are sent.
 * 409 means the batch is not in `PREVIEW` — committing twice is refused rather
 * than deduplicated.
 */
export async function commitImport(
  id: string,
  body?: ImportCommitRequest
): Promise<SyncLogResponse> {
  const res = await api.post<ApiResponse<SyncLogResponse>>(
    `/v1/imports/${encodeURIComponent(id)}/commit`,
    body ?? null
  )
  return unwrap(res)
}

/**
 * `DELETE /v1/imports/{id}` — removes the rows this batch inserted **that nobody
 * has touched since**, and marks the batch `UNDONE`. `EmptySuccess`, so
 * `unwrapVoid`. 409 means the batch was never committed.
 */
export async function undoImport(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(`/v1/imports/${encodeURIComponent(id)}`)
  unwrapVoid(res)
}
