/**
 * Merchant rules — the priority-ordered list, every mutation, the reorder
 * affordance and the dry-run preview (§4.5, step 202).
 *
 * `dryRunCategoryRule` is deliberately NOT a `useQuery`: it is a preview a
 * member asks for on demand (before a rule is even saved), never something to
 * cache or refetch in the background, so it is exposed as a mutation whose
 * "variables" are the merchant string being tested.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createCategoryRule,
  deleteCategoryRule,
  dryRunCategoryRule,
  listCategoryRules,
  reorderCategoryRules,
  updateCategoryRule,
} from '@api/categories'
import type { ApiError } from '@api/client'
import type {
  CategorisationResultResponse,
  CategoryRuleRequest,
  CategoryRuleResponse,
  DryRunRequest,
  ReorderRulesRequest,
} from '@api/types'

export const categoryRulesKey = ['category-rules'] as const

export function useCategoryRules() {
  return useQuery<CategoryRuleResponse[], ApiError>({
    queryKey: categoryRulesKey,
    queryFn: listCategoryRules,
    staleTime: 60 * 1000,
  })
}

function invalidateRules(qc: ReturnType<typeof useQueryClient>) {
  void qc.invalidateQueries({ queryKey: categoryRulesKey })
}

export function useCreateCategoryRuleMutation() {
  const qc = useQueryClient()
  return useMutation<CategoryRuleResponse, ApiError, CategoryRuleRequest>({
    mutationFn: createCategoryRule,
    onSuccess: () => invalidateRules(qc),
  })
}

export function useUpdateCategoryRuleMutation() {
  const qc = useQueryClient()
  return useMutation<CategoryRuleResponse, ApiError, { id: string; body: CategoryRuleRequest }>({
    mutationFn: ({ id, body }) => updateCategoryRule(id, body),
    onSuccess: () => invalidateRules(qc),
  })
}

export function useDeleteCategoryRuleMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: deleteCategoryRule,
    onSuccess: () => invalidateRules(qc),
  })
}

/**
 * `POST /v1/categories/rules/reorder`. The caller passes the full ordered id
 * list (index 0 first) — the whole point of this operation, per its
 * description, is that there is no partial reorder.
 */
export function useReorderCategoryRulesMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, ReorderRulesRequest>({
    mutationFn: reorderCategoryRules,
    onSuccess: () => invalidateRules(qc),
  })
}

/** `POST /v1/categories/rules/dry-run`. Writes nothing; nothing here is cached or invalidated. */
export function useDryRunCategoryRuleMutation() {
  return useMutation<CategorisationResultResponse, ApiError, DryRunRequest>({
    mutationFn: dryRunCategoryRule,
  })
}
