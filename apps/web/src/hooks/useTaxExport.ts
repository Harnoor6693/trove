/**
 * Tax export — the category-to-line map, the run list (polled while a run is
 * generating), and the run mutations (§4.12, step 203).
 *
 * The download itself is NOT a query: it is a one-shot authenticated fetch a
 * button triggers, exactly like the receipt download (`ReceiptDetail.tsx`),
 * so it has no hook here — see `TaxExport/RunsPanel.tsx`.
 */
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query'
import {
  createTaxExportRun,
  deleteTaxExportRun,
  listTaxExportRuns,
  listTaxLines,
  setTaxLine,
} from '@api/taxexport'
import type { ApiError } from '@api/client'
import type {
  CountryCode,
  CreateTaxExportRunRequest,
  Page,
  TaxExportRunResponse,
  TaxExportRunsQuery,
  TaxLineMappingRequest,
  TaxLineMappingResponse,
} from '@api/types'

export const taxExportKey = ['tax-export'] as const
export const taxLinesKey = (jurisdiction: CountryCode | undefined) =>
  ['tax-export', 'lines', jurisdiction ?? ''] as const
export const taxExportRunsListKey = (q: TaxExportRunsQuery) =>
  ['tax-export', 'runs', q] as const

function invalidateTaxExport(qc: QueryClient): void {
  void qc.invalidateQueries({ queryKey: taxExportKey })
}

/** `GET /v1/tax-export/lines?jurisdiction=`. Omitted jurisdiction defaults to the caller's own country. */
export function useTaxLines(jurisdiction?: CountryCode) {
  return useQuery<TaxLineMappingResponse[], ApiError>({
    queryKey: taxLinesKey(jurisdiction),
    queryFn: () => listTaxLines(jurisdiction),
    staleTime: 60 * 1000,
  })
}

export function useSetTaxLineMutation() {
  const qc = useQueryClient()
  return useMutation<
    TaxLineMappingResponse,
    ApiError,
    { categoryId: string; body: TaxLineMappingRequest }
  >({
    mutationFn: ({ categoryId, body }) => setTaxLine(categoryId, body),
    onSuccess: () => invalidateTaxExport(qc),
  })
}

/**
 * `GET /v1/tax-export/runs?page&limit`. Keeps polling every three seconds
 * while the CURRENT PAGE holds a `PENDING` or `GENERATING` row, and stops the
 * moment every row on it is terminal (`COMPLETE`/`FAILED`) — the same
 * terminal-status pattern `useReceiptStatus` uses, applied to a list instead
 * of a single row because a run has no dedicated single-row poll route beyond
 * `getTaxExportRun`, which this list already answers with fresher data on
 * every page fetch.
 */
export function useTaxExportRuns(q: TaxExportRunsQuery = {}) {
  return useQuery<Page<TaxExportRunResponse>, ApiError>({
    queryKey: taxExportRunsListKey(q),
    queryFn: () => listTaxExportRuns(q),
    placeholderData: keepPreviousData,
    refetchInterval: (query) => {
      const rows = query.state.data?.rows ?? []
      const hasPending = rows.some((r) => r.status === 'PENDING' || r.status === 'GENERATING')
      return hasPending ? 3000 : false
    },
  })
}

export function useCreateTaxExportRunMutation() {
  const qc = useQueryClient()
  return useMutation<TaxExportRunResponse, ApiError, CreateTaxExportRunRequest>({
    mutationFn: createTaxExportRun,
    onSuccess: () => invalidateTaxExport(qc),
  })
}

export function useDeleteTaxExportRunMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: deleteTaxExportRun,
    onSuccess: () => invalidateTaxExport(qc),
  })
}
