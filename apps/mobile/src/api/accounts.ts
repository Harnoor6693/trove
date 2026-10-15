/**
 * Accounts — the eight `/v1/accounts*` operations of `openapi/trove-core.yaml`.
 *
 * Every call goes through the one `api` instance, which owns the bearer
 * header, `X-Requested-With` and the single-flight refresh. Nothing here reads
 * the access token, retries a 401 or creates a second axios instance.
 *
 * Two shapes to keep straight, both stated by the contract:
 *
 *  - `listAccounts` and `listAccountSyncLogs` answer a **bare array** in
 *    `data`. Neither is paged — the spec says so on both operations — so
 *    `unwrap` is right and `unwrapPage` would be wrong.
 *  - `deleteAccount` answers the closed `EmptySuccess` shape, literally
 *    `{"success": true}` with no `data` key, so it goes through
 *    `expectSuccess` rather than `unwrap`.
 */

import { api } from './client';
import { expectSuccess, unwrap } from './errors';
import { pathSegment } from './paths';
import type {
  AccountRequest,
  AccountResponse,
  AccountUpdateRequest,
  ApiResponse,
  ExchangeSetupTokenRequest,
  ExchangeSetupTokenResponse,
  SyncLogResponse,
} from './types';

/** `GET /v1/accounts` — the household's accounts, newest first. Not paged. */
export async function fetchAccounts(): Promise<AccountResponse[]> {
  const response = await api.get<ApiResponse<AccountResponse[]>>('/v1/accounts');
  return unwrap(response.data);
}

/** `GET /v1/accounts/{id}` — 404 for another household's id, never 403. */
export async function fetchAccount(id: string): Promise<AccountResponse> {
  const response = await api.get<ApiResponse<AccountResponse>>(`/v1/accounts/${pathSegment(id)}`);
  return unwrap(response.data);
}

/**
 * `POST /v1/accounts` — 201.
 *
 * `currency` omitted takes the caller's own `users.currency`; there is no
 * default currency literal anywhere on either side of this call.
 */
export async function createAccount(body: AccountRequest): Promise<AccountResponse> {
  const response = await api.post<ApiResponse<AccountResponse>>('/v1/accounts', body);
  return unwrap(response.data);
}

/**
 * `PUT /v1/accounts/{id}` — label, institution and product type only.
 *
 * `sourceKind` and `currency` are not in `AccountUpdateRequest` on purpose:
 * the first decides how the row is synced and the second is the denomination
 * every transaction already stored against it was written in.
 */
export async function updateAccount(
  id: string,
  body: AccountUpdateRequest
): Promise<AccountResponse> {
  const response = await api.put<ApiResponse<AccountResponse>>(
    `/v1/accounts/${pathSegment(id)}`,
    body
  );
  return unwrap(response.data);
}

/**
 * `DELETE /v1/accounts/{id}` — removes the account **and every transaction
 * stored against it** (`ON DELETE CASCADE`). `EmptySuccess`.
 */
export async function deleteAccount(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/accounts/${pathSegment(id)}`);
  expectSuccess(response.data);
}

/**
 * `POST /v1/accounts/{id}/sync` — runs one synchronisation and answers with
 * the `sync_log` row it wrote.
 *
 * A run that ends `FAILED` or `PARTIAL_FAILURE` is a **200** carrying that
 * status, not an HTTP error: the request succeeded in recording what happened.
 * The 409 is the other thing — the account is already `SYNCING`, or no source
 * is registered for its kind — and the two are told apart by `detail`, not by
 * the slug.
 */
export async function syncAccount(id: string): Promise<SyncLogResponse> {
  const response = await api.post<ApiResponse<SyncLogResponse>>(
    `/v1/accounts/${pathSegment(id)}/sync`
  );
  return unwrap(response.data);
}

/** `GET /v1/accounts/{id}/sync-logs` — the 50 most recent runs, newest first. Not paged. */
export async function fetchAccountSyncLogs(id: string): Promise<SyncLogResponse[]> {
  const response = await api.get<ApiResponse<SyncLogResponse[]>>(
    `/v1/accounts/${pathSegment(id)}/sync-logs`
  );
  return unwrap(response.data);
}

/**
 * `POST /v1/accounts/exchange-setup-token` — 201.
 *
 * `setupToken` is a one-time credential. It is passed straight through to the
 * body and never appears in a URL, a log line or the secure store; the caller
 * clears it from state the moment this resolves. A re-exchange reuses the
 * accounts it already created, so `accountIds` comes back with the same ids
 * rather than duplicating the household's accounts.
 */
export async function exchangeSetupToken(
  body: ExchangeSetupTokenRequest
): Promise<ExchangeSetupTokenResponse> {
  const response = await api.post<ApiResponse<ExchangeSetupTokenResponse>>(
    '/v1/accounts/exchange-setup-token',
    body
  );
  return unwrap(response.data);
}
