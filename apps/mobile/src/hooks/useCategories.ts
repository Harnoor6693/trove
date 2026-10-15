/**
 * React Query bindings for `/v1/categories*` — the category tree and the
 * household's merchant rules.
 *
 * Same shape as `useAccounts.ts`: one root per resource, a mutation
 * invalidates its own root plus anything it changed as a side effect. A rule
 * always names a category (`categoryId`), but creating or editing a rule
 * never changes a category row, so rule mutations invalidate only
 * `categoryKeys.rules()` — not the whole `categoryKeys.all()`, which would
 * force-refetch the tree for no reason. Deleting a *category* can 409 if a
 * rule still references it, so that path never needs to invalidate rules.
 */

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  createCategory,
  createCategoryRule,
  deleteCategory,
  deleteCategoryRule,
  dryRunCategoryRule,
  fetchCategories,
  fetchCategoryRules,
  reorderCategoryRules,
  updateCategory,
  updateCategoryRule,
} from '@api/categories';
import type {
  CategorisationResultResponse,
  CategoryRequest,
  CategoryResponse,
  CategoryRuleRequest,
  CategoryRuleResponse,
  DryRunRequest,
  ReorderRulesRequest,
} from '@api/types';
import { categoryKeys } from './queryKeys';
import { retryUnlessRefused } from './retryPolicy';

export { categoryKeys } from './queryKeys';

/** `GET /v1/categories`. Every system category plus the household's own. Not paged. */
export function useCategories(): UseQueryResult<CategoryResponse[]> {
  return useQuery({
    queryKey: categoryKeys.list(),
    queryFn: fetchCategories,
    staleTime: 60_000,
    retry: retryUnlessRefused,
  });
}

/** `POST /v1/categories`. */
export function useCreateCategory(): UseMutationResult<CategoryResponse, unknown, CategoryRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: CategoryRequest) => createCategory(body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: categoryKeys.list() });
    },
  });
}

/** `PUT /v1/categories/{id}` — rename or re-parent. A system category is 403. */
export function useUpdateCategory(
  id: string
): UseMutationResult<CategoryResponse, unknown, CategoryRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: CategoryRequest) => updateCategory(id, body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: categoryKeys.list() });
    },
  });
}

/**
 * `DELETE /v1/categories/{id}`. A category still referenced by a household
 * rule is 409 `conflict` — the mutation rejects and nothing is invalidated,
 * which is correct since nothing changed.
 */
export function useDeleteCategory(): UseMutationResult<void, unknown, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteCategory(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: categoryKeys.list() });
    },
  });
}

/** `GET /v1/categories/rules`. Precedence order (`priority` ascending). Not paged. */
export function useCategoryRules(): UseQueryResult<CategoryRuleResponse[]> {
  return useQuery({
    queryKey: categoryKeys.rules(),
    queryFn: fetchCategoryRules,
    staleTime: 30_000,
    retry: retryUnlessRefused,
  });
}

/** `POST /v1/categories/rules`. A second rule for the same merchant is 409. */
export function useCreateCategoryRule(): UseMutationResult<
  CategoryRuleResponse,
  unknown,
  CategoryRuleRequest
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: CategoryRuleRequest) => createCategoryRule(body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: categoryKeys.rules() });
    },
  });
}

/** `PUT /v1/categories/rules/{id}`. */
export function useUpdateCategoryRule(
  id: string
): UseMutationResult<CategoryRuleResponse, unknown, CategoryRuleRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: CategoryRuleRequest) => updateCategoryRule(id, body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: categoryKeys.rules() });
    },
  });
}

/** `DELETE /v1/categories/rules/{id}`. */
export function useDeleteCategoryRule(): UseMutationResult<void, unknown, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteCategoryRule(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: categoryKeys.rules() });
    },
  });
}

/**
 * `POST /v1/categories/rules/reorder`. Refetches the rule list on success so
 * the screen's order comes from the server's own `priority`, rather than
 * trusting the order the client just asked for (the request is refused
 * whole-or-nothing, so a success here always means the server now agrees).
 */
export function useReorderCategoryRules(): UseMutationResult<void, unknown, ReorderRulesRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: ReorderRulesRequest) => reorderCategoryRules(body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: categoryKeys.rules() });
    },
  });
}

/**
 * `POST /v1/categories/rules/dry-run`. Writes nothing, so no invalidation —
 * this is a preview mutation (`useMutation` rather than `useQuery` because it
 * runs on demand, from a button press, not as page data).
 */
export function useDryRunCategoryRule(): UseMutationResult<
  CategorisationResultResponse,
  unknown,
  DryRunRequest
> {
  return useMutation({
    mutationFn: (body: DryRunRequest) => dryRunCategoryRule(body),
  });
}
