/**
 * Transactions — the five `/v1/transactions*` operations.
 *
 * Three contract details drive everything in this file:
 *
 *  1. **`listTransactions` is paged and enveloped.** Its 200 requires `data`
 *     *and* `meta`, so it returns a `Page<TransactionResponse>` and never a
 *     bare array. A list fed from page one alone is a silent truncation of the
 *     member's ledger.
 *  2. **`PATCH /v1/transactions/{id}` is presence-meaningful.** A field that is
 *     absent is left alone; a field present with `null` clears it. That is why
 *     `patchTransaction` takes the patch object the caller built from what
 *     actually changed and posts it verbatim — nothing here spreads a form, and
 *     nothing here strips a deliberate `null`.
 *  3. **A transfer is not a route.** Linking a leg is
 *     `{transferGroupId: "<uuid>"}` and unlinking it is
 *     `{transferGroupId: null}`; the two helpers at the bottom exist so no
 *     screen has to remember which is which.
 */

import { api } from './client';
import { expectSuccess, unwrap, unwrapPage } from './errors';
import { pathSegment } from './paths';
import type {
  ApiResponse,
  ManualTransactionRequest,
  Page,
  TransactionPatchRequest,
  TransactionQuery,
  TransactionResponse,
} from './types';

/** `limit`'s server-side default. Stated here because a client that sends none is silently capped. */
export const DEFAULT_TRANSACTION_LIMIT = 50;

/** `limit`'s server-side maximum. A larger value is *rejected*, not clamped. */
export const MAX_TRANSACTION_LIMIT = 500;

/**
 * Turn the declared query into axios `params`.
 *
 * `undefined` entries are dropped rather than sent as empty strings, and a
 * blank `q` is dropped too — the operation states blank is the same as absent,
 * and sending `q=` would otherwise be a substring match on nothing.
 */
export function transactionParams(query: TransactionQuery): Record<string, string | number> {
  const params: Record<string, string | number> = {};
  if (query.page !== undefined) params.page = query.page;
  if (query.limit !== undefined) params.limit = query.limit;
  if (query.sort !== undefined) params.sort = query.sort;
  if (query.from !== undefined && query.from !== '') params.from = query.from;
  if (query.to !== undefined && query.to !== '') params.to = query.to;
  if (query.type !== undefined) params.type = query.type;
  if (query.accountId !== undefined && query.accountId !== '') params.accountId = query.accountId;
  if (query.categoryId !== undefined && query.categoryId !== '') {
    params.categoryId = query.categoryId;
  }
  if (query.q !== undefined && query.q.trim() !== '') params.q = query.q.trim();
  return params;
}

/**
 * `GET /v1/transactions` — one page of the household ledger, plus its totals.
 *
 * `page` is zero-based. Both halves of the answer are returned because the
 * page controls and the rows have to come from the same fetch.
 */
export async function fetchTransactions(
  query: TransactionQuery = {}
): Promise<Page<TransactionResponse>> {
  const response = await api.get<ApiResponse<TransactionResponse[]>>('/v1/transactions', {
    params: transactionParams(query),
  });
  return unwrapPage(response.data);
}

/** `GET /v1/transactions/{id}` — 404 for another household's id, never 403. */
export async function fetchTransaction(id: string): Promise<TransactionResponse> {
  const response = await api.get<ApiResponse<TransactionResponse>>(
    `/v1/transactions/${pathSegment(id)}`
  );
  return unwrap(response.data);
}

/**
 * `POST /v1/transactions` — 201.
 *
 * `accountId` must name an account of this household whose `sourceKind` is
 * `MANUAL`; anything else is 409, which is why the picker in front of this
 * call filters to `MANUAL` accounts rather than offering all of them.
 * `amount` is the absolute value and `type` carries the direction; `currency`
 * is the account's and is not accepted here.
 */
export async function createManualTransaction(
  body: ManualTransactionRequest
): Promise<TransactionResponse> {
  const response = await api.post<ApiResponse<TransactionResponse>>('/v1/transactions', body);
  return unwrap(response.data);
}

/**
 * `PATCH /v1/transactions/{id}` — a true partial update.
 *
 * The body is sent exactly as given: only what the caller put in it is
 * written, and an explicit `null` clears the field. Callers build the patch
 * from what changed, never from a whole form.
 */
export async function patchTransaction(
  id: string,
  patch: TransactionPatchRequest
): Promise<TransactionResponse> {
  const response = await api.patch<ApiResponse<TransactionResponse>>(
    `/v1/transactions/${pathSegment(id)}`,
    patch
  );
  return unwrap(response.data);
}

/**
 * `DELETE /v1/transactions/{id}` — manual rows only. `EmptySuccess`.
 *
 * A row that came from a source is 409: it would reappear on the next sync, so
 * the way to remove it is to disconnect the account it belongs to.
 */
export async function deleteTransaction(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/transactions/${pathSegment(id)}`);
  expectSuccess(response.data);
}

/**
 * Unlink a transfer leg.
 *
 * `{"transferGroupId": null}` — the explicit null is the request. A body that
 * simply omits the field would leave the link exactly where it was.
 */
export function unlinkTransfer(id: string): Promise<TransactionResponse> {
  return patchTransaction(id, { transferGroupId: null });
}
