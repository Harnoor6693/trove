/**
 * React Query bindings for `/v1/transactions*`.
 *
 * **How a paged list is consumed on this app.** `GET /v1/transactions` is
 * paged: its 200 requires `data` *and* `meta: {total, page, limit}`. On a phone
 * a ledger is a scroll, not a pager, so the list is a `useInfiniteQuery` driven
 * by `FlatList`'s `onEndReached`. `meta` is what bounds it: the next page
 * number is `page + 1` only while `(page + 1) * limit < total`, and
 * `undefined` otherwise, so the list stops at the real end of the ledger
 * rather than requesting empty pages forever. A `FlatList` fed from page one
 * alone would silently truncate the member's ledger, which is the failure this
 * shape exists to prevent.
 *
 * The filter object is part of the query key, so changing a filter is a new
 * query rather than a mutation of an existing cache entry.
 */

import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type UseInfiniteQueryResult,
  type UseMutationResult,
  type UseQueryResult,
  type InfiniteData,
} from '@tanstack/react-query';
import {
  createManualTransaction,
  deleteTransaction,
  fetchTransaction,
  fetchTransactions,
  patchTransaction,
} from '@api/transactions';
import type {
  ManualTransactionRequest,
  Page,
  TransactionPatchRequest,
  TransactionQuery,
  TransactionResponse,
} from '@api/types';
import { accountKeys, dashboardKeys, transactionKeys } from './queryKeys';
import { retryUnlessRefused } from './retryPolicy';

export { transactionKeys } from './queryKeys';

/**
 * The next zero-based page, or `undefined` at the end of the ledger.
 *
 * Exported so the rule is testable on its own: `meta` is the only thing that
 * knows how much of the ledger is left, and getting this wrong is either an
 * infinite request loop or a truncated list.
 */
export function nextTransactionPage(last: Page<TransactionResponse>): number | undefined {
  const { page, limit, total } = last.meta;
  if (limit <= 0) return undefined;
  const seen = (page + 1) * limit;
  return seen < total ? page + 1 : undefined;
}

/**
 * `GET /v1/transactions`, page by page.
 *
 * `query.page` is ignored — the infinite query owns the page cursor — so a
 * caller passes filters only.
 */
export function useTransactions(
  query: TransactionQuery = {},
  enabled = true
): UseInfiniteQueryResult<InfiniteData<Page<TransactionResponse>>> {
  return useInfiniteQuery({
    enabled,
    queryKey: transactionKeys.list(query),
    initialPageParam: 0,
    queryFn: ({ pageParam }) => fetchTransactions({ ...query, page: pageParam as number }),
    getNextPageParam: nextTransactionPage,
    staleTime: 30_000,
    retry: retryUnlessRefused,
  });
}

/** `GET /v1/transactions/{id}`. Idle until an id is known. */
export function useTransaction(id: string): UseQueryResult<TransactionResponse> {
  return useQuery({
    queryKey: transactionKeys.detail(id),
    queryFn: () => fetchTransaction(id),
    enabled: id !== '',
    retry: retryUnlessRefused,
  });
}

/**
 * `POST /v1/transactions`.
 *
 * The account root is invalidated as well: a new row changes what the account
 * list and the dashboard say, and the caller of this hook has already filtered
 * its picker to `MANUAL` accounts, which is the only kind the operation
 * accepts.
 */
export function useCreateManualTransaction(): UseMutationResult<
  TransactionResponse,
  unknown,
  ManualTransactionRequest
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: ManualTransactionRequest) => createManualTransaction(body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: transactionKeys.all() });
      void client.invalidateQueries({ queryKey: accountKeys.all() });
      void client.invalidateQueries({ queryKey: dashboardKeys.all() });
    },
  });
}

/**
 * `PATCH /v1/transactions/{id}` — **presence-meaningful**.
 *
 * The patch reaches the wire exactly as the caller built it. Nothing here adds
 * a field the caller left out, and nothing here strips a deliberate `null`:
 * `{transferGroupId: null}` unlinks a transfer leg and `{}` is a valid no-op,
 * and neither must be turned into the other.
 */
export function usePatchTransaction(
  id: string
): UseMutationResult<TransactionResponse, unknown, TransactionPatchRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (patch: TransactionPatchRequest) => patchTransaction(id, patch),
    onSuccess: (updated) => {
      // The response *is* the new state of this row, so the detail cache is
      // written rather than invalidated — no refetch to see your own edit.
      client.setQueryData(transactionKeys.detail(id), updated);
      // `['transactions', 'list', {}]` is a prefix, and React Query matches an
      // object in a key partially, so this reaches every filtered list without
      // also invalidating the detail entry just written above.
      void client.invalidateQueries({ queryKey: transactionKeys.list({}) });
      void client.invalidateQueries({ queryKey: dashboardKeys.all() });
    },
  });
}

/** `DELETE /v1/transactions/{id}` — manual rows only; a synced row is a 409. */
export function useDeleteTransaction(): UseMutationResult<void, unknown, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteTransaction(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: transactionKeys.all() });
      void client.invalidateQueries({ queryKey: accountKeys.all() });
      void client.invalidateQueries({ queryKey: dashboardKeys.all() });
    },
  });
}
