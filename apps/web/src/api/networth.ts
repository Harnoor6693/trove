/**
 * `/v1/networth/*` — ten operations, all of them, and nothing else (§4.7, step 203).
 *
 * The series is grouped by currency (F2): a household holding both CAD and USD
 * positions never has them added together. Nothing in this module blends a
 * total across currencies — the screen renders one series per currency.
 */
import { api, unwrap, unwrapVoid } from './client'
import type {
  ApiResponse,
  AssetRequest,
  AssetResponse,
  AssetUpdateRequest,
  LiabilityRequest,
  LiabilityResponse,
  LiabilityUpdateRequest,
  NetWorthSeriesQuery,
  NetWorthSeriesResponse,
  NetWorthSnapshotResponse,
} from './types'

/* ------------------------------------------------------------------ series */

/** `GET /v1/networth/series?range=`. Never writes — an empty `series` means no snapshots exist in range. */
export async function getNetWorthSeries(
  q: NetWorthSeriesQuery = {}
): Promise<NetWorthSeriesResponse> {
  const params: Record<string, string> = {}
  if (q.range !== undefined) params.range = q.range
  const res = await api.get<ApiResponse<NetWorthSeriesResponse>>('/v1/networth/series', {
    params,
  })
  return unwrap(res)
}

/**
 * `POST /v1/networth/snapshot` — 201. Writes one row per currency present;
 * re-running on the same local date UPDATES the existing row(s) rather than
 * creating a second set. Bare array, one per currency.
 */
export async function takeNetWorthSnapshot(): Promise<NetWorthSnapshotResponse[]> {
  const res = await api.post<ApiResponse<NetWorthSnapshotResponse[]>>('/v1/networth/snapshot')
  return unwrap(res)
}

/* ------------------------------------------------------------------- assets */

/** `GET /v1/networth/assets` — the household's shared ledger. Not paged. */
export async function listAssets(): Promise<AssetResponse[]> {
  const res = await api.get<ApiResponse<AssetResponse[]>>('/v1/networth/assets')
  return unwrap(res)
}

/** `POST /v1/networth/assets` — 201. `currency` defaults to the caller's own when omitted or null. */
export async function createAsset(body: AssetRequest): Promise<AssetResponse> {
  const res = await api.post<ApiResponse<AssetResponse>>('/v1/networth/assets', body)
  return unwrap(res)
}

/** `PUT /v1/networth/assets/{id}` — `currency` is not writable; it would reinterpret `value`. */
export async function updateAsset(id: string, body: AssetUpdateRequest): Promise<AssetResponse> {
  const res = await api.put<ApiResponse<AssetResponse>>(
    `/v1/networth/assets/${encodeURIComponent(id)}`,
    body
  )
  return unwrap(res)
}

/** `DELETE /v1/networth/assets/{id}`. `EmptySuccess`. */
export async function deleteAsset(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(`/v1/networth/assets/${encodeURIComponent(id)}`)
  unwrapVoid(res)
}

/* -------------------------------------------------------------- liabilities */

/** `GET /v1/networth/liabilities` — the household's shared ledger. Not paged. */
export async function listLiabilities(): Promise<LiabilityResponse[]> {
  const res = await api.get<ApiResponse<LiabilityResponse[]>>('/v1/networth/liabilities')
  return unwrap(res)
}

/** `POST /v1/networth/liabilities` — 201. `interestRate` defaults to zero when omitted or null. */
export async function createLiability(body: LiabilityRequest): Promise<LiabilityResponse> {
  const res = await api.post<ApiResponse<LiabilityResponse>>('/v1/networth/liabilities', body)
  return unwrap(res)
}

/** `PUT /v1/networth/liabilities/{id}` — a full replace; `currency` is absent for the same reason as an asset's. */
export async function updateLiability(
  id: string,
  body: LiabilityUpdateRequest
): Promise<LiabilityResponse> {
  const res = await api.put<ApiResponse<LiabilityResponse>>(
    `/v1/networth/liabilities/${encodeURIComponent(id)}`,
    body
  )
  return unwrap(res)
}

/** `DELETE /v1/networth/liabilities/{id}`. `EmptySuccess`. */
export async function deleteLiability(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(
    `/v1/networth/liabilities/${encodeURIComponent(id)}`
  )
  unwrapVoid(res)
}
