/**
 * Net worth — the ten operations `openapi/trove-core.yaml` describes for
 * `/v1/networth/*`.
 *
 * **The series is PER CURRENCY, never blended.** `GET /v1/networth/series`
 * answers `NetWorthSeriesResponse.series[]`, one `NetWorthSeries` per currency
 * the household has snapshots in (F2: there is no exchange rate anywhere in
 * this system, so CAD and USD positions are never added together).
 * `primaryCurrency` says which of those is the caller's own — it is not a
 * currency to convert the others into, and this module never sums across the
 * array.
 *
 * `takeNetWorthSnapshot` and `scanSubscriptions` share a shape: both are
 * explicit member-triggered actions that do the same write a scheduled job
 * already does, so pressing the button twice in a day is a no-op on the
 * second press (the server upserts by local date, not by request).
 */

import { api } from './client';
import { pathSegment } from './paths';
import { expectSuccess, unwrap } from './errors';
import type {
  ApiResponse,
  AssetRequest,
  AssetResponse,
  AssetUpdateRequest,
  LiabilityRequest,
  LiabilityResponse,
  LiabilityUpdateRequest,
  NetWorthRange,
  NetWorthSeriesResponse,
  NetWorthSnapshotResponse,
} from './types';

/** `GET /v1/networth/series` — one series per currency the household holds a snapshot in. */
export async function fetchNetWorthSeries(range?: NetWorthRange): Promise<NetWorthSeriesResponse> {
  const response = await api.get<ApiResponse<NetWorthSeriesResponse>>('/v1/networth/series', {
    params: range === undefined ? undefined : { range },
  });
  return unwrap(response.data);
}

/**
 * `POST /v1/networth/snapshot` — sums the household's current assets and
 * liabilities now, one row per currency present. Re-running this on the same
 * local date updates the existing row(s) rather than creating a second set.
 */
export async function takeNetWorthSnapshot(): Promise<NetWorthSnapshotResponse[]> {
  const response = await api.post<ApiResponse<NetWorthSnapshotResponse[]>>('/v1/networth/snapshot');
  return unwrap(response.data);
}

/** `GET /v1/networth/assets` — the household's shared ledger. Not paged. */
export async function fetchAssets(): Promise<AssetResponse[]> {
  const response = await api.get<ApiResponse<AssetResponse[]>>('/v1/networth/assets');
  return unwrap(response.data);
}

/** `POST /v1/networth/assets`. `currency` omitted takes the creating member's own. */
export async function createAsset(body: AssetRequest): Promise<AssetResponse> {
  const response = await api.post<ApiResponse<AssetResponse>>('/v1/networth/assets', body);
  return unwrap(response.data);
}

/**
 * `PUT /v1/networth/assets/{id}` — a full replace of the writable fields.
 * `currency` is not accepted; it is the denomination `value` was recorded in.
 */
export async function updateAsset(id: string, body: AssetUpdateRequest): Promise<AssetResponse> {
  const response = await api.put<ApiResponse<AssetResponse>>(
    `/v1/networth/assets/${pathSegment(id)}`,
    body
  );
  return unwrap(response.data);
}

/** `DELETE /v1/networth/assets/{id}`. Answers the closed `EmptySuccess` shape. */
export async function deleteAsset(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/networth/assets/${pathSegment(id)}`);
  expectSuccess(response.data);
}

/** `GET /v1/networth/liabilities` — the household's shared ledger. Not paged. */
export async function fetchLiabilities(): Promise<LiabilityResponse[]> {
  const response = await api.get<ApiResponse<LiabilityResponse[]>>('/v1/networth/liabilities');
  return unwrap(response.data);
}

/**
 * `POST /v1/networth/liabilities`. `interestRate` omitted defaults to zero
 * (zero-interest debt); `currency` omitted takes the creating member's own.
 */
export async function createLiability(body: LiabilityRequest): Promise<LiabilityResponse> {
  const response = await api.post<ApiResponse<LiabilityResponse>>('/v1/networth/liabilities', body);
  return unwrap(response.data);
}

/**
 * `PUT /v1/networth/liabilities/{id}` — a full replace. `currency` is not
 * accepted, for the same reason as an asset's.
 */
export async function updateLiability(
  id: string,
  body: LiabilityUpdateRequest
): Promise<LiabilityResponse> {
  const response = await api.put<ApiResponse<LiabilityResponse>>(
    `/v1/networth/liabilities/${pathSegment(id)}`,
    body
  );
  return unwrap(response.data);
}

/** `DELETE /v1/networth/liabilities/{id}`. Answers the closed `EmptySuccess` shape. */
export async function deleteLiability(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(
    `/v1/networth/liabilities/${pathSegment(id)}`
  );
  expectSuccess(response.data);
}
