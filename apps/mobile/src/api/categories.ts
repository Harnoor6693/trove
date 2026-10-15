/**
 * Categories and merchant rules — the ten `/v1/categories*` operations of
 * `openapi/trove-core.yaml`.
 *
 * Every call goes through the one `api` instance (bearer header,
 * `X-Requested-With`, single-flight refresh — see `client.ts`). Two shapes to
 * keep straight:
 *
 *  - `listCategories` and `listCategoryRules` answer a **bare array** in
 *    `data`; neither is paged — `unwrap` is right, `unwrapPage` would be
 *    wrong.
 *  - `deleteCategory`, `deleteCategoryRule` and `reorderCategoryRules` answer
 *    the closed `EmptySuccess` shape — `expectSuccess`, never `unwrap`.
 */

import { api } from './client';
import { expectSuccess, unwrap } from './errors';
import { pathSegment } from './paths';
import type {
  ApiResponse,
  CategorisationResultResponse,
  CategoryRequest,
  CategoryResponse,
  CategoryRuleRequest,
  CategoryRuleResponse,
  DryRunRequest,
  ReorderRulesRequest,
} from './types';

/** `GET /v1/categories` — every system category plus this household's own, by name. Not paged. */
export async function fetchCategories(): Promise<CategoryResponse[]> {
  const response = await api.get<ApiResponse<CategoryResponse[]>>('/v1/categories');
  return unwrap(response.data);
}

/** `POST /v1/categories` — 201. `slug` is derived server-side and never accepted here. */
export async function createCategory(body: CategoryRequest): Promise<CategoryResponse> {
  const response = await api.post<ApiResponse<CategoryResponse>>('/v1/categories', body);
  return unwrap(response.data);
}

/**
 * `PUT /v1/categories/{id}` — rename or re-parent a household category. A
 * system category is 403 `forbidden`.
 */
export async function updateCategory(id: string, body: CategoryRequest): Promise<CategoryResponse> {
  const response = await api.put<ApiResponse<CategoryResponse>>(
    `/v1/categories/${pathSegment(id)}`,
    body
  );
  return unwrap(response.data);
}

/**
 * `DELETE /v1/categories/{id}` — a system category is 403 `forbidden`; a
 * household category still referenced by one of this household's own
 * merchant rules is 409 `conflict`.
 */
export async function deleteCategory(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/categories/${pathSegment(id)}`);
  expectSuccess(response.data);
}

/**
 * `GET /v1/categories/rules` — this household's own `RULE`-sourced rows, in
 * precedence order (`priority` ascending, then creation order). Not paged.
 */
export async function fetchCategoryRules(): Promise<CategoryRuleResponse[]> {
  const response = await api.get<ApiResponse<CategoryRuleResponse[]>>('/v1/categories/rules');
  return unwrap(response.data);
}

/**
 * `POST /v1/categories/rules` — 201. A second rule for a merchant that
 * already has one is 409 `conflict` — update the existing rule instead.
 */
export async function createCategoryRule(body: CategoryRuleRequest): Promise<CategoryRuleResponse> {
  const response = await api.post<ApiResponse<CategoryRuleResponse>>('/v1/categories/rules', body);
  return unwrap(response.data);
}

/**
 * `PUT /v1/categories/rules/{id}` — changing `merchant` to one that collides
 * with another of this household's rules is 409 `conflict`.
 */
export async function updateCategoryRule(
  id: string,
  body: CategoryRuleRequest
): Promise<CategoryRuleResponse> {
  const response = await api.put<ApiResponse<CategoryRuleResponse>>(
    `/v1/categories/rules/${pathSegment(id)}`,
    body
  );
  return unwrap(response.data);
}

/** `DELETE /v1/categories/rules/{id}`. `EmptySuccess`. */
export async function deleteCategoryRule(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/categories/rules/${pathSegment(id)}`);
  expectSuccess(response.data);
}

/**
 * `POST /v1/categories/rules/reorder` — sets each rule's `priority` to its
 * index in `orderedIds` (0 first). Every id must already resolve to one of
 * this household's own `RULE`-sourced rows or the WHOLE request is refused —
 * there is no partial reorder. `EmptySuccess`.
 */
export async function reorderCategoryRules(body: ReorderRulesRequest): Promise<void> {
  const response = await api.post<ApiResponse<never>>('/v1/categories/rules/reorder', body);
  expectSuccess(response.data);
}

/**
 * `POST /v1/categories/rules/dry-run` — "test this rule against merchant X".
 * Runs the real pipeline (household rules, then the built-in community
 * defaults, then `Uncategorised`) and writes nothing.
 */
export async function dryRunCategoryRule(
  body: DryRunRequest
): Promise<CategorisationResultResponse> {
  const response = await api.post<ApiResponse<CategorisationResultResponse>>(
    '/v1/categories/rules/dry-run',
    body
  );
  return unwrap(response.data);
}
