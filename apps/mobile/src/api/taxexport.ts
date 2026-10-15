/**
 * Tax export — the seven operations `openapi/trove-core.yaml` describes for
 * `/v1/tax-export/*`.
 *
 * **AN EXPORT, NOT AN ENGINE.** The tax engine was cut (owner decision); this
 * whole surface carries the member's own amounts and labels and computes
 * nothing — no rate, no bracket, no GST split, no tax owed. `lineCode` and
 * `lineLabel` are strings a human reads off a form and the server stores
 * verbatim; nothing here interprets them.
 *
 * **A run is scoped to the member, not the household** — the opposite of
 * every ledger route on this API — even though the rows it exports come from
 * the household's shared ledger. `createTaxExportRun` answers 202 with the row
 * `PENDING` (or already `FAILED`, if the executor's queue was full); poll
 * {@link fetchTaxExportRun} until `status` is `COMPLETE`.
 *
 * **The download is the one non-JSON response on this API**, and it is
 * authenticated exactly like `binary.ts`'s receipt fetch: through `api`, never
 * a bare `fetch` or a token in a URL. Two readers share it:
 * {@link fetchTaxExportRunFile} returns the CSV text for an in-app read-only
 * preview (or, for a PDF run, which this app cannot render, just the byte
 * count), and writes nothing; {@link fetchTaxExportRunBytes} returns the raw
 * bytes, which `src/lib/shareFile.ts` writes to the app cache, hands to the
 * system share sheet and deletes again. A run that is not yet `COMPLETE` answers 409
 * `conflict` — the caller's job is to show "still generating", not a failure,
 * and this module does not gate that itself so the screen can decide from its
 * own poll state without a second round trip.
 */

import { api } from './client';
import { pathSegment } from './paths';
import { expectSuccess, unwrap, unwrapPage } from './errors';
import type {
  ApiResponse,
  CreateTaxExportRunRequest,
  CountryCode,
  Page,
  TaxExportFormat,
  TaxExportRunResponse,
  TaxExportRunsQuery,
  TaxLineMappingRequest,
  TaxLineMappingResponse,
} from './types';

/**
 * `GET /v1/tax-export/lines` — the caller's effective category-to-tax-line
 * map. Omitted `jurisdiction` means the caller's own `users.country`.
 */
export async function fetchTaxLines(jurisdiction?: CountryCode): Promise<TaxLineMappingResponse[]> {
  const response = await api.get<ApiResponse<TaxLineMappingResponse[]>>('/v1/tax-export/lines', {
    params: jurisdiction === undefined ? undefined : { jurisdiction },
  });
  return unwrap(response.data);
}

/**
 * `PUT /v1/tax-export/lines/{categoryId}` — writes the CALLER'S OWN override,
 * shadowing the install-wide seed for this member only.
 */
export async function setTaxLine(
  categoryId: string,
  body: TaxLineMappingRequest
): Promise<TaxLineMappingResponse> {
  const response = await api.put<ApiResponse<TaxLineMappingResponse>>(
    `/v1/tax-export/lines/${pathSegment(categoryId)}`,
    body
  );
  return unwrap(response.data);
}

/** `GET /v1/tax-export/runs` — one page of the caller's own runs, newest first. */
export async function fetchTaxExportRuns(
  query: TaxExportRunsQuery = {}
): Promise<Page<TaxExportRunResponse>> {
  const response = await api.get<ApiResponse<TaxExportRunResponse[]>>('/v1/tax-export/runs', {
    params: query,
  });
  return unwrapPage(response.data);
}

/**
 * `POST /v1/tax-export/runs` — starts a whole-tax-year export of the
 * household's ledger for the caller alone. Answers 202 with the row, `status`
 * `PENDING` normally or already `FAILED` if the executor's queue was full.
 */
export async function createTaxExportRun(
  body: CreateTaxExportRunRequest
): Promise<TaxExportRunResponse> {
  const response = await api.post<ApiResponse<TaxExportRunResponse>>('/v1/tax-export/runs', body);
  return unwrap(response.data);
}

/** `GET /v1/tax-export/runs/{id}` — the poll target while a run is generating. */
export async function fetchTaxExportRun(id: string): Promise<TaxExportRunResponse> {
  const response = await api.get<ApiResponse<TaxExportRunResponse>>(
    `/v1/tax-export/runs/${pathSegment(id)}`
  );
  return unwrap(response.data);
}

/** `DELETE /v1/tax-export/runs/{id}` — removes the row and its generated file together. Safe in any status. */
export async function deleteTaxExportRun(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/tax-export/runs/${pathSegment(id)}`);
  expectSuccess(response.data);
}

/** What {@link fetchTaxExportRunFile} hands back — for on-screen display only. */
export interface TaxExportFile {
  contentType: 'text/csv' | 'application/pdf';
  /** The decoded CSV text. Null for a PDF run — this app has no PDF renderer. */
  text: string | null;
  /** Size in bytes: the `Content-Length` header when the server sends one, else measured from the body. */
  byteLength: number;
}

function contentLengthOf(headers: unknown): number | null {
  if (typeof headers !== 'object' || headers === null) return null;
  const raw = (headers as Record<string, unknown>)['content-length'];
  const value = typeof raw === 'string' ? Number(raw) : null;
  return value !== null && Number.isFinite(value) ? value : null;
}

/**
 * Validate the response's own `Content-Type` rather than trusting the
 * `format` the caller asked for — the same reason `binary.ts`'s
 * `contentTypeOf` refuses a body whose bytes disagree with the header it
 * claims. A missing header defaults to `expected` (an adapter that didn't
 * surface it, not a wrong body); a header that disagrees throws, so a stale
 * cached run row, a captive portal, or a misbehaving proxy answering 200
 * with an unrelated body is never rendered as if it were the member's export.
 */
function verifiedContentType(headers: unknown, expected: 'text/csv' | 'application/pdf'): string {
  if (typeof headers !== 'object' || headers === null) return expected;
  const raw = (headers as Record<string, unknown>)['content-type'];
  if (typeof raw !== 'string' || raw.trim() === '') return expected;
  const type = raw.split(';')[0].trim().toLowerCase();
  if (type !== expected) throw new Error('That export could not be read.');
  return type;
}

/**
 * `GET /v1/tax-export/runs/{id}/download`, through `api` — the bearer token
 * travels in a header the axios instance built, never in this URL. A run that
 * is not `COMPLETE` rejects with the ordinary 409 `conflict` axios error;
 * callers read that through `toApiFailure`/`slugOf` the same way every other
 * refusal on this API is read, rather than this module inventing a second
 * shape for it.
 *
 * For a `CSV` run the text is returned for an in-app, read-only preview — no
 * file is written and nothing is handed to the OS. For a `PDF` run only the
 * byte count comes back: this app has no PDF viewer. Saving either format is
 * {@link fetchTaxExportRunBytes}' job.
 */
export async function fetchTaxExportRunFile(
  id: string,
  format: TaxExportFormat
): Promise<TaxExportFile> {
  const path = `/v1/tax-export/runs/${pathSegment(id)}/download`;
  if (format === 'PDF') {
    const response = await api.get<ArrayBuffer>(path, { responseType: 'arraybuffer' });
    verifiedContentType(response.headers, 'application/pdf');
    const byteLength = contentLengthOf(response.headers) ?? response.data.byteLength;
    return { contentType: 'application/pdf', text: null, byteLength };
  }
  const response = await api.get<string>(path, { responseType: 'text' });
  verifiedContentType(response.headers, 'text/csv');
  const text = typeof response.data === 'string' ? response.data : '';
  const byteLength = contentLengthOf(response.headers) ?? text.length;
  return { contentType: 'text/csv', text, byteLength };
}

/**
 * The same download as raw bytes, for "Save or share". Always
 * `responseType: 'arraybuffer'` so a CSV reaches the file byte for byte, and
 * the response's own `Content-Type` must match the run's format.
 */
export async function fetchTaxExportRunBytes(
  id: string,
  format: TaxExportFormat
): Promise<ArrayBuffer> {
  const response = await api.get<ArrayBuffer>(`/v1/tax-export/runs/${pathSegment(id)}/download`, {
    responseType: 'arraybuffer',
  });
  verifiedContentType(response.headers, format === 'PDF' ? 'application/pdf' : 'text/csv');
  return response.data;
}
