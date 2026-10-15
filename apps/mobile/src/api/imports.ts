/**
 * The CSV/OFX import pipeline — `/v1/imports*`.
 *
 * Four operations, in the order a member meets them:
 *
 *  1. `POST /v1/imports` (multipart) uploads the file and answers a
 *     **preview**. Nothing is written to the ledger — not one transaction row.
 *     The batch is created in status `PREVIEW`.
 *  2. `GET /v1/imports/{id}/preview` re-reads that preview. For a batch still
 *     in `PREVIEW` the file is re-classified against the ledger *as it is now*,
 *     which is what makes the counts current rather than remembered, and it is
 *     also how a client that lost the first response — a reload, a second
 *     device — picks the batch up again.
 *  3. `POST /v1/imports/{id}/commit` writes the rows. Its optional body
 *     supersedes the detected mapping; fields it omits keep their detected
 *     values. That is why there is no separate "update the mapping" route.
 *  4. `DELETE /v1/imports/{id}` undoes a committed batch, removing only the
 *     rows it inserted that nobody has touched since.
 *
 * `createImport` is the only multipart call in this family. The field names
 * below are the ones the operation's `multipart/form-data` schema declares:
 * `file`, `accountId` and `sourceKind`. `accountId` is **required for CSV**
 * (a CSV carries no account identity at all) and optional for OFX.
 */

import { api } from './client';
import { expectSuccess, unwrap } from './errors';
import { pathSegment } from './paths';
import type {
  ApiResponse,
  ImportCommitRequest,
  ImportPreviewResponse,
  ImportSourceKind,
  SyncLogResponse,
} from './types';

/** Multipart field name for the uploaded file. */
export const IMPORT_FILE_FIELD = 'file';
/** Multipart field name for the account the rows belong to. */
export const IMPORT_ACCOUNT_FIELD = 'accountId';
/** Multipart field name for the declared source kind; it narrows the sniff, it does not override it. */
export const IMPORT_SOURCE_KIND_FIELD = 'sourceKind';

/** A file chosen on the device, in the shape React Native's `FormData` takes. */
export interface ImportFile {
  uri: string;
  /** Kept for display only; the server mints its own storage key. */
  name: string;
  /**
   * Must be one of the types the operation accepts (`text/csv`, `text/plain`,
   * `application/csv`, `application/vnd.ms-excel`, `application/x-ofx`,
   * `application/x-qfx`, `application/ofx`, `application/octet-stream`) **and**
   * the file's first bytes must actually match the format it claims to be.
   */
  type: string;
}

export interface CreateImportInput {
  file: ImportFile;
  /** Required for CSV. For OFX it overrides the account the file's `ACCTID` resolves to. */
  accountId?: string;
  /** Optional — the format is sniffed from the first bytes; this only narrows the sniff. */
  sourceKind?: ImportSourceKind;
}

/**
 * `POST /v1/imports` — 201 with the preview. Writes nothing.
 *
 * 413 `upload-too-large` is one of three ceilings (the container's multipart
 * cap, the import's own per-file cap, the household's allowance for uploads
 * not yet committed); 400 `validation-error` covers a wrong content type, a
 * file whose bytes are not the format it claims, a missing `accountId` on a
 * CSV, or an `accountId` naming an account of another kind.
 */
export async function createImport(input: CreateImportInput): Promise<ImportPreviewResponse> {
  const form = new FormData();
  form.append(IMPORT_FILE_FIELD, {
    uri: input.file.uri,
    name: input.file.name,
    type: input.file.type,
  } as unknown as Blob);
  if (input.accountId !== undefined && input.accountId !== '') {
    form.append(IMPORT_ACCOUNT_FIELD, input.accountId);
  }
  if (input.sourceKind !== undefined) {
    form.append(IMPORT_SOURCE_KIND_FIELD, input.sourceKind);
  }

  const response = await api.post<ApiResponse<ImportPreviewResponse>>('/v1/imports', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return unwrap(response.data);
}

/** `GET /v1/imports/{id}/preview` — the same body the upload answered, recomputed. */
export async function fetchImportPreview(id: string): Promise<ImportPreviewResponse> {
  const response = await api.get<ApiResponse<ImportPreviewResponse>>(
    `/v1/imports/${pathSegment(id)}/preview`
  );
  return unwrap(response.data);
}

/**
 * `POST /v1/imports/{id}/commit` — writes the rows and answers the sync run.
 *
 * The body is optional and the mapping inside it is a *correction*: only the
 * fields the member changed are sent, and anything omitted keeps the value the
 * sniffer detected. 409 means the batch is not in `PREVIEW` — committing twice
 * is refused rather than deduplicated.
 */
export async function commitImport(
  id: string,
  body?: ImportCommitRequest
): Promise<SyncLogResponse> {
  const response = await api.post<ApiResponse<SyncLogResponse>>(
    `/v1/imports/${pathSegment(id)}/commit`,
    body
  );
  return unwrap(response.data);
}

/**
 * `DELETE /v1/imports/{id}` — undo a committed batch. `EmptySuccess`.
 *
 * Rows a member has since edited are deliberately left alone, still carrying
 * this batch's id as the record of where they came from. 409 means the batch
 * was never committed, or has already been undone.
 */
export async function undoImport(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/imports/${pathSegment(id)}`);
  expectSuccess(response.data);
}
