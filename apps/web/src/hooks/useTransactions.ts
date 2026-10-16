/**
 * Transactions — the paged list, one row, and the three mutations.
 *
 * **Pattern 1, the paged list.** The query key carries the WHOLE query object,
 * so changing any filter or the page index is a different cache entry rather
 * than a mutation of one. `placeholderData: keepPreviousData` holds the page
 * that is on screen while the next one loads, so the table never flashes empty
 * between pages. The pager is driven by `meta.total / meta.page / meta.limit`
 * from the server — no page count is invented from `rows.length`.
 */
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query'
import {
  createManualTransaction,
  deleteTransaction,
  getTransaction,
  listTransactions,
  patchTransaction,
} from '@api/transactions'
import { budgetsKey } from './useBudgetPeriods'
import { dashboardTodayKey } from './useDashboardToday'
import { notificationsKey } from './useNotifications'
import type { ApiError } from '@api/client'
import type {
  ManualTransactionRequest,
  Page,
  TransactionPatchRequest,
  TransactionResponse,
  TransactionsQuery,
} from '@api/types'

export const transactionsKey = ['transactions'] as const
export const transactionsListKey = (q: TransactionsQuery) =>
  ['transactions', 'list', q] as const
export const transactionKey = (id: string) => ['transactions', 'detail', id] as const

/** Pattern 2: family prefix, plus the dashboard, which is derived from these rows. */
export function invalidateLedger(qc: QueryClient): void {
  void qc.invalidateQueries({ queryKey: transactionsKey })
  void qc.invalidateQueries({ queryKey: dashboardTodayKey })
  // Envelope `spent`/`health` and alerts are derived from transactions.
  void qc.invalidateQueries({ queryKey: budgetsKey })
  void qc.invalidateQueries({ queryKey: notificationsKey })
}

/**
 * One page of the ledger.
 *
 * `enabled` is for a caller whose query is not known until another query has
 * answered — the transfer picker cannot name its date window until it has the
 * row it is pairing — and defaults to on, so the list page passes nothing.
 */
export function useTransactions(q: TransactionsQuery, opts: { enabled?: boolean } = {}) {
  return useQuery<Page<TransactionResponse>, ApiError>({
    queryKey: transactionsListKey(q),
    queryFn: () => listTransactions(q),
    placeholderData: keepPreviousData,
    staleTime: 30 * 1000,
    enabled: opts.enabled ?? true,
  })
}

export function useTransaction(id: string | null) {
  return useQuery<TransactionResponse, ApiError>({
    queryKey: transactionKey(id ?? ''),
    queryFn: () => getTransaction(id as string),
    enabled: id !== null,
  })
}

export function useCreateManualTransactionMutation() {
  const qc = useQueryClient()
  return useMutation<TransactionResponse, ApiError, ManualTransactionRequest>({
    mutationFn: createManualTransaction,
    onSuccess: () => invalidateLedger(qc),
  })
}

/**
 * `PATCH /v1/transactions/{id}` — presence-meaningful.
 *
 * `patch` is passed through verbatim. This hook deliberately does NOT merge the
 * row's current values into the body: an absent key is left alone by the server
 * and a key present with `null` is cleared, so a "helpful" merge here would
 * rewrite fields the member never touched, and an unlink (`transferGroupId:
 * null`) would become indistinguishable from a no-op.
 */
export function usePatchTransactionMutation() {
  const qc = useQueryClient()
  return useMutation<TransactionResponse, ApiError, { id: string; patch: TransactionPatchRequest }>(
    {
      mutationFn: ({ id, patch }) => patchTransaction(id, patch),
      onSuccess: () => invalidateLedger(qc),
    }
  )
}

/** Manual rows only; a row from a source answers 409 and the screen says why. */
export function useDeleteTransactionMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: deleteTransaction,
    onSuccess: () => invalidateLedger(qc),
  })
}
