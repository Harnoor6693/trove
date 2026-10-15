import { useQuery } from '@tanstack/react-query'
import { listTransactions } from '@api/transactions'
import type { ApiError } from '@api/client'
import type { TransactionResponse } from '@api/types'

export const recentTransactionsKey = (limit: number) =>
  ['transactions', 'recent', limit] as const

/**
 * The newest few rows for the Today card.
 *
 * `listTransactions` returns a page now (rows + the server's `meta`); this hook
 * wants only the rows, and says so by taking `.rows` rather than by having the
 * API layer drop `meta` for everybody.
 */
export function useRecentTransactions(limit: number) {
  return useQuery<TransactionResponse[], ApiError>({
    queryKey: recentTransactionsKey(limit),
    queryFn: async () => (await listTransactions({ limit, sort: 'date,desc' })).rows,
    staleTime: 60 * 1000,
  })
}
