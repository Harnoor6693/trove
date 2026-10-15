/**
 * Budget periods — the list, one period, and every period mutation (§4.6,
 * step 202).
 *
 * Query-key shape (pattern 1, copied from `useAccounts.ts`): everything here
 * lives under the `['budgets']` family prefix that `useCurrentBudget`'s key
 * also starts with, so a period or envelope mutation invalidates the current-
 * period view, the period list and every envelope grid at once — creating a
 * period can turn the current-period 404 into a 200, and deleting one cascades
 * its envelopes away server-side, so nothing under the prefix stays trustworthy
 * on its own.
 */
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import {
  createBudgetPeriod,
  deleteBudgetPeriod,
  getBudgetPeriod,
  listBudgetPeriods,
  updateBudgetPeriod,
} from '@api/budgets'
import type { ApiError } from '@api/client'
import type { BudgetPeriodRequest, BudgetPeriodResponse, BudgetPeriodUpdateRequest } from '@api/types'

export const budgetsKey = ['budgets'] as const
export const budgetPeriodsKey = ['budgets', 'periods'] as const
export const budgetPeriodKey = (id: string) => ['budgets', 'periods', id] as const

export function invalidateBudgets(qc: QueryClient): void {
  void qc.invalidateQueries({ queryKey: budgetsKey })
}

/** `GET /v1/budgets` — every period the household has, newest month first. Not paged. */
export function useBudgetPeriods() {
  return useQuery<BudgetPeriodResponse[], ApiError>({
    queryKey: budgetPeriodsKey,
    queryFn: listBudgetPeriods,
    staleTime: 60 * 1000,
  })
}

/**
 * `GET /v1/budgets/{id}`. Not called by any screen in this family — the
 * Budgets overview derives its selected period from the already-loaded
 * `useBudgetPeriods`/`useCurrentBudget` lists instead of a per-id fetch, so
 * this hook has no test of its own. It is exported as a seam for a later
 * screen (e.g. a period deep-link) that needs to fetch one period directly;
 * the underlying `getBudgetPeriod` API function it wraps IS exercised
 * directly in `api/budgets.test.ts`.
 */
export function useBudgetPeriod(id: string | null) {
  return useQuery<BudgetPeriodResponse, ApiError>({
    queryKey: budgetPeriodKey(id ?? ''),
    queryFn: () => getBudgetPeriod(id as string),
    enabled: id !== null,
  })
}

export function useCreateBudgetPeriodMutation() {
  const qc = useQueryClient()
  return useMutation<BudgetPeriodResponse, ApiError, BudgetPeriodRequest>({
    mutationFn: createBudgetPeriod,
    onSuccess: () => invalidateBudgets(qc),
  })
}

export function useUpdateBudgetPeriodMutation() {
  const qc = useQueryClient()
  return useMutation<BudgetPeriodResponse, ApiError, { id: string; body: BudgetPeriodUpdateRequest }>({
    mutationFn: ({ id, body }) => updateBudgetPeriod(id, body),
    onSuccess: () => invalidateBudgets(qc),
  })
}

export function useDeleteBudgetPeriodMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: deleteBudgetPeriod,
    onSuccess: () => invalidateBudgets(qc),
  })
}
