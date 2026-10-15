/**
 * `/v1/receipts*` — the nine receipt operations.
 *
 * Two shapes here are easy to get wrong and both have already cost this project
 * a defect:
 *
 *  - **`GET /v1/receipts` is PAGED and enveloped.** Its 200 requires BOTH `data`
 *    (an array of `ReceiptSummaryResponse`) and `meta` (`PaginationMeta`). There
 *    is no bare array on this API. A client that returned
 *    `Promise<ReceiptResponse[]>` and dropped `meta` would show the server's
 *    first page and have no idea the rest existed — the same silent truncation
 *    step 201 fixed on transactions, and the exact bug the mobile client still
 *    has (handoff 15 §9). `listReceipts` returns a `Page<T>`.
 *  - **`POST /v1/receipts/upload` answers 202 with three fields.** `{id, status,
 *    createdAt}` and nothing else, deliberately: the ROW exists the moment it
 *    returns but its fields do not, and a fuller shape would invite a screen to
 *    render an empty receipt. Processing continues in the background; poll
 *    `getReceiptStatus` until `COMPLETE` or `FAILED`.
 *
 * `COMPLETE` with `reviewRequired: true` is the ORDINARY outcome on an install
 * with no vision model — "the image is stored and the fields are yours to fill
 * in", not "something went wrong". Nothing in this module treats it as an error.
 */
import { api, unwrap, unwrapVoid } from './client'
import { fetchAuthenticatedFile, type AuthenticatedFile } from './binary'
import type {
  ApiResponse,
  Page,
  PaginationMeta,
  ReceiptPatchRequest,
  ReceiptResponse,
  ReceiptReviewRequest,
  ReceiptSource,
  ReceiptStatusResponse,
  ReceiptSummaryResponse,
  ReceiptUploadResponse,
  ReceiptsQuery,
} from './types'

/** The two content types `uploadReceipt` accepts. HEIC and PDF are refused by design. */
export const ACCEPTED_RECEIPT_TYPES = ['image/jpeg', 'image/png'] as const

const path = (id: string, suffix = '') => `/v1/receipts/${encodeURIComponent(id)}${suffix}`

/* -------------------------------------------------------------- the list */

/**
 * `GET /v1/receipts` — one page of the HOUSEHOLD's receipts, newest first.
 *
 * A receipt is shared ledger data: every member sees every receipt, whoever
 * uploaded it. The uploader is recorded on the row but is not the scope.
 */
export async function listReceipts(
  q: ReceiptsQuery = {}
): Promise<Page<ReceiptSummaryResponse>> {
  const params: Record<string, string | number> = {}
  if (q.page !== undefined) params.page = q.page
  if (q.limit !== undefined) params.limit = q.limit

  const res = await api.get<ApiResponse<ReceiptSummaryResponse[]>>('/v1/receipts', { params })
  const rows = unwrap(res)
  const meta = res.data.meta
  if (!meta) {
    // The operation's 200 requires `meta`. Inventing a page count from
    // `rows.length` is exactly the silent truncation this guard exists to stop.
    throw new Error('GET /v1/receipts answered without the required pagination meta')
  }
  return { rows, meta: meta satisfies PaginationMeta }
}

/* ------------------------------------------------------------- the upload */

export interface UploadReceiptInput {
  file: File
  /** `UPLOAD` (a chosen file) or `SCAN` (a photograph). Defaults to `UPLOAD` server-side. */
  source?: ReceiptSource
}

/**
 * `POST /v1/receipts/upload` — multipart, **202**.
 *
 * Only JPEG and PNG are accepted, and the declared content type must agree with
 * the first bytes. HEIC is refused on purpose (the mobile client converts before
 * it uploads) and PDF is not accepted in this version at all.
 *
 * **The bytes that are stored are not the bytes that were sent**: every accepted
 * image is re-encoded to JPEG at quality 85, at most 2000 px on its long edge,
 * and only the re-encoding is written. That is what removes EXIF — the GPS
 * coordinates a phone camera writes into a receipt photograph included — by
 * construction. A consequence worth knowing at the call site: the download is
 * always `image/jpeg`, whatever was sent.
 *
 * 413 `upload-too-large` is one of three ceilings, all answering one slug
 * because from a client's point of view there is one refusal. 400
 * `validation-error` covers an empty part, an unaccepted content type, bytes
 * that are not the format they claim, and an undecodable image.
 *
 * `Content-Type: multipart/form-data` is declared so axios' JSON request
 * transform leaves the `FormData` alone; the adapter then drops the header again
 * so the browser can supply the real one with its boundary. With the instance's
 * default `application/json` still in force the upload would arrive with no file
 * at all. The filename is passed explicitly for the same reason it is on
 * imports — without it some XHR paths send `blob`.
 */
export async function uploadReceipt(
  input: UploadReceiptInput
): Promise<ReceiptUploadResponse> {
  const form = new FormData()
  form.append('file', input.file, input.file.name)
  if (input.source !== undefined) form.append('source', input.source)

  const res = await api.post<ApiResponse<ReceiptUploadResponse>>('/v1/receipts/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  return unwrap(res)
}

/* --------------------------------------------------------- one receipt */

/**
 * `GET /v1/receipts/{id}` — the whole row, with its line items, the Canadian tax
 * split and the matched transaction. Resolved inside the caller's household: an
 * id from another household is 404, never 403.
 */
export async function getReceipt(id: string): Promise<ReceiptResponse> {
  const res = await api.get<ApiResponse<ReceiptResponse>>(path(id))
  return unwrap(res)
}

/**
 * `GET /v1/receipts/{id}/status` — the four fields a poller needs, so watching a
 * receipt through its pipeline costs a fraction of fetching the whole row.
 * `COMPLETE` and `FAILED` are terminal; `isTerminalReceiptStatus` below is the
 * one place that rule is written down.
 */
export async function getReceiptStatus(id: string): Promise<ReceiptStatusResponse> {
  const res = await api.get<ApiResponse<ReceiptStatusResponse>>(path(id, '/status'))
  return unwrap(res)
}

/** The two terminal statuses. Stop polling on either. */
export function isTerminalReceiptStatus(status: ReceiptStatusResponse['status']): boolean {
  return status === 'COMPLETE' || status === 'FAILED'
}

/**
 * `PATCH /v1/receipts/{id}` — the manual path, and on an install with no vision
 * model the ONLY path.
 *
 * Every field is optional; an absent field is left alone and a present one is
 * written. There is **no way to clear a field back to null** through this
 * operation, so a caller must not send a key it means to leave alone — this
 * module passes the body through verbatim and never merges the row's current
 * values in. A body with no property at all is 400.
 *
 * A receipt whose pipeline is still running is **409 `conflict`**: the pipeline
 * would overwrite whatever was just written. Wait for `COMPLETE` or `FAILED`.
 */
export async function patchReceipt(
  id: string,
  body: ReceiptPatchRequest
): Promise<ReceiptResponse> {
  const res = await api.patch<ApiResponse<ReceiptResponse>>(path(id), body)
  return unwrap(res)
}

/**
 * `PATCH /v1/receipts/{id}/review` — **a decision, not a correction.**
 *
 * `accepted: true` clears `reviewRequired`; `accepted: false` marks the receipt
 * `FAILED`, which is the member saying the extraction is not worth correcting —
 * the image is still stored and still downloadable. Corrections go to
 * `patchReceipt`; this operation records a verdict, which is why it is a
 * separate call and a separate control on the screen. 409 while the pipeline
 * runs, for the same reason as the patch.
 */
export async function reviewReceipt(
  id: string,
  body: ReceiptReviewRequest
): Promise<ReceiptResponse> {
  const res = await api.patch<ApiResponse<ReceiptResponse>>(path(id, '/review'), body)
  return unwrap(res)
}

/**
 * `DELETE /v1/receipts/{id}` — the row, its line items, its image rows AND the
 * files on the server's data volume. If the receipt was linked to a transaction,
 * that transaction's `receiptId` is cleared in the same database transaction.
 * `EmptySuccess`, so `unwrapVoid`.
 */
export async function deleteReceipt(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(path(id))
  unwrapVoid(res)
}

/* ------------------------------------------------------- the image bytes */

/**
 * `GET /v1/receipts/{id}/thumbnail` — the small JPEG a list view renders,
 * **authenticated**, fetched through the session and handed back as a Blob.
 *
 * This is a photograph of the household's spending; an unauthenticated image
 * path would be a way to read it without a session. It is therefore never an
 * `<img src>` — see `./binary.ts`, and use `useReceiptImage` so the object URL
 * is revoked.
 */
export function fetchReceiptThumbnail(
  id: string,
  signal?: AbortSignal
): Promise<AuthenticatedFile> {
  return fetchAuthenticatedFile(path(id, '/thumbnail'), { signal })
}

/**
 * `GET /v1/receipts/{id}/download` — the stored JPEG, with
 * `Content-Disposition: attachment` and a server-built filename.
 *
 * Always `image/jpeg`, because every accepted upload is re-encoded before it is
 * written. Nothing is presigned and there is no token in a query string, so this
 * cannot be a plain link: the bytes come through the session and are handed to
 * the member with `saveAuthenticatedFile`.
 */
export function downloadReceipt(id: string, signal?: AbortSignal): Promise<AuthenticatedFile> {
  return fetchAuthenticatedFile(path(id, '/download'), { signal })
}

/**
 * The path `ReceiptResponse.thumbnailUrl` carries, made safe to hand to
 * `fetchAuthenticatedFile`.
 *
 * The server sends a path on this API (`/api/v1/receipts/{id}/thumbnail`) and
 * never an absolute URL, never a presigned link and never a data URI. It is
 * still server-supplied text that ends up in a request, so it is checked rather
 * than trusted: anything that is not a relative path under this API's own prefix
 * is rejected, and the id-derived path is used instead. Returns the path with
 * the client's `baseURL` prefix stripped, since `sessionFetch` adds it back.
 */
export function thumbnailPathFor(receipt: ReceiptResponse): string | null {
  if (receipt.thumbnailUrl === null) return null
  const url = receipt.thumbnailUrl
  if (url.startsWith('/api/v1/')) return url.slice('/api'.length)
  if (url.startsWith('/v1/')) return url
  // Not a path on this API. Fall back to the operation's own documented shape
  // rather than fetching whatever arrived.
  return path(receipt.id, '/thumbnail')
}
