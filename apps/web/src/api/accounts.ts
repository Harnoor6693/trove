/**
 * `/v1/accounts` — the eight account operations, all of them, and nothing else.
 *
 * Every shape here comes from `./types`, which derives it from the generated
 * schema. Nothing on this module hand-types a server DTO.
 *
 * Three of these answer the closed `EmptySuccess` body (`{"success":true}` with
 * no `data` key at all), so they go through `unwrapVoid` and never `unwrap` —
 * reading `data` off one would raise "success envelope with no data" on a
 * request that in fact succeeded.
 */
import { api, unwrap, unwrapVoid } from './client'
import type {
  AccountRequest,
  AccountResponse,
  AccountUpdateRequest,
  ApiResponse,
  ExchangeSetupTokenRequest,
  ExchangeSetupTokenResponse,
  SyncLogResponse,
} from './types'

/** `GET /v1/accounts` — a bare array, newest first. The operation states it is not paged. */
export async function listAccounts(): Promise<AccountResponse[]> {
  const res = await api.get<ApiResponse<AccountResponse[]>>('/v1/accounts')
  return unwrap(res)
}

/** `GET /v1/accounts/{id}` — 404 for an id outside the caller's household, never 403. */
export async function getAccount(id: string): Promise<AccountResponse> {
  const res = await api.get<ApiResponse<AccountResponse>>(`/v1/accounts/${encodeURIComponent(id)}`)
  return unwrap(res)
}

/** `POST /v1/accounts` — 201. `currency` may be omitted; the server uses the caller's own. */
export async function createAccount(body: AccountRequest): Promise<AccountResponse> {
  const res = await api.post<ApiResponse<AccountResponse>>('/v1/accounts', body)
  return unwrap(res)
}

/**
 * `PUT /v1/accounts/{id}` — presentation only. The request type has no
 * `sourceKind` and no `currency` because the server will not accept either:
 * the first decides how the row syncs and the second is the denomination every
 * transaction already stored against it was written in.
 */
export async function updateAccount(
  id: string,
  body: AccountUpdateRequest
): Promise<AccountResponse> {
  const res = await api.put<ApiResponse<AccountResponse>>(
    `/v1/accounts/${encodeURIComponent(id)}`,
    body
  )
  return unwrap(res)
}

/**
 * `DELETE /v1/accounts/{id}` — removes the account **and every transaction on
 * it** (`ON DELETE CASCADE`). `EmptySuccess`, so `unwrapVoid`.
 */
export async function deleteAccount(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(`/v1/accounts/${encodeURIComponent(id)}`)
  unwrapVoid(res)
}

/**
 * `POST /v1/accounts/{id}/sync` — one run, answering with the `sync_log` row.
 *
 * A run that ends `PARTIAL_FAILURE` or `FAILED` is a 200 with that status in the
 * body, not an HTTP error: the request succeeded in recording what happened.
 * 409 is the account already being claimed by another run, or no source being
 * registered for its kind yet; the two differ by `detail`, not by slug.
 */
export async function syncAccount(id: string): Promise<SyncLogResponse> {
  const res = await api.post<ApiResponse<SyncLogResponse>>(
    `/v1/accounts/${encodeURIComponent(id)}/sync`
  )
  return unwrap(res)
}

/** `GET /v1/accounts/{id}/sync-logs` — a bare array, newest first, capped at 50 by the server. */
export async function listAccountSyncLogs(id: string): Promise<SyncLogResponse[]> {
  const res = await api.get<ApiResponse<SyncLogResponse[]>>(
    `/v1/accounts/${encodeURIComponent(id)}/sync-logs`
  )
  return unwrap(res)
}

/**
 * `POST /v1/accounts/exchange-setup-token` — 201.
 *
 * The setup token is a credential. It travels in the body (never a query
 * string), it is not logged here or anywhere, and no caller retains it after the
 * promise settles. The response carries `syncLogs` — one run per connected
 * account — not the single `syncLog` the pre-contract shape assumed.
 */
export async function exchangeSetupToken(
  body: ExchangeSetupTokenRequest
): Promise<ExchangeSetupTokenResponse> {
  const res = await api.post<ApiResponse<ExchangeSetupTokenResponse>>(
    '/v1/accounts/exchange-setup-token',
    body
  )
  return unwrap(res)
}
