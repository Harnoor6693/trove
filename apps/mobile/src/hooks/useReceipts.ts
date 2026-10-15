/**
 * React Query bindings for `/v1/receipts*`.
 *
 * **`GET /v1/receipts` is paged, and this hook used to pretend it was not.**
 * Its 200 requires `data` *and* `meta: {total, page, limit}`; the shipped hook
 * called a `fetchReceipts(): Promise<ReceiptResponse[]>` that threw the totals
 * away, and the screen fed a `FlatList` from the result. A household past its
 * first page was shown a truncated list it was never told about. It is a
 * `useInfiniteQuery` now, bounded by `meta` exactly the way the transactions
 * ledger is: the next page is `page + 1` only while `(page + 1) * limit <
 * total`, so the list stops at the real end rather than asking for empty pages
 * for ever.
 *
 * **Polling stops on a terminal status.** `COMPLETE` and `FAILED` are the ends
 * of the pipeline. `COMPLETE` with `reviewRequired: true` is the *ordinary*
 * outcome on an install with no vision model — the image is stored and the
 * fields are the member's to fill in — not a failure, and not a reason to keep
 * polling against a chain-wide rate limiter.
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
  deleteReceipt,
  fetchReceipt,
  fetchReceiptImage,
  fetchReceiptStatus,
  fetchReceipts,
  fetchReceiptThumbnail,
  patchReceipt,
  reviewReceipt,
} from '@api/receipts';
import type {
  Page,
  ReceiptPatchRequest,
  ReceiptResponse,
  ReceiptReviewRequest,
  ReceiptStatus,
  ReceiptStatusResponse,
  ReceiptSummaryResponse,
} from '@api/types';
import { retryUnlessRefused } from './retryPolicy';

export const receiptKeys = {
  all: () => ['receipts'] as const,
  list: () => ['receipts', 'list'] as const,
  detail: (id: string) => ['receipts', id] as const,
  status: (id: string) => ['receipts', id, 'status'] as const,
  thumbnail: (id: string) => ['receipts', id, 'thumbnail'] as const,
  image: (id: string) => ['receipts', id, 'image'] as const,
};

/** The two statuses the pipeline stops at. A poller stops on either. */
export const TERMINAL_RECEIPT_STATUSES: readonly ReceiptStatus[] = ['COMPLETE', 'FAILED'];

/** True once there is nothing left to wait for. */
export function isTerminalReceiptStatus(status: ReceiptStatus): boolean {
  return TERMINAL_RECEIPT_STATUSES.includes(status);
}

/** How often a receipt still in the pipeline is re-asked. */
export const RECEIPT_POLL_MS = 2_000;

/**
 * The next zero-based page, or `undefined` at the end of the list.
 *
 * Exported so the rule is testable on its own: `meta` is the only thing that
 * knows how much is left, and getting it wrong is either an infinite request
 * loop or a silently truncated list.
 */
export function nextReceiptPage(last: Page<ReceiptSummaryResponse>): number | undefined {
  const { page, limit, total } = last.meta;
  if (limit <= 0) return undefined;
  const seen = (page + 1) * limit;
  return seen < total ? page + 1 : undefined;
}

/** `GET /v1/receipts`, page by page. */
export function useReceipts(): UseInfiniteQueryResult<InfiniteData<Page<ReceiptSummaryResponse>>> {
  return useInfiniteQuery({
    queryKey: receiptKeys.list(),
    initialPageParam: 0,
    queryFn: ({ pageParam }) => fetchReceipts({ page: pageParam as number }),
    getNextPageParam: nextReceiptPage,
    staleTime: 30_000,
    retry: retryUnlessRefused,
  });
}

/** `GET /v1/receipts/{id}` — the whole row. Idle until an id is known. */
export function useReceipt(id: string): UseQueryResult<ReceiptResponse> {
  return useQuery({
    queryKey: receiptKeys.detail(id),
    queryFn: () => fetchReceipt(id),
    enabled: id !== '',
    retry: retryUnlessRefused,
  });
}

/**
 * `GET /v1/receipts/{id}/status`, polled until the pipeline stops.
 *
 * `refetchInterval` returns `false` on a terminal status, which is how React
 * Query is told to stop — a fixed interval would poll a `COMPLETE` receipt for
 * as long as the screen stayed open.
 */
export function useReceiptStatus(
  id: string,
  enabled = true
): UseQueryResult<ReceiptStatusResponse> {
  return useQuery({
    queryKey: receiptKeys.status(id),
    queryFn: () => fetchReceiptStatus(id),
    enabled: enabled && id !== '',
    retry: retryUnlessRefused,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      if (status === undefined) return RECEIPT_POLL_MS;
      return isTerminalReceiptStatus(status) ? false : RECEIPT_POLL_MS;
    },
  });
}

/**
 * `PATCH /v1/receipts/{id}` — write fields by hand, or link a transaction.
 *
 * The patch reaches the wire exactly as the caller built it: an absent field
 * is left alone by the server, so nothing here adds one the caller omitted.
 */
export function usePatchReceipt(
  id: string
): UseMutationResult<ReceiptResponse, Error, ReceiptPatchRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (patch: ReceiptPatchRequest) => patchReceipt(id, patch),
    onSuccess: (updated) => {
      // The response *is* the new state of this row — no refetch to see your
      // own edit — and the list is invalidated because the row changed there
      // too. `matchedTransactionId` writes both halves of the link in one
      // server transaction, so the transaction caches are stale as well.
      client.setQueryData(receiptKeys.detail(id), updated);
      void client.invalidateQueries({ queryKey: receiptKeys.list() });
      void client.invalidateQueries({ queryKey: ['transactions'] });
    },
  });
}

/**
 * `PATCH /v1/receipts/{id}/review` — accept or reject the extracted fields.
 *
 * A distinct action from the patch above: that one writes fields, this one
 * records a decision. `accepted: false` marks the receipt `FAILED` and the
 * image stays stored and downloadable.
 */
export function useReviewReceipt(
  id: string
): UseMutationResult<ReceiptResponse, Error, ReceiptReviewRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: ReceiptReviewRequest) => reviewReceipt(id, body),
    onSuccess: (updated) => {
      client.setQueryData(receiptKeys.detail(id), updated);
      void client.invalidateQueries({ queryKey: receiptKeys.status(id) });
      void client.invalidateQueries({ queryKey: receiptKeys.list() });
    },
  });
}

/** `DELETE /v1/receipts/{id}` — the row, its line items and its files. */
export function useDeleteReceipt(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteReceipt(id),
    onSuccess: (_result, id) => {
      client.removeQueries({ queryKey: receiptKeys.detail(id) });
      // The image bytes go with the row: nothing fetched for a deleted
      // receipt is left in the cache for a later screen to render.
      client.removeQueries({ queryKey: receiptKeys.thumbnail(id) });
      client.removeQueries({ queryKey: receiptKeys.image(id) });
      void client.invalidateQueries({ queryKey: receiptKeys.list() });
      void client.invalidateQueries({ queryKey: ['transactions'] });
    },
  });
}

/**
 * The receipt's thumbnail, as a `data:` URI.
 *
 * Fetched through `api` with the member's own credentials — the route is
 * authenticated and React Native's `Image` cannot carry the header, so the
 * bytes come to us and the component renders what we hold. `gcTime` is short
 * on purpose: a photograph of the household's spending is not something to
 * keep in memory once the screen that wanted it has gone.
 */
export function useReceiptThumbnail(id: string, enabled = true): UseQueryResult<string> {
  return useQuery({
    queryKey: receiptKeys.thumbnail(id),
    queryFn: () => fetchReceiptThumbnail(id),
    enabled: enabled && id !== '',
    staleTime: 5 * 60_000,
    gcTime: 60_000,
    retry: retryUnlessRefused,
  });
}

/** The full stored JPEG, as a `data:` URI. Same rules as the thumbnail. */
export function useReceiptImage(id: string, enabled = true): UseQueryResult<string> {
  return useQuery({
    queryKey: receiptKeys.image(id),
    queryFn: () => fetchReceiptImage(id),
    enabled: enabled && id !== '',
    staleTime: 5 * 60_000,
    gcTime: 60_000,
    retry: retryUnlessRefused,
  });
}
