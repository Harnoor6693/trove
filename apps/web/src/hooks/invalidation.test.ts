import { QueryClient } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import { invalidateAccountsAndLedger } from './useAccounts'
import { invalidateLedger } from './useTransactions'

function seeded(keys: unknown[][]) {
  const qc = new QueryClient()
  for (const k of keys) qc.setQueryData(k, 1)
  return qc
}
const stale = (qc: QueryClient, k: unknown[]) => qc.getQueryState(k)?.isInvalidated

describe('cross-view invalidation', () => {
  it('a ledger change refreshes budgets and notifications (B4-03)', () => {
    for (const run of [invalidateLedger, invalidateAccountsAndLedger]) {
      const qc = seeded([['budgets', 'current'], ['budgets', 'envelopes', 'p1'], ['notifications', 'unread-count']])
      run(qc)
      expect(stale(qc, ['budgets', 'current'])).toBe(true)
      expect(stale(qc, ['budgets', 'envelopes', 'p1'])).toBe(true)
      expect(stale(qc, ['notifications', 'unread-count'])).toBe(true)
    }
  })
})
