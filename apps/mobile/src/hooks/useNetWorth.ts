/**
 * React Query bindings for `/v1/networth/*`.
 *
 * The series (`useNetWorthSeries`) and the two ledgers (`useAssets`/
 * `useLiabilities`) are kept as separate query roots on purpose: editing an
 * asset does not retroactively change a snapshot already taken, so a mutation
 * on the ledgers never invalidates the series — only {@link useTakeNetWorthSnapshot}
 * does, because that is the one write that actually changes what the series
 * shows.
 */

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  createAsset,
  createLiability,
  deleteAsset,
  deleteLiability,
  fetchAssets,
  fetchLiabilities,
  fetchNetWorthSeries,
  takeNetWorthSnapshot,
  updateAsset,
  updateLiability,
} from '@api/networth';
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
} from '@api/types';
import { retryUnlessRefused } from './retryPolicy';

export const netWorthKeys = {
  all: () => ['networth'] as const,
  series: (range?: NetWorthRange) => ['networth', 'series', range ?? 'default'] as const,
  assets: () => ['networth', 'assets'] as const,
  liabilities: () => ['networth', 'liabilities'] as const,
};

/** `GET /v1/networth/series` — one line per currency; the hook never sums the array. */
export function useNetWorthSeries(range?: NetWorthRange): UseQueryResult<NetWorthSeriesResponse> {
  return useQuery({
    queryKey: netWorthKeys.series(range),
    queryFn: () => fetchNetWorthSeries(range),
    staleTime: 60_000,
    retry: retryUnlessRefused,
  });
}

/**
 * `POST /v1/networth/snapshot` — the explicit "refresh the series now" action.
 * Re-running it on the same local date updates the existing row(s), so this
 * invalidates every range's series query rather than guessing which one to
 * refetch.
 */
export function useTakeNetWorthSnapshot(): UseMutationResult<
  NetWorthSnapshotResponse[],
  Error,
  void
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => takeNetWorthSnapshot(),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: netWorthKeys.all() });
    },
  });
}

/** `GET /v1/networth/assets` — the household's shared ledger. Not paged. */
export function useAssets(): UseQueryResult<AssetResponse[]> {
  return useQuery({
    queryKey: netWorthKeys.assets(),
    queryFn: fetchAssets,
    staleTime: 30_000,
    retry: retryUnlessRefused,
  });
}

/** `POST /v1/networth/assets`. */
export function useCreateAsset(): UseMutationResult<AssetResponse, Error, AssetRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: AssetRequest) => createAsset(body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: netWorthKeys.assets() });
    },
  });
}

/** `PUT /v1/networth/assets/{id}` — a full replace of the writable fields. */
export function useUpdateAsset(
  id: string
): UseMutationResult<AssetResponse, Error, AssetUpdateRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: AssetUpdateRequest) => updateAsset(id, body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: netWorthKeys.assets() });
    },
  });
}

/** `DELETE /v1/networth/assets/{id}`. */
export function useDeleteAsset(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteAsset(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: netWorthKeys.assets() });
    },
  });
}

/** `GET /v1/networth/liabilities` — the household's shared ledger. Not paged. */
export function useLiabilities(): UseQueryResult<LiabilityResponse[]> {
  return useQuery({
    queryKey: netWorthKeys.liabilities(),
    queryFn: fetchLiabilities,
    staleTime: 30_000,
    retry: retryUnlessRefused,
  });
}

/** `POST /v1/networth/liabilities`. */
export function useCreateLiability(): UseMutationResult<
  LiabilityResponse,
  Error,
  LiabilityRequest
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: LiabilityRequest) => createLiability(body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: netWorthKeys.liabilities() });
    },
  });
}

/** `PUT /v1/networth/liabilities/{id}` — a full replace. */
export function useUpdateLiability(
  id: string
): UseMutationResult<LiabilityResponse, Error, LiabilityUpdateRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: LiabilityUpdateRequest) => updateLiability(id, body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: netWorthKeys.liabilities() });
    },
  });
}

/** `DELETE /v1/networth/liabilities/{id}`. */
export function useDeleteLiability(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteLiability(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: netWorthKeys.liabilities() });
    },
  });
}
