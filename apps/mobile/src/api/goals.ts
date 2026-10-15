/**
 * Savings goals — the four `/v1/goals*` operations of
 * `openapi/trove-core.yaml`.
 *
 * `listGoals` answers a **bare array**; it is not paged (a household
 * accumulates goals slowly — inventory BUD-01 records the future risk).
 * `deleteGoal` answers `EmptySuccess`. `achieved` is server-derived on every
 * write from `currentAmount >= targetAmount` and is never sent by this
 * module.
 */

import { api } from './client';
import { expectSuccess, unwrap } from './errors';
import { pathSegment } from './paths';
import type { ApiResponse, GoalRequest, GoalResponse, GoalUpdateRequest } from './types';

/** `GET /v1/goals` — every savings goal of the household, newest first. Not paged. */
export async function fetchGoals(): Promise<GoalResponse[]> {
  const response = await api.get<ApiResponse<GoalResponse[]>>('/v1/goals');
  return unwrap(response.data);
}

/**
 * `POST /v1/goals` — 201. `currentAmount` starts at zero and `achieved` at
 * `false` whatever the caller sends.
 */
export async function createGoal(body: GoalRequest): Promise<GoalResponse> {
  const response = await api.post<ApiResponse<GoalResponse>>('/v1/goals', body);
  return unwrap(response.data);
}

/**
 * `PUT /v1/goals/{id}` — full replace of `name`/`targetAmount`/
 * `currentAmount`/`deadline`. `currency` is not writable; `achieved` is
 * derived server-side, never sent.
 */
export async function updateGoal(id: string, body: GoalUpdateRequest): Promise<GoalResponse> {
  const response = await api.put<ApiResponse<GoalResponse>>(`/v1/goals/${pathSegment(id)}`, body);
  return unwrap(response.data);
}

/** `DELETE /v1/goals/{id}`. `EmptySuccess`. */
export async function deleteGoal(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/goals/${pathSegment(id)}`);
  expectSuccess(response.data);
}
