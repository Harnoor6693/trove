/**
 * Receipts — the nine operations `openapi/trove-core.yaml` describes.
 *
 * What changed in step 214, and why each one was a defect rather than a
 * refactor:
 *
 *  - **`GET /v1/receipts` is paged.** Its 200 requires `data` *and*
 *    `meta: {total, page, limit}`. This module declared
 *    `fetchReceipts(): Promise<ReceiptResponse[]>` and the list screen fed a
 *    `FlatList` from it, so a household past its first page was shown a
 *    truncated ledger it was never told about. It returns a `Page` now and
 *    page two is reachable.
 *  - **The list rows are `ReceiptSummaryResponse`, not `ReceiptResponse`.**
 *    The summary carries no line items, no tax split and no `thumbnailUrl`;
 *    reading one off a row was reading a field the server does not send.
 *  - **Every type is derived.** The hand-written block in `types.ts` had a
 *    status enum the server has never emitted (`PROCESSING`, `PROCESSED`,
 *    `REVIEW_REQUIRED`) and non-null extracted fields the server sends as
 *    null on nearly every receipt.
 *  - **Six operations were simply absent**: patch, review, delete, status,
 *    download and thumbnail.
 *
 * The multipart form keeps the field names the upload controller binds:
 * `file` (the image) and `source` (the ingestion channel). The
 * `X-Trove-Province` header is gone — the tax-split stage reads the province
 * from the member's own profile server-side.
 */

import { api } from './client';
import { fetchImageAsDataUri } from './binary';
import { expectSuccess, unwrap, unwrapPage } from './errors';
import type {
  ApiResponse,
  Page,
  ReceiptListQuery,
  ReceiptPatchRequest,
  ReceiptResponse,
  ReceiptReviewRequest,
  ReceiptSource,
  ReceiptStatusResponse,
  ReceiptSummaryResponse,
  ReceiptUploadResponse,
} from './types';

/** Multipart field name for the image part (`@RequestParam("file")`). */
export const RECEIPT_FILE_FIELD = 'file';
/** Multipart field name for the ingestion channel (`@RequestParam("source")`). */
export const RECEIPT_SOURCE_FIELD = 'source';

/**
 * The per-file ceiling this app tells the member about **before** they shoot.
 *
 * 413 `upload-too-large` is one slug covering three ceilings — the
 * container's multipart cap, the operation's own per-file cap and the
 * household's storage quota — and the first two are checked against the
 * part's *declared* size before a byte is read. The server owns the real
 * numbers and never publishes them, so this is the figure the capture screen
 * quotes as guidance; the refusal itself always comes from the server and its
 * own sentence is what is shown.
 */
export const RECEIPT_MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

/** The two image types `uploadReceipt` accepts, in the spec's own words. */
export const UPLOADABLE_IMAGE_TYPES = ['image/jpeg', 'image/png'] as const;
export type UploadableImageType = (typeof UPLOADABLE_IMAGE_TYPES)[number];

/** The message a member sees when their photo is a format this API refuses. */
export const UNSUPPORTED_IMAGE_MESSAGE =
  'Trove can send a JPEG or a PNG. That photo is in another format — on an iPhone, ' +
  'turn on Settings › Camera › Formats › Most Compatible, or re-save the picture as ' +
  'a JPEG, and try again.';

/** Thrown before a byte leaves the device, rather than letting the server 400. */
export class UnsupportedImageFormatError extends Error {
  constructor(readonly declaredType: string | null) {
    super(UNSUPPORTED_IMAGE_MESSAGE);
    this.name = 'UnsupportedImageFormatError';
  }
}

const EXTENSION_TYPES: Readonly<Record<string, UploadableImageType>> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
};

/** Formats a picker can hand back that this API refuses outright. */
const REFUSED_EXTENSIONS = ['heic', 'heif', 'webp', 'gif', 'bmp', 'tif', 'tiff', 'avif'];

function normaliseMimeType(raw: string | null | undefined): string | null {
  if (typeof raw !== 'string') return null;
  const type = raw.split(';')[0].trim().toLowerCase();
  return type === '' ? null : type;
}

function extensionOf(uri: string): string | null {
  const path = uri.split('?')[0].split('#')[0];
  const dot = path.lastIndexOf('.');
  if (dot === -1 || dot === path.length - 1) return null;
  return path.slice(dot + 1).toLowerCase();
}

/**
 * The type the multipart part must **declare**, from what the picker said.
 *
 * `uploadReceipt` used to hardcode `image/jpeg` for every upload, whatever the
 * bytes really were. That is not a cosmetic lie: the operation states that the
 * declared content type must be `image/jpeg` or `image/png` **and that the
 * first bytes must agree with it**, and names "bytes that are not the format
 * they claim to be" as one of the causes of its 400 — so a PNG screenshot of
 * an e-receipt (a format the capture screen explicitly invites) declared as
 * JPEG is refused by the server for a reason the member cannot act on.
 *
 * Returns `null` for a format this API does not accept at all, which is
 * {@link UnsupportedImageFormatError} rather than a mislabelled part. HEIC is
 * the case that matters: it is the iOS photo library's default, and the
 * operation refuses it *on the stated assumption that the mobile client
 * converts first*. The conversion happens at the picker, not here:
 * `useReceiptCamera` opens the library with
 * `preferredAssetRepresentationMode: Compatible`, so PhotoKit hands back a
 * JPEG and HEIC never reaches this function on that path. Should one arrive
 * anyway (another picker, an older OS), it is still refused here, plainly and
 * before the upload — the refusal list is deliberately not loosened.
 *
 * The `mimeType` the picker reports wins when there is one. Otherwise the
 * URI's extension decides, and a URI with neither (an in-memory camera path on
 * some platforms) falls back to JPEG, which is what `launchCameraAsync`
 * produces.
 */
export function declaredImageType(
  mimeType: string | null | undefined,
  uri: string
): UploadableImageType | null {
  const declared = normaliseMimeType(mimeType);
  if (declared !== null) {
    if (declared === 'image/jpeg' || declared === 'image/jpg') return 'image/jpeg';
    if (declared === 'image/png') return 'image/png';
    return null;
  }
  const extension = extensionOf(uri);
  if (extension === null) return 'image/jpeg';
  if (REFUSED_EXTENSIONS.includes(extension)) return null;
  return EXTENSION_TYPES[extension] ?? 'image/jpeg';
}

/**
 * The multipart image part, named after the format it declares.
 *
 * A function rather than three inline lines because it is the only place the
 * declared type and the filename can disagree, and because the `FormData` the
 * test environment provides stringifies an appended object — so this is the
 * seam where the part's shape can actually be asserted.
 */
export function receiptFilePart(
  uri: string,
  type: UploadableImageType
): { uri: string; name: string; type: UploadableImageType } {
  return { uri, name: type === 'image/png' ? 'receipt.png' : 'receipt.jpg', type };
}

/**
 * `POST /v1/receipts/upload` — **202**, `{id, status, createdAt}` and nothing
 * else. The row exists the moment this returns; its fields do not. Poll
 * {@link fetchReceiptStatus}.
 *
 * Only JPEG and PNG are accepted and the declared type must match the first
 * bytes, so the part is declared from what the picker actually handed over
 * (see {@link declaredImageType}) rather than as JPEG regardless. Whatever is
 * sent, what the server stores and later serves is a re-encoded JPEG — which
 * is also what removes the GPS coordinates a phone writes into a receipt
 * photograph.
 *
 * `source` is the contract's own distinction and is **displayed to the member
 * afterwards** (`RECEIPT_SOURCE_LABEL`): `SCAN` is a photograph taken in the
 * app, `UPLOAD` a file the member chose. There is no field on
 * `ReceiptPatchRequest` to correct it later, so the caller passes the one that
 * is true.
 */
export async function uploadReceipt(
  uri: string,
  source: ReceiptSource = 'SCAN',
  mimeType: string | null = null
): Promise<ReceiptUploadResponse> {
  const type = declaredImageType(mimeType, uri);
  if (type === null) throw new UnsupportedImageFormatError(normaliseMimeType(mimeType));

  const form = new FormData();
  form.append(RECEIPT_FILE_FIELD, receiptFilePart(uri, type) as unknown as Blob);
  form.append(RECEIPT_SOURCE_FIELD, source);

  const response = await api.post<ApiResponse<ReceiptUploadResponse>>('/v1/receipts/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return unwrap(response.data);
}

/**
 * `GET /v1/receipts` — **one page**, with its totals.
 *
 * `unwrapPage` rather than `unwrap`: the totals are what bound the pager, and
 * dropping them is how the previous version silently truncated the list.
 */
export async function fetchReceipts(
  query: ReceiptListQuery = {}
): Promise<Page<ReceiptSummaryResponse>> {
  const response = await api.get<ApiResponse<ReceiptSummaryResponse[]>>('/v1/receipts', {
    params: query,
  });
  return unwrapPage(response.data);
}

/** `GET /v1/receipts/{id}` — the whole row: line items, tax split, matched transaction. */
export async function fetchReceipt(id: string): Promise<ReceiptResponse> {
  const response = await api.get<ApiResponse<ReceiptResponse>>(`/v1/receipts/${id}`);
  return unwrap(response.data);
}

/**
 * `GET /v1/receipts/{id}/status` — the four fields a poller needs.
 *
 * A fraction of the cost of re-fetching the whole row each tick, which is the
 * reason the operation exists.
 */
export async function fetchReceiptStatus(id: string): Promise<ReceiptStatusResponse> {
  const response = await api.get<ApiResponse<ReceiptStatusResponse>>(`/v1/receipts/${id}/status`);
  return unwrap(response.data);
}

/**
 * `PATCH /v1/receipts/{id}` — fill fields in by hand, and link a transaction.
 *
 * Every field is optional and an absent field is left alone; there is no way
 * to clear one back to null here. A receipt whose pipeline is still running
 * is 409 `conflict`, because the pipeline would overwrite the write.
 */
export async function patchReceipt(
  id: string,
  patch: ReceiptPatchRequest
): Promise<ReceiptResponse> {
  const response = await api.patch<ApiResponse<ReceiptResponse>>(`/v1/receipts/${id}`, patch);
  return unwrap(response.data);
}

/**
 * `PATCH /v1/receipts/{id}/review` — the reviewer's verdict.
 *
 * A **different action** from {@link patchReceipt}: that one writes fields,
 * this one records a decision. `accepted: false` marks the receipt `FAILED`,
 * which is the member saying the extraction is not worth correcting; the
 * image stays stored and downloadable either way.
 */
export async function reviewReceipt(
  id: string,
  body: ReceiptReviewRequest
): Promise<ReceiptResponse> {
  const response = await api.patch<ApiResponse<ReceiptResponse>>(`/v1/receipts/${id}/review`, body);
  return unwrap(response.data);
}

/**
 * `DELETE /v1/receipts/{id}` — the row, its line items and **the files behind
 * them**. Answers the closed `EmptySuccess` shape, so nothing reads `data`.
 */
export async function deleteReceipt(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/receipts/${id}`);
  expectSuccess(response.data);
}

/**
 * `GET /v1/receipts/{id}/thumbnail` as a `data:` URI.
 *
 * Authenticated, so the bytes come through `api` and never through an
 * `<Image source={{uri}}>` pointed at the route — see `binary.ts`.
 */
export function fetchReceiptThumbnail(id: string): Promise<string> {
  return fetchImageAsDataUri(`/v1/receipts/${id}/thumbnail`);
}

/**
 * `GET /v1/receipts/{id}/download` as a `data:` URI — the full stored JPEG.
 *
 * Always `image/jpeg`: the format the client sent is not the format that is
 * kept.
 */
export function fetchReceiptImage(id: string): Promise<string> {
  return fetchImageAsDataUri(`/v1/receipts/${id}/download`);
}
