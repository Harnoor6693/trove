/**
 * `/v1/me/export*` — the member's own full-data export (§4.2, readiness gap
 * P2-16, step 205). Four operations, all of them, and nothing else.
 *
 * **Scoped to the MEMBER, not the household.** An archive is a copy of one
 * person's whole financial life, so a household mate's runs are neither listed
 * here nor reachable by id — anybody else's id is 404, never 403, so the answer
 * never confirms that another member's id is real.
 *
 * The download is the one non-JSON body in this module — `application/zip`
 * behind the session, with `Content-Disposition: attachment` — so it goes
 * through the shared `fetchAuthenticatedFile` helper (`./binary.ts`, landed by
 * step 204 and already reused by family C) rather than a second implementation,
 * and never through an `<a href>`: nothing is presigned and no URL to any
 * storage system is ever handed out.
 */
import { api, unwrap } from './client'
import { fetchAuthenticatedFile, type AuthenticatedFile } from './binary'
import type {
  ApiResponse,
  DataExportRunResponse,
  DataExportsQuery,
  Page,
  PaginationMeta,
  StepUpRequest,
} from './types'

/** `GET /v1/me/export?page&limit` — the caller's own runs, newest first. PAGED. */
export async function listDataExports(
  q: DataExportsQuery = {}
): Promise<Page<DataExportRunResponse>> {
  const params: Record<string, number> = {}
  if (q.page !== undefined) params.page = q.page
  if (q.limit !== undefined) params.limit = q.limit

  const res = await api.get<ApiResponse<DataExportRunResponse[]>>('/v1/me/export', { params })
  const rows = unwrap(res)
  const meta = res.data.meta
  if (!meta) throw new Error('GET /v1/me/export answered without the required pagination meta')
  return { rows, meta: meta as PaginationMeta }
}

/**
 * `POST /v1/me/export` — **step-up**, answers 202 with the recorded run.
 *
 * Records the run as `PENDING`, hands generation to a bounded background
 * executor and returns immediately; poll `getDataExport` until `COMPLETE`. If
 * the executor's queue is full the row is flipped to `FAILED` before the
 * response is sent — still a 202, still a real queryable id.
 *
 * **One run per member per day**, counted over the member's own local day. A
 * second request the same day is 409 `conflict` and the server's own message
 * says when they may ask again — which is why no client-side timer guesses at it.
 *
 * The `StepUpRequest` is never logged, cached or retained.
 */
export async function createDataExport(body: StepUpRequest): Promise<DataExportRunResponse> {
  const res = await api.post<ApiResponse<DataExportRunResponse>>('/v1/me/export', body)
  return unwrap(res)
}

/** `GET /v1/me/export/{id}` — the poll target while a run is generating. Anybody else's id is 404. */
export async function getDataExport(id: string): Promise<DataExportRunResponse> {
  const res = await api.get<ApiResponse<DataExportRunResponse>>(
    `/v1/me/export/${encodeURIComponent(id)}`
  )
  return unwrap(res)
}

/**
 * `GET /v1/me/export/{id}/download` — authenticated, `application/zip`.
 *
 * 409 `conflict` covers BOTH a run that is not `COMPLETE` (`PENDING`,
 * `GENERATING`, `FAILED`) and one whose 24-hour link has expired — never 404,
 * because the run exists and belongs to the caller, and "no such run" would make
 * a polling client discard state it should keep.
 */
export function downloadDataExport(id: string, signal?: AbortSignal): Promise<AuthenticatedFile> {
  return fetchAuthenticatedFile(`/v1/me/export/${encodeURIComponent(id)}/download`, { signal })
}
