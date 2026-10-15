/**
 * `/v1/categories` and `/v1/categories/rules` — ten operations, all of them,
 * and nothing else (§4.5, step 202).
 *
 * Every shape here comes from `./types`, which derives it from the generated
 * schema. `deleteCategory`, `deleteCategoryRule` and `reorderCategoryRules`
 * answer the closed `EmptySuccess` body (`{"success":true}`, no `data` key),
 * so they go through `unwrapVoid`.
 */
import { api, unwrap, unwrapVoid } from './client'
import type {
  ApiResponse,
  CategorisationResultResponse,
  CategoryRequest,
  CategoryResponse,
  CategoryRuleRequest,
  CategoryRuleResponse,
  DryRunRequest,
  ReorderRulesRequest,
} from './types'

/* --------------------------------------------------------------- categories */

/** `GET /v1/categories` — every system category plus this household's own, by name. Not paged. */
export async function listCategories(): Promise<CategoryResponse[]> {
  const res = await api.get<ApiResponse<CategoryResponse[]>>('/v1/categories')
  return unwrap(res)
}

/** `POST /v1/categories` — 201. `slug` is derived server-side and never accepted here. */
export async function createCategory(body: CategoryRequest): Promise<CategoryResponse> {
  const res = await api.post<ApiResponse<CategoryResponse>>('/v1/categories', body)
  return unwrap(res)
}

/**
 * `PUT /v1/categories/{id}` — rename or re-parent. A system category answers
 * 403 `forbidden`; the caller must not offer this on one.
 */
export async function updateCategory(id: string, body: CategoryRequest): Promise<CategoryResponse> {
  const res = await api.put<ApiResponse<CategoryResponse>>(
    `/v1/categories/${encodeURIComponent(id)}`,
    body
  )
  return unwrap(res)
}

/**
 * `DELETE /v1/categories/{id}`. 403 `forbidden` on a system category; 409
 * `conflict` when the category is still referenced by one of this household's
 * own merchant rules — the caller reads `error.detail` for what it is used by.
 */
export async function deleteCategory(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(`/v1/categories/${encodeURIComponent(id)}`)
  unwrapVoid(res)
}

/* ----------------------------------------------------------- merchant rules */

/**
 * `GET /v1/categories/rules` — this household's own `RULE`-sourced rows, in
 * precedence order (`priority` ascending, then creation order). Not paged.
 */
export async function listCategoryRules(): Promise<CategoryRuleResponse[]> {
  const res = await api.get<ApiResponse<CategoryRuleResponse[]>>('/v1/categories/rules')
  return unwrap(res)
}

/**
 * `POST /v1/categories/rules` — 201. A second rule for a merchant that already
 * has one is 409 `conflict`; update the existing rule instead.
 */
export async function createCategoryRule(body: CategoryRuleRequest): Promise<CategoryRuleResponse> {
  const res = await api.post<ApiResponse<CategoryRuleResponse>>('/v1/categories/rules', body)
  return unwrap(res)
}

/** `PUT /v1/categories/rules/{id}`. 404 if not this household's row; 403 if it is not `RULE`-sourced. */
export async function updateCategoryRule(
  id: string,
  body: CategoryRuleRequest
): Promise<CategoryRuleResponse> {
  const res = await api.put<ApiResponse<CategoryRuleResponse>>(
    `/v1/categories/rules/${encodeURIComponent(id)}`,
    body
  )
  return unwrap(res)
}

/** `DELETE /v1/categories/rules/{id}`. 404 if not this household's row; 403 if it is not `RULE`-sourced. */
export async function deleteCategoryRule(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(`/v1/categories/rules/${encodeURIComponent(id)}`)
  unwrapVoid(res)
}

/**
 * `POST /v1/categories/rules/reorder`. Sets each id's `priority` to its index
 * in `orderedIds` (`0` first) — every id must already be one of this
 * household's own `RULE`-sourced rows, or nothing is written at all. `EmptySuccess`.
 */
export async function reorderCategoryRules(body: ReorderRulesRequest): Promise<void> {
  const res = await api.post<ApiResponse<never>>('/v1/categories/rules/reorder', body)
  unwrapVoid(res)
}

/**
 * `POST /v1/categories/rules/dry-run` — "test this rule against merchant X".
 * Runs the real pipeline (this household's rules, then the community tier,
 * then `Uncategorised`) and writes nothing. Safe to call before a rule is saved.
 */
export async function dryRunCategoryRule(
  body: DryRunRequest
): Promise<CategorisationResultResponse> {
  const res = await api.post<ApiResponse<CategorisationResultResponse>>(
    '/v1/categories/rules/dry-run',
    body
  )
  return unwrap(res)
}
