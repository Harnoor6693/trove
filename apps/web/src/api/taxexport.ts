/**
 * `/v1/tax-export/*` — seven operations, all of them, and nothing else
 * (§4.12, step 203).
 *
 * **This is an export, not an engine.** Nothing here computes a tax figure —
 * no rate, no bracket, no GST split, no total the server did not send. Every
 * screen this module feeds renders the member's own amounts and labels
 * verbatim.
 *
 * `downloadTaxExportRun` is the one non-JSON body on this API — `text/csv` or
 * `application/pdf`, behind the session — so it goes through the shared
 * `fetchAuthenticatedFile` helper (`./binary.ts`, landed by step 204) rather
 * than a second implementation.
 */
import { api, unwrap, unwrapVoid } from './client'
import { fetchAuthenticatedFile, type AuthenticatedFile } from './binary'
import type {
  ApiResponse,
  CreateTaxExportRunRequest,
  Page,
  PaginationMeta,
  TaxLineMappingRequest,
  TaxLineMappingResponse,
  TaxExportRunResponse,
  TaxExportRunsQuery,
} from './types'
import type { CountryCode } from './types'

/** `GET /v1/tax-export/lines?jurisdiction=`. Bare array; not paged. Omitted jurisdiction defaults to the caller's own country. */
export async function listTaxLines(jurisdiction?: CountryCode): Promise<TaxLineMappingResponse[]> {
  const params: Record<string, string> = {}
  if (jurisdiction !== undefined) params.jurisdiction = jurisdiction
  const res = await api.get<ApiResponse<TaxLineMappingResponse[]>>('/v1/tax-export/lines', {
    params,
  })
  return unwrap(res)
}

/**
 * `PUT /v1/tax-export/lines/{categoryId}` — writes the CALLER's own override
 * for `(jurisdiction, categoryId)`. There is no empty-string clear; the
 * seeded convention for "no line" is the literal `lineCode: 'NONE'`.
 */
export async function setTaxLine(
  categoryId: string,
  body: TaxLineMappingRequest
): Promise<TaxLineMappingResponse> {
  const res = await api.put<ApiResponse<TaxLineMappingResponse>>(
    `/v1/tax-export/lines/${encodeURIComponent(categoryId)}`,
    body
  )
  return unwrap(res)
}

/** `GET /v1/tax-export/runs?page&limit` — the caller's OWN runs, newest first. PAGED. */
export async function listTaxExportRuns(
  q: TaxExportRunsQuery = {}
): Promise<Page<TaxExportRunResponse>> {
  const params: Record<string, number> = {}
  if (q.page !== undefined) params.page = q.page
  if (q.limit !== undefined) params.limit = q.limit

  const res = await api.get<ApiResponse<TaxExportRunResponse[]>>('/v1/tax-export/runs', {
    params,
  })
  const rows = unwrap(res)
  const meta = res.data.meta
  if (!meta) {
    throw new Error('GET /v1/tax-export/runs answered without the required pagination meta')
  }
  return { rows, meta: meta as PaginationMeta }
}

/**
 * `POST /v1/tax-export/runs` — 202. Records the run and hands generation to a
 * background executor; poll `getTaxExportRun` until `status` is `COMPLETE`.
 */
export async function createTaxExportRun(
  body: CreateTaxExportRunRequest
): Promise<TaxExportRunResponse> {
  const res = await api.post<ApiResponse<TaxExportRunResponse>>('/v1/tax-export/runs', body)
  return unwrap(res)
}

/** `GET /v1/tax-export/runs/{id}` — the poll target while a run is generating. */
export async function getTaxExportRun(id: string): Promise<TaxExportRunResponse> {
  const res = await api.get<ApiResponse<TaxExportRunResponse>>(
    `/v1/tax-export/runs/${encodeURIComponent(id)}`
  )
  return unwrap(res)
}

/** `DELETE /v1/tax-export/runs/{id}` — removes the row and the generated file together. Safe in any status. */
export async function deleteTaxExportRun(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(
    `/v1/tax-export/runs/${encodeURIComponent(id)}`
  )
  unwrapVoid(res)
}

/**
 * `GET /v1/tax-export/runs/{id}/download` — authenticated, non-JSON. A run
 * that is not `COMPLETE` answers 409 `ExportNotReady` (`conflict`), which the
 * screen reads as "still generating", never a failure.
 */
export function downloadTaxExportRun(id: string, signal?: AbortSignal): Promise<AuthenticatedFile> {
  return fetchAuthenticatedFile(`/v1/tax-export/runs/${encodeURIComponent(id)}/download`, {
    signal,
  })
}
