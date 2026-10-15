/**
 * Categories — the list query (used all over the app: Transactions, Today,
 * envelopes) and the household-category mutations (§202).
 */
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { createCategory, deleteCategory, listCategories, updateCategory } from '@api/categories'
import { budgetsKey } from './useBudgetPeriods'
import { categoryRulesKey } from './useCategoryRules'
import { taxExportKey } from './useTaxExport'
import { invalidateLedger } from './useTransactions'
import type { ApiError } from '@api/client'
import type { CategoryRequest, CategoryResponse } from '@api/types'

export const categoriesKey = ['categories'] as const

export function useCategories() {
  return useQuery<CategoryResponse[], ApiError>({
    queryKey: categoriesKey,
    queryFn: listCategories,
    staleTime: Infinity,
  })
}

/** Category names are shown on envelopes, ledger rows, rules and tax lines, so all of them refresh. */
export function invalidateCategories(qc: QueryClient) {
  void qc.invalidateQueries({ queryKey: categoriesKey })
  void qc.invalidateQueries({ queryKey: budgetsKey })
  void qc.invalidateQueries({ queryKey: categoryRulesKey })
  void qc.invalidateQueries({ queryKey: taxExportKey })
  invalidateLedger(qc)
}

export function useCreateCategoryMutation() {
  const qc = useQueryClient()
  return useMutation<CategoryResponse, ApiError, CategoryRequest>({
    mutationFn: createCategory,
    onSuccess: () => invalidateCategories(qc),
  })
}

export function useUpdateCategoryMutation() {
  const qc = useQueryClient()
  return useMutation<CategoryResponse, ApiError, { id: string; body: CategoryRequest }>({
    mutationFn: ({ id, body }) => updateCategory(id, body),
    onSuccess: () => invalidateCategories(qc),
  })
}

export function useDeleteCategoryMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: deleteCategory,
    onSuccess: () => invalidateCategories(qc),
  })
}
