/**
 * `GET /v1/budgets/current` — the household's period for the caller's own
 * current month. A 404 (no period yet) is mapped to `null` by `@api/budgets`;
 * that is the normal empty state this hook's consumers render, not an error.
 *
 * Shares the `['budgets']` prefix with `useBudgetPeriods.ts`'s keys — see that
 * file for why every mutation under the family invalidates the whole prefix.
 */
import { useQuery } from '@tanstack/react-query'
import { getCurrentBudget } from '@api/budgets'
import type { ApiError } from '@api/client'
import type { BudgetPeriodResponse } from '@api/types'

export const currentBudgetKey = ['budgets', 'current'] as const

export function useCurrentBudget() {
  return useQuery<BudgetPeriodResponse | null, ApiError>({
    queryKey: currentBudgetKey,
    queryFn: getCurrentBudget,
    staleTime: 5 * 60 * 1000,
  })
}
