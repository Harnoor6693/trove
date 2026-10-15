/**
 * `/v1/transactions` — the five transaction operations.
 *
 * **`listTransactions` is PAGED and its 200 requires BOTH `data` and `meta`.**
 * The shipped version of this module returned `Promise<BankTransaction[]>` and
 * threw `meta` away, which silently truncated the member's ledger at the
 * server's unstated default of 50 rows and gave the screen no way to reach page
 * two. It returns a `Page<T>` now: the rows and the totals that describe them,
 * read out of the same response body.
 */
import { api, unwrap, unwrapVoid } from './client'
import type {
  ApiResponse,
  ManualTransactionRequest,
  Page,
  PaginationMeta,
  TransactionPatchRequest,
  TransactionResponse,
  TransactionsQuery,
} from './types'

/**
 * `GET /v1/transactions`.
 *
 * The parameters are exactly the ones the operation declares (`TransactionsQuery`
 * is derived from them), so no filter is invented and none is silently dropped.
 * `page` is zero-based; `limit` defaults to 50 server-side and is capped at 500.
 * A value outside a bound is rejected by the server rather than clamped, so
 * nothing is clamped here either — a wrong number must be visible, not hidden.
 */
export async function listTransactions(
  q: TransactionsQuery = {}
): Promise<Page<TransactionResponse>> {
  const params: Record<string, string | number> = {}
  if (q.page !== undefined) params.page = q.page
  if (q.limit !== undefined) params.limit = q.limit
  if (q.sort !== undefined) params.sort = q.sort
  if (q.from !== undefined) params.from = q.from
  if (q.to !== undefined) params.to = q.to
  if (q.type !== undefined) params.type = q.type
  if (q.accountId !== undefined) params.accountId = q.accountId
  if (q.categoryId !== undefined) params.categoryId = q.categoryId
  if (q.q !== undefined && q.q !== '') params.q = q.q

  const res = await api.get<ApiResponse<TransactionResponse[]>>('/v1/transactions', { params })
  const rows = unwrap(res)
  const meta = res.data.meta
  if (!meta) {
    // The operation's 200 requires `meta`. A body without it is a contract
    // violation, and inventing a page count from `rows.length` is exactly the
    // silent truncation this module exists to stop.
    throw new Error('GET /v1/transactions answered without the required pagination meta')
  }
  return { rows, meta: meta satisfies PaginationMeta }
}

/**
 * `POST /v1/transactions` — 201. The account must be one of this household's
 * whose `sourceKind` is `MANUAL`; anything else is 409. `currency` is the
 * account's and is not accepted here. `amount` is the absolute value and `type`
 * carries the direction.
 */
export async function createManualTransaction(
  body: ManualTransactionRequest
): Promise<TransactionResponse> {
  const res = await api.post<ApiResponse<TransactionResponse>>('/v1/transactions', body)
  return unwrap(res)
}

/** `GET /v1/transactions/{id}` — 404 for an id outside the caller's household. */
export async function getTransaction(id: string): Promise<TransactionResponse> {
  const res = await api.get<ApiResponse<TransactionResponse>>(
    `/v1/transactions/${encodeURIComponent(id)}`
  )
  return unwrap(res)
}

/**
 * `PATCH /v1/transactions/{id}` — **presence-meaningful**.
 *
 * Only the keys present in `body` are written, and a key present with `null`
 * clears the field. Callers must pass a body containing only what changed:
 * spreading a whole form state in here would wipe fields the member never
 * touched. This module deliberately does not "helpfully" fill in the rest.
 *
 * Unlinking a transfer is `{transferGroupId: null}` — a different request from
 * one that does not mention `transferGroupId` at all.
 */
export async function patchTransaction(
  id: string,
  body: TransactionPatchRequest
): Promise<TransactionResponse> {
  const res = await api.patch<ApiResponse<TransactionResponse>>(
    `/v1/transactions/${encodeURIComponent(id)}`,
    body
  )
  return unwrap(res)
}

/**
 * `DELETE /v1/transactions/{id}` — manual rows only; a row from a source is 409,
 * because it would reappear on the next sync. `EmptySuccess`, so `unwrapVoid`.
 */
export async function deleteTransaction(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(`/v1/transactions/${encodeURIComponent(id)}`)
  unwrapVoid(res)
}
