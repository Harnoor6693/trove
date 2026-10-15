/**
 * Receipts — the paged list, one row, the status poll, and the four mutations.
 *
 * The list is paged and consumes the server's `meta`; nothing here invents a
 * page count from `rows.length`. Upload writes a row and starts a background
 * pipeline, so it invalidates the list and hands back an id to poll.
 */
import { useEffect } from 'react'
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query'
import {
  deleteReceipt,
  getReceipt,
  getReceiptStatus,
  isTerminalReceiptStatus,
  listReceipts,
  patchReceipt,
  reviewReceipt,
  uploadReceipt,
  type UploadReceiptInput,
} from '@api/receipts'
import type { ApiError } from '@api/client'
import type {
  Page,
  ReceiptPatchRequest,
  ReceiptResponse,
  ReceiptReviewRequest,
  ReceiptStatusResponse,
  ReceiptSummaryResponse,
  ReceiptUploadResponse,
  ReceiptsQuery,
} from '@api/types'

export const receiptsKey = ['receipts'] as const
export const receiptsListKey = (q: ReceiptsQuery) => ['receipts', 'list', q] as const
export const receiptKey = (id: string) => ['receipts', 'detail', id] as const
export const receiptStatusKey = (id: string) => ['receipts', 'status', id] as const

function invalidateReceipts(qc: QueryClient, id?: string): void {
  void qc.invalidateQueries({ queryKey: receiptsKey })
  if (id) void qc.invalidateQueries({ queryKey: receiptKey(id) })
}

/** One page of the household's receipts. Every member sees every receipt. */
export function useReceipts(q: ReceiptsQuery) {
  return useQuery<Page<ReceiptSummaryResponse>, ApiError>({
    queryKey: receiptsListKey(q),
    queryFn: () => listReceipts(q),
    placeholderData: keepPreviousData,
    staleTime: 15 * 1000,
  })
}

export function useReceipt(id: string | null) {
  return useQuery<ReceiptResponse, ApiError>({
    queryKey: receiptKey(id ?? ''),
    queryFn: () => getReceipt(id as string),
    enabled: id !== null,
  })
}

/**
 * `GET /v1/receipts/{id}/status`, polled to completion.
 *
 * `COMPLETE` and `FAILED` are terminal and the interval stops on either — a
 * poller that keeps asking after a terminal status is a request per second per
 * open tab, for ever. The route exists precisely so this costs a fraction of
 * refetching the whole row, so nothing here polls `getReceipt` instead.
 */
export function useReceiptStatus(id: string | null, opts: { intervalMs?: number } = {}) {
  const interval = opts.intervalMs ?? 2000
  const qc = useQueryClient()
  const query = useQuery<ReceiptStatusResponse, ApiError>({
    queryKey: receiptStatusKey(id ?? ''),
    queryFn: () => getReceiptStatus(id as string),
    enabled: id !== null,
    refetchInterval: (query) => {
      const status = query.state.data?.status
      if (status && isTerminalReceiptStatus(status)) return false
      return interval
    },
    staleTime: 0,
  })
  // The row the list shows changed when the poll finished. The status entry is
  // left alone: invalidating it would cancel and restart the poll that just ended.
  const terminal = query.data !== undefined && isTerminalReceiptStatus(query.data.status)
  useEffect(() => {
    if (!terminal || id === null) return
    void qc.invalidateQueries({ queryKey: receiptsKey, predicate: (q) => q.queryKey[1] !== 'status' })
  }, [terminal, id, qc])
  return query
}

/**
 * `POST /v1/receipts/upload` — 202, and the row's fields do not exist yet.
 *
 * The list is invalidated because the row is real the moment this returns; the
 * caller polls `useReceiptStatus` with the id for the rest.
 */
export function useUploadReceiptMutation() {
  const qc = useQueryClient()
  return useMutation<ReceiptUploadResponse, ApiError, UploadReceiptInput>({
    mutationFn: uploadReceipt,
    onSuccess: () => invalidateReceipts(qc),
  })
}

/**
 * `PATCH /v1/receipts/{id}` — the body is passed through VERBATIM. This hook
 * does not merge the row's current values in: there is no way to clear a field
 * to null through this operation, so a "helpful" merge would rewrite fields the
 * member never touched.
 */
export function usePatchReceiptMutation() {
  const qc = useQueryClient()
  return useMutation<ReceiptResponse, ApiError, { id: string; patch: ReceiptPatchRequest }>({
    mutationFn: ({ id, patch }) => patchReceipt(id, patch),
    onSuccess: (_row, { id }) => invalidateReceipts(qc, id),
  })
}

/**
 * `PATCH /v1/receipts/{id}/review` — the REVIEW_REQUIRED resolution, a decision
 * rather than a correction, and therefore its own mutation and its own control.
 */
export function useReviewReceiptMutation() {
  const qc = useQueryClient()
  return useMutation<ReceiptResponse, ApiError, { id: string; body: ReceiptReviewRequest }>({
    mutationFn: ({ id, body }) => reviewReceipt(id, body),
    onSuccess: (_row, { id }) => invalidateReceipts(qc, id),
  })
}

/** `DELETE /v1/receipts/{id}` — the row, its line items and the files on disk. */
export function useDeleteReceiptMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: deleteReceipt,
    onSuccess: (_void, id) => {
      qc.removeQueries({ queryKey: receiptKey(id) })
      invalidateReceipts(qc)
    },
  })
}
