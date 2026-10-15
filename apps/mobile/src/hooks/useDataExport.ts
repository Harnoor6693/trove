/**
 * React Query bindings for `/v1/me/export*` and `POST /v1/me/delete`.
 *
 * **Nothing here caches a credential.** The step-up body is a mutation
 * *variable*, not a query key and not a query result: React Query keeps
 * `mutation.variables` alive until the mutation is reset, so every step-up
 * mutation below is `reset()` by its caller on settle. There is no key derived
 * from a password, which would put one in the cache index itself.
 *
 * **Polling stops on a terminal status**, the same rule `useTaxExport` follows:
 * `COMPLETE` and `FAILED` are the ends of a run, `PENDING` and `GENERATING`
 * are re-asked at {@link DATA_EXPORT_POLL_MS}. A fixed interval would poll a
 * finished run for as long as the screen stayed open.
 */

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  createDataExport,
  fetchDataExport,
  fetchDataExportArchive,
  fetchDataExports,
} from '@api/dataexport';
import { shareBytes } from '@lib/shareFile';
import { deleteMyAccount } from '@api/me';
import type {
  DataExportRunResponse,
  DataExportStatus,
  DataExportsQuery,
  Page,
  StepUpRequest,
} from '@api/types';
import { retryUnlessRefused } from './retryPolicy';

export const dataExportKeys = {
  all: () => ['data-export'] as const,
  list: (query: DataExportsQuery) => ['data-export', 'list', query] as const,
  run: (id: string) => ['data-export', id] as const,
};

/** The two statuses a run stops at. A poller stops on either. */
export const TERMINAL_DATA_EXPORT_STATUSES: readonly DataExportStatus[] = ['COMPLETE', 'FAILED'];

/** True once there is nothing left to wait for. */
export function isTerminalDataExportStatus(status: DataExportStatus): boolean {
  return TERMINAL_DATA_EXPORT_STATUSES.includes(status);
}

/** How often a run still `PENDING`/`GENERATING` is re-asked. */
export const DATA_EXPORT_POLL_MS = 2_000;

/** `GET /v1/me/export` — one page of the caller's own runs, newest first. */
export function useDataExports(
  query: DataExportsQuery = {}
): UseQueryResult<Page<DataExportRunResponse>> {
  return useQuery({
    queryKey: dataExportKeys.list(query),
    queryFn: () => fetchDataExports(query),
    staleTime: 15_000,
    retry: retryUnlessRefused,
  });
}

/**
 * `POST /v1/me/export` — 202 with the run row.
 *
 * The variable is the step-up credential. Callers must `reset()` this mutation
 * once they have read the result, so the password does not sit in
 * `mutation.variables` for the life of the screen.
 */
export function useCreateDataExport(): UseMutationResult<
  DataExportRunResponse,
  Error,
  StepUpRequest
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: StepUpRequest) => createDataExport(body),
    onSuccess: (run) => {
      client.setQueryData(dataExportKeys.run(run.id), run);
      void client.invalidateQueries({ queryKey: dataExportKeys.all() });
    },
  });
}

/**
 * `GET /v1/me/export/{id}`, polled until the run stops. `refetchInterval`
 * returns `false` on a terminal status, which is how React Query is told to
 * stop.
 */
export function useDataExport(id: string, enabled = true): UseQueryResult<DataExportRunResponse> {
  return useQuery({
    queryKey: dataExportKeys.run(id),
    queryFn: () => fetchDataExport(id),
    enabled: enabled && id !== '',
    retry: retryUnlessRefused,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      if (status === undefined) return DATA_EXPORT_POLL_MS;
      return isTerminalDataExportStatus(status) ? false : DATA_EXPORT_POLL_MS;
    },
  });
}

/**
 * "Save or share" for a `COMPLETE` run: `GET /v1/me/export/{id}/download`
 * through `api` (arraybuffer, content type checked), written to the app cache
 * as `trove-export.zip`, handed to the system share sheet and deleted when it
 * returns (`shareBytes`). Resolves to nothing, so the archive never sits in
 * React Query's cache. A 409 (not ready, or the 24-hour link expired) is read
 * through `toApiFailure` by the caller.
 */
export function useShareDataExport(): UseMutationResult<void, Error, string> {
  return useMutation({
    mutationFn: async (id: string) => {
      const archive = await fetchDataExportArchive(id);
      await shareBytes(archive.bytes, 'trove-export.zip', 'application/zip', 'public.zip-archive');
    },
  });
}

/**
 * `POST /v1/me/delete`. Same credential rule as the export: `reset()` after
 * it settles.
 *
 * Deliberately **no** `onSuccess` cache work here. Every session of the
 * account is revoked server-side the instant this returns, so the right next
 * step is the caller's `clearSession()` and a bounce to the connect screen —
 * invalidating queries would only fire a round of requests that are all about
 * to 401.
 */
export function useDeleteMyAccount(): UseMutationResult<void, Error, StepUpRequest> {
  return useMutation({ mutationFn: (body: StepUpRequest) => deleteMyAccount(body) });
}
