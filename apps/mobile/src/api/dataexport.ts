/**
 * Full data export — the four `/v1/me/export*` operations.
 *
 * **Step-up, not a confirmation dialog.** `createDataExport` takes a
 * {@link StepUpRequest}: an archive is a copy of the member's whole financial
 * life, so holding a session is not enough to ask for one. The body is a
 * credential. It is passed straight through to `api` and is never logged,
 * never written to the secure store, never stored in a query key and never
 * kept by this module.
 *
 * **The shape of a run.** `POST` answers 202 with the row `PENDING` (or
 * already `FAILED`, if the executor's queue was full — the answer is still
 * 202 and still carries a real, queryable id). Poll {@link fetchDataExport}
 * until `status` is `COMPLETE`, then download. A **one run per member per
 * local day** cap is enforced server-side as a 409 `conflict` whose `detail`
 * says when they may ask again — that sentence is the server's to write, not
 * this client's to guess from a timer of its own.
 *
 * **The download is authenticated binary**, exactly like `binary.ts`'s receipt
 * fetch and `taxexport.ts`'s CSV: through `api`, so the bearer token travels
 * in a header the axios instance built. Never `Linking.openURL`, never a token
 * in a URL, never a bare `fetch`. A run that is not `COMPLETE`, or one whose
 * 24-hour link has expired, answers **409 `conflict`** — the caller's job is
 * to show "still generating" or "that link has expired", not a failure toast.
 *
 * **Saving the file.** {@link fetchDataExportArchive} pulls the bytes through
 * the authenticated seam, checks the content type it was really served, and
 * returns them; `src/lib/shareFile.ts` writes them to the app cache, opens the
 * system share sheet (where the member saves to Files / Downloads or sends
 * the ZIP elsewhere) and deletes the cache copy in `finally`.
 */

import { api } from './client';
import { pathSegment } from './paths';
import { unwrap, unwrapPage } from './errors';
import type {
  ApiResponse,
  DataExportRunResponse,
  DataExportsQuery,
  Page,
  StepUpRequest,
} from './types';

/**
 * `POST /v1/me/export` — 202 with the run row.
 *
 * `body` is the step-up credential and is deliberately not defaulted, cloned,
 * merged or logged here: it arrives, it is sent, this function forgets it.
 */
export async function createDataExport(body: StepUpRequest): Promise<DataExportRunResponse> {
  const response = await api.post<ApiResponse<DataExportRunResponse>>('/v1/me/export', body);
  return unwrap(response.data);
}

/** `GET /v1/me/export` — one page of the caller's own runs, newest first. Paged: `meta` is required. */
export async function fetchDataExports(
  query: DataExportsQuery = {}
): Promise<Page<DataExportRunResponse>> {
  const response = await api.get<ApiResponse<DataExportRunResponse[]>>('/v1/me/export', {
    params: query,
  });
  return unwrapPage(response.data);
}

/** `GET /v1/me/export/{id}` — the poll target. Anybody else's run is 404, never 403. */
export async function fetchDataExport(id: string): Promise<DataExportRunResponse> {
  const response = await api.get<ApiResponse<DataExportRunResponse>>(
    `/v1/me/export/${pathSegment(id)}`
  );
  return unwrap(response.data);
}

/**
 * What {@link fetchDataExportArchive} hands back. Nothing is written here;
 * `src/lib/shareFile.ts` writes `bytes` to the cache, shares and deletes it.
 */
export interface DataExportArchive {
  /** Always `application/zip` — anything else is refused before it is counted. */
  contentType: 'application/zip';
  /** `Content-Length` when the server sends one, else measured from the body. */
  byteLength: number;
  /** The archive itself. */
  bytes: ArrayBuffer;
}

function contentLengthOf(headers: unknown): number | null {
  if (typeof headers !== 'object' || headers === null) return null;
  const raw = (headers as Record<string, unknown>)['content-length'];
  const value = typeof raw === 'string' ? Number(raw) : null;
  return value !== null && Number.isFinite(value) ? value : null;
}

/**
 * Check the response's own `Content-Type` rather than trusting that a 200 on
 * this path is the archive — the same guard `binary.ts` and `taxexport.ts`
 * carry, and for the same reason: a captive portal or a misbehaving proxy
 * answering 200 with an unrelated body must not be reported to the member as
 * "your export is ready". A missing header is an adapter that did not surface
 * one, not a wrong body, so it defaults.
 */
function verifiedZip(headers: unknown): 'application/zip' {
  if (typeof headers !== 'object' || headers === null) return 'application/zip';
  const raw = (headers as Record<string, unknown>)['content-type'];
  if (typeof raw !== 'string' || raw.trim() === '') return 'application/zip';
  if (raw.split(';')[0].trim().toLowerCase() !== 'application/zip') {
    throw new Error('That export could not be read.');
  }
  return 'application/zip';
}

/**
 * `GET /v1/me/export/{id}/download`, through `api`.
 *
 * A run that is not `COMPLETE`, and one whose 24-hour link has expired, both
 * reject with the ordinary 409 `conflict` axios error; callers read it through
 * `toApiFailure`/`slugOf` like every other refusal on this API rather than
 * this module inventing a second shape for it.
 */
export async function fetchDataExportArchive(id: string): Promise<DataExportArchive> {
  const response = await api.get<ArrayBuffer>(`/v1/me/export/${pathSegment(id)}/download`, {
    responseType: 'arraybuffer',
  });
  const contentType = verifiedZip(response.headers);
  const byteLength = contentLengthOf(response.headers) ?? response.data.byteLength;
  return { contentType, byteLength, bytes: response.data };
}
