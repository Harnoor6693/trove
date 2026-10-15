/**
 * React Query bindings for `/v1/accounts*`.
 *
 * The patterns here are the ones the other four families on this app copy;
 * they are described below, each with its rationale.
 *
 *  - **Key shape.** One root per resource (`['accounts']`), a `list` child for
 *    the collection and the bare id for a member (`['accounts', id]`), with
 *    sub-resources hanging off the member (`['accounts', id, 'sync-logs']`).
 *    Invalidating the root therefore reaches every view of the resource, which
 *    is what a mutation wants, while a detail screen still has its own key.
 *  - **Invalidation.** Every mutation invalidates its own resource root in
 *    `onSuccess`, plus the root of every *other* resource it changed as a side
 *    effect. Deleting an account cascades to its transactions and a sync
 *    writes them, and both move the dashboard figures, so all three roots are
 *    invalidated rather than only the one the call was addressed to. Leaving a
 *    cascaded resource cached is how a member ends up looking at rows on an
 *    account that no longer exists.
 */

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  createAccount,
  deleteAccount,
  exchangeSetupToken,
  fetchAccount,
  fetchAccountSyncLogs,
  fetchAccounts,
  syncAccount,
  updateAccount,
} from '@api/accounts';
import type {
  AccountRequest,
  AccountResponse,
  AccountUpdateRequest,
  ExchangeSetupTokenRequest,
  ExchangeSetupTokenResponse,
  SyncLogResponse,
} from '@api/types';
import { accountKeys, dashboardKeys, transactionKeys } from './queryKeys';
import { retryUnlessRefused } from './retryPolicy';

export { accountKeys } from './queryKeys';

/** `GET /v1/accounts`. A bare array — this list is not paged. */
export function useAccounts(): UseQueryResult<AccountResponse[]> {
  return useQuery({
    queryKey: accountKeys.list(),
    queryFn: fetchAccounts,
    staleTime: 60_000,
    retry: retryUnlessRefused,
  });
}

/** `GET /v1/accounts/{id}`. Idle until an id is known. */
export function useAccount(id: string): UseQueryResult<AccountResponse> {
  return useQuery({
    queryKey: accountKeys.detail(id),
    queryFn: () => fetchAccount(id),
    enabled: id !== '',
    retry: retryUnlessRefused,
  });
}

/** `GET /v1/accounts/{id}/sync-logs` — the 50 most recent runs. Not paged. */
export function useAccountSyncLogs(id: string): UseQueryResult<SyncLogResponse[]> {
  return useQuery({
    queryKey: accountKeys.syncLogs(id),
    queryFn: () => fetchAccountSyncLogs(id),
    enabled: id !== '',
    retry: retryUnlessRefused,
  });
}

/** `POST /v1/accounts`. */
export function useCreateAccount(): UseMutationResult<AccountResponse, unknown, AccountRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: AccountRequest) => createAccount(body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: accountKeys.all() });
    },
  });
}

/** `PUT /v1/accounts/{id}` — label, institution and product type only. */
export function useUpdateAccount(
  id: string
): UseMutationResult<AccountResponse, unknown, AccountUpdateRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: AccountUpdateRequest) => updateAccount(id, body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: accountKeys.all() });
    },
  });
}

/**
 * `DELETE /v1/accounts/{id}`.
 *
 * The account's transactions go with it (`ON DELETE CASCADE`), so the ledger
 * root is invalidated too — the transactions list is stale the moment this
 * succeeds, and leaving it cached would show the member rows on an account
 * that no longer exists.
 */
export function useDeleteAccount(): UseMutationResult<void, unknown, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteAccount(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: accountKeys.all() });
      void client.invalidateQueries({ queryKey: transactionKeys.all() });
      void client.invalidateQueries({ queryKey: dashboardKeys.all() });
    },
  });
}

/**
 * `POST /v1/accounts/{id}/sync`.
 *
 * A `FAILED` or `PARTIAL_FAILURE` run resolves rather than rejects — it is a
 * 200 carrying that status — so the screen reads `status` off the returned log
 * instead of treating the promise settling as success.
 */
export function useSyncAccount(): UseMutationResult<SyncLogResponse, unknown, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => syncAccount(id),
    onSuccess: (_log, id) => {
      void client.invalidateQueries({ queryKey: accountKeys.all() });
      void client.invalidateQueries({ queryKey: accountKeys.syncLogs(id) });
      void client.invalidateQueries({ queryKey: transactionKeys.all() });
      void client.invalidateQueries({ queryKey: dashboardKeys.all() });
    },
  });
}

/**
 * `POST /v1/accounts/exchange-setup-token`.
 *
 * The setup token is a credential: it lives in the caller's component state
 * for exactly as long as this call takes, is never persisted, and the caller
 * clears it in `onSettled`.
 */
export function useExchangeSetupToken(): UseMutationResult<
  ExchangeSetupTokenResponse,
  unknown,
  ExchangeSetupTokenRequest
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: ExchangeSetupTokenRequest) => exchangeSetupToken(body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: accountKeys.all() });
      void client.invalidateQueries({ queryKey: transactionKeys.all() });
      void client.invalidateQueries({ queryKey: dashboardKeys.all() });
    },
  });
}
