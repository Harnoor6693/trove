/**
 * Accounts — the list query and every account mutation.
 *
 * Query-key shape (pattern 1 for this family, copied by B–E): a family prefix
 * `['accounts']` with a discriminating segment under it. Invalidating the prefix
 * therefore covers the list, the per-account detail and the sync history at
 * once, and stays correct when a later family adds a fourth query beneath it.
 */
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import {
  createAccount,
  deleteAccount,
  getAccount,
  listAccountSyncLogs,
  listAccounts,
  syncAccount,
  updateAccount,
} from '@api/accounts'
import { invalidateLedger } from './useTransactions'
import type { ApiError } from '@api/client'
import type { AccountRequest, AccountResponse, AccountUpdateRequest, SyncLogResponse } from '@api/types'

export const accountsKey = ['accounts'] as const
export const accountsListKey = ['accounts', 'list'] as const
export const accountKey = (accountId: string) => ['accounts', 'detail', accountId] as const
export const accountSyncLogsKey = (accountId: string) =>
  ['accounts', 'sync-logs', accountId] as const

/**
 * Pattern 2: a mutation invalidates by FAMILY PREFIX in `onSuccess`, and never
 * hand-patches the cache. Anything that changes an account can also change the
 * ledger rows hanging off it (a sync inserts transactions, a delete cascades
 * them away), and the dashboard figures are derived from those rows — so the
 * accounts, transactions, dashboard-today, budgets and notifications prefixes
 * all go (envelope spent/health and alerts are derived from the ledger rows),
 * and nothing else does.
 */
export function invalidateAccountsAndLedger(qc: QueryClient): void {
  void qc.invalidateQueries({ queryKey: accountsKey })
  invalidateLedger(qc)
}

export function useAccounts() {
  return useQuery<AccountResponse[], ApiError>({
    queryKey: accountsListKey,
    queryFn: listAccounts,
    staleTime: 60 * 1000,
  })
}

/**
 * `GET /v1/accounts/{id}`. Read where one account's CURRENT state matters more
 * than the list's copy of it — a household mate may have synced or renamed the
 * row since this page last fetched the list, and a sync-status line that is
 * quietly minutes stale is worse than none.
 */
export function useAccount(accountId: string | null) {
  return useQuery<AccountResponse, ApiError>({
    queryKey: accountKey(accountId ?? ''),
    queryFn: () => getAccount(accountId as string),
    enabled: accountId !== null,
  })
}

/** `GET /v1/accounts/{id}/sync-logs`. Enabled only while the history is being looked at. */
export function useAccountSyncLogs(accountId: string | null) {
  return useQuery<SyncLogResponse[], ApiError>({
    queryKey: accountSyncLogsKey(accountId ?? ''),
    queryFn: () => listAccountSyncLogs(accountId as string),
    enabled: accountId !== null,
    staleTime: 30 * 1000,
  })
}

export function useCreateAccountMutation() {
  const qc = useQueryClient()
  return useMutation<AccountResponse, ApiError, AccountRequest>({
    mutationFn: createAccount,
    onSuccess: () => invalidateAccountsAndLedger(qc),
  })
}

export function useUpdateAccountMutation() {
  const qc = useQueryClient()
  return useMutation<AccountResponse, ApiError, { id: string; body: AccountUpdateRequest }>({
    mutationFn: ({ id, body }) => updateAccount(id, body),
    onSuccess: () => invalidateAccountsAndLedger(qc),
  })
}

export function useDeleteAccountMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: deleteAccount,
    onSuccess: () => invalidateAccountsAndLedger(qc),
  })
}

/**
 * `POST /v1/accounts/{id}/sync`. The in-flight state the operation describes is
 * the server's own claim on the account (`sync_status = SYNCING`), so the button
 * is disabled both while this mutation is pending AND while the account row
 * itself says `SYNCING` — two triggers that raced would otherwise both pass a
 * local check and one would come back 409.
 */
export function useSyncAccountMutation() {
  const qc = useQueryClient()
  return useMutation<SyncLogResponse, ApiError, string>({
    mutationFn: syncAccount,
    onSuccess: () => invalidateAccountsAndLedger(qc),
  })
}
