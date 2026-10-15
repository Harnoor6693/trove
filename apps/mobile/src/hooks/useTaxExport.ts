/**
 * React Query bindings for `/v1/tax-export/*`.
 *
 * **Polling stops on a terminal status.** `COMPLETE` and `FAILED` are the ends
 * of a run; `PENDING` and `GENERATING` are polled at {@link TAX_EXPORT_POLL_MS}.
 * `useTaxExportRunFile` is deliberately its own mutation rather than something
 * a query fetches automatically: downloading is a member action taken once a
 * run is `COMPLETE`, not data a screen loads on mount.
 */

import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
  type UseInfiniteQueryResult,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  createTaxExportRun,
  deleteTaxExportRun,
  fetchTaxExportRun,
  fetchTaxExportRunBytes,
  fetchTaxExportRunFile,
  fetchTaxExportRuns,
  fetchTaxLines,
  setTaxLine,
  type TaxExportFile,
} from '@api/taxexport';
import { shareBytes } from '@lib/shareFile';
import type {
  CountryCode,
  CreateTaxExportRunRequest,
  Page,
  TaxExportFormat,
  TaxExportRunResponse,
  TaxExportStatus,
  TaxLineMappingRequest,
  TaxLineMappingResponse,
} from '@api/types';
import { retryUnlessRefused } from './retryPolicy';

export const taxExportKeys = {
  all: () => ['tax-export'] as const,
  linesAll: () => ['tax-export', 'lines'] as const,
  lines: (jurisdiction?: CountryCode) =>
    ['tax-export', 'lines', jurisdiction ?? 'default'] as const,
  runs: () => ['tax-export', 'runs'] as const,
  run: (id: string) => ['tax-export', 'runs', id] as const,
};

/** The two statuses a run stops at. A poller stops on either. */
export const TERMINAL_TAX_EXPORT_STATUSES: readonly TaxExportStatus[] = ['COMPLETE', 'FAILED'];

/** True once there is nothing left to wait for. */
export function isTerminalTaxExportStatus(status: TaxExportStatus): boolean {
  return TERMINAL_TAX_EXPORT_STATUSES.includes(status);
}

/** How often a run still `PENDING`/`GENERATING` is re-asked. */
export const TAX_EXPORT_POLL_MS = 2_000;

/** `GET /v1/tax-export/lines`. Omitted `jurisdiction` means the caller's own `users.country`. */
export function useTaxLines(jurisdiction?: CountryCode): UseQueryResult<TaxLineMappingResponse[]> {
  return useQuery({
    queryKey: taxExportKeys.lines(jurisdiction),
    queryFn: () => fetchTaxLines(jurisdiction),
    staleTime: 60_000,
    retry: retryUnlessRefused,
  });
}

/**
 * `PUT /v1/tax-export/lines/{categoryId}` — writes the caller's own override
 * for one category.
 *
 * Invalidates the whole `['tax-export', 'lines']` prefix rather than one
 * jurisdiction's key: `useTaxLines()` (no argument, the screen's own query)
 * caches under `'default'`, while every row's `jurisdiction` field is a real
 * `CountryCode` (`'CA'`/`'US'`) — invalidating only that literal jurisdiction
 * would never match the `'default'`-keyed query actually on screen, so a
 * successful save would leave the list showing the stale row. React Query
 * prefix-matches a query key, so the broader key catches every variant.
 */
export function useSetTaxLine(): UseMutationResult<
  TaxLineMappingResponse,
  Error,
  { categoryId: string; body: TaxLineMappingRequest }
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ categoryId, body }) => setTaxLine(categoryId, body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: taxExportKeys.linesAll() });
    },
  });
}

/** The next zero-based page, or `undefined` at the end — same rule as {@link nextReceiptPage}. */
export function nextTaxExportRunPage(last: Page<TaxExportRunResponse>): number | undefined {
  const { page, limit, total } = last.meta;
  if (limit <= 0) return undefined;
  const seen = (page + 1) * limit;
  return seen < total ? page + 1 : undefined;
}

/** `GET /v1/tax-export/runs`, page by page — the caller's own runs, newest first. */
export function useTaxExportRuns(): UseInfiniteQueryResult<
  InfiniteData<Page<TaxExportRunResponse>>
> {
  return useInfiniteQuery({
    queryKey: taxExportKeys.runs(),
    initialPageParam: 0,
    queryFn: ({ pageParam }) => fetchTaxExportRuns({ page: pageParam as number }),
    getNextPageParam: nextTaxExportRunPage,
    staleTime: 15_000,
    retry: retryUnlessRefused,
  });
}

/**
 * `POST /v1/tax-export/runs` — starts a whole-tax-year export. Answers 202
 * with the row `PENDING` (or already `FAILED` if the executor's queue was
 * full); the caller polls {@link useTaxExportRun} from there.
 */
export function useCreateTaxExportRun(): UseMutationResult<
  TaxExportRunResponse,
  Error,
  CreateTaxExportRunRequest
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateTaxExportRunRequest) => createTaxExportRun(body),
    onSuccess: (run) => {
      client.setQueryData(taxExportKeys.run(run.id), run);
      void client.invalidateQueries({ queryKey: taxExportKeys.runs() });
    },
  });
}

/**
 * `GET /v1/tax-export/runs/{id}`, polled until the run stops. `refetchInterval`
 * returns `false` on `COMPLETE`/`FAILED`, which is how React Query is told to
 * stop — a fixed interval would poll a finished run for as long as the screen
 * stayed open.
 */
export function useTaxExportRun(id: string, enabled = true): UseQueryResult<TaxExportRunResponse> {
  return useQuery({
    queryKey: taxExportKeys.run(id),
    queryFn: () => fetchTaxExportRun(id),
    enabled: enabled && id !== '',
    retry: retryUnlessRefused,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      if (status === undefined) return TAX_EXPORT_POLL_MS;
      return isTerminalTaxExportStatus(status) ? false : TAX_EXPORT_POLL_MS;
    },
  });
}

/** `DELETE /v1/tax-export/runs/{id}` — the row and its generated file together. */
export function useDeleteTaxExportRun(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteTaxExportRun(id),
    onSuccess: (_result, id) => {
      client.removeQueries({ queryKey: taxExportKeys.run(id) });
      void client.invalidateQueries({ queryKey: taxExportKeys.runs() });
    },
  });
}

/**
 * `GET /v1/tax-export/runs/{id}/download`, through `api` — never a bare
 * `fetch`, never a token in the URL. A mutation, not a query: this is a
 * member pressing "view", not data a screen loads unasked. A run that is not
 * `COMPLETE` rejects with the ordinary 409 `conflict`; the caller reads that
 * through `toApiFailure`/`slugOf` like every other refusal.
 */
export function useTaxExportRunFile(): UseMutationResult<
  TaxExportFile,
  Error,
  { id: string; format: TaxExportFormat }
> {
  return useMutation({
    mutationFn: ({ id, format }) => fetchTaxExportRunFile(id, format),
  });
}

const SHARE_TYPE: Record<TaxExportFormat, { ext: string; mimeType: string; uti: string }> = {
  CSV: { ext: 'csv', mimeType: 'text/csv', uti: 'public.comma-separated-values-text' },
  PDF: { ext: 'pdf', mimeType: 'application/pdf', uti: 'com.adobe.pdf' },
};

/**
 * "Save or share": the run's bytes through `api` (arraybuffer), written to the
 * app cache as `tax-export-<year>.<csv|pdf>`, handed to the system share sheet
 * and deleted when it returns (`shareBytes`). The mutation resolves to nothing,
 * so the bytes never sit in React Query's cache. A 409 is "still generating".
 */
export function useShareTaxExportRun(): UseMutationResult<
  void,
  Error,
  { id: string; format: TaxExportFormat; taxYear: number }
> {
  return useMutation({
    mutationFn: async ({ id, format, taxYear }) => {
      const bytes = await fetchTaxExportRunBytes(id, format);
      const { ext, mimeType, uti } = SHARE_TYPE[format];
      await shareBytes(bytes, `tax-export-${taxYear}.${ext}`, mimeType, uti);
    },
  });
}
