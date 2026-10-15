/**
 * Net worth — the per-currency series, the snapshot action, and assets and
 * liabilities as their own CRUD lists (§4.7, step 203).
 *
 * Every mutation invalidates the whole `['networth']` prefix: an asset or
 * liability write changes what the NEXT snapshot would record, and a snapshot
 * changes the series — none of the four are trustworthy on their own once any
 * of the others has changed.
 */
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import {
  createAsset,
  createLiability,
  deleteAsset,
  deleteLiability,
  getNetWorthSeries,
  listAssets,
  listLiabilities,
  takeNetWorthSnapshot,
  updateAsset,
  updateLiability,
} from '@api/networth'
import type { ApiError } from '@api/client'
import type {
  AssetRequest,
  AssetResponse,
  AssetUpdateRequest,
  LiabilityRequest,
  LiabilityResponse,
  LiabilityUpdateRequest,
  NetWorthRange,
  NetWorthSeriesResponse,
  NetWorthSnapshotResponse,
} from '@api/types'

export const netWorthKey = ['networth'] as const
export const netWorthSeriesKey = (range: NetWorthRange) => ['networth', 'series', range] as const
export const assetsKey = ['networth', 'assets'] as const
export const liabilitiesKey = ['networth', 'liabilities'] as const

function invalidateNetWorth(qc: QueryClient): void {
  void qc.invalidateQueries({ queryKey: netWorthKey })
}

/** `GET /v1/networth/series?range=`. One series per currency — never rendered as one blended line. */
export function useNetWorthSeries(range: NetWorthRange) {
  return useQuery<NetWorthSeriesResponse, ApiError>({
    queryKey: netWorthSeriesKey(range),
    queryFn: () => getNetWorthSeries({ range }),
    staleTime: 60 * 1000,
  })
}

/** `POST /v1/networth/snapshot` — an explicit action; its result refreshes the series. */
export function useTakeNetWorthSnapshotMutation() {
  const qc = useQueryClient()
  return useMutation<NetWorthSnapshotResponse[], ApiError, void>({
    mutationFn: takeNetWorthSnapshot,
    onSuccess: () => invalidateNetWorth(qc),
  })
}

/** `GET /v1/networth/assets`. Not paged. */
export function useAssets() {
  return useQuery<AssetResponse[], ApiError>({
    queryKey: assetsKey,
    queryFn: listAssets,
    staleTime: 60 * 1000,
  })
}

export function useCreateAssetMutation() {
  const qc = useQueryClient()
  return useMutation<AssetResponse, ApiError, AssetRequest>({
    mutationFn: createAsset,
    onSuccess: () => invalidateNetWorth(qc),
  })
}

export function useUpdateAssetMutation() {
  const qc = useQueryClient()
  return useMutation<AssetResponse, ApiError, { id: string; body: AssetUpdateRequest }>({
    mutationFn: ({ id, body }) => updateAsset(id, body),
    onSuccess: () => invalidateNetWorth(qc),
  })
}

export function useDeleteAssetMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: deleteAsset,
    onSuccess: () => invalidateNetWorth(qc),
  })
}

/** `GET /v1/networth/liabilities`. Not paged. */
export function useLiabilities() {
  return useQuery<LiabilityResponse[], ApiError>({
    queryKey: liabilitiesKey,
    queryFn: listLiabilities,
    staleTime: 60 * 1000,
  })
}

export function useCreateLiabilityMutation() {
  const qc = useQueryClient()
  return useMutation<LiabilityResponse, ApiError, LiabilityRequest>({
    mutationFn: createLiability,
    onSuccess: () => invalidateNetWorth(qc),
  })
}

export function useUpdateLiabilityMutation() {
  const qc = useQueryClient()
  return useMutation<LiabilityResponse, ApiError, { id: string; body: LiabilityUpdateRequest }>({
    mutationFn: ({ id, body }) => updateLiability(id, body),
    onSuccess: () => invalidateNetWorth(qc),
  })
}

export function useDeleteLiabilityMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: deleteLiability,
    onSuccess: () => invalidateNetWorth(qc),
  })
}
