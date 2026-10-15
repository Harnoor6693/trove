/**
 * `/v1/goals` — the household's savings goals, four operations, all of them
 * (§4.6, step 202).
 *
 * `currentAmount` and `achieved` are never accepted on a write: a goal always
 * starts at zero, unmet, and `achieved` is derived server-side from
 * `currentAmount >= targetAmount` on every write.
 */
import { api, unwrap, unwrapVoid } from './client'
import type { ApiResponse, GoalRequest, GoalResponse, GoalUpdateRequest } from './types'

/** `GET /v1/goals` — every goal of the caller's household, newest first. Not paged. */
export async function listGoals(): Promise<GoalResponse[]> {
  const res = await api.get<ApiResponse<GoalResponse[]>>('/v1/goals')
  return unwrap(res)
}

/** `POST /v1/goals` — 201. `currency` defaults to the creating member's own; `deadline` is optional. */
export async function createGoal(body: GoalRequest): Promise<GoalResponse> {
  const res = await api.post<ApiResponse<GoalResponse>>('/v1/goals', body)
  return unwrap(res)
}

/**
 * `PUT /v1/goals/{id}` — a full replace. `currency` is not writable; `achieved`
 * is never sent, the server derives it from `currentAmount >= targetAmount`.
 */
export async function updateGoal(id: string, body: GoalUpdateRequest): Promise<GoalResponse> {
  const res = await api.put<ApiResponse<GoalResponse>>(`/v1/goals/${encodeURIComponent(id)}`, body)
  return unwrap(res)
}

/** `DELETE /v1/goals/{id}`. `EmptySuccess`. */
export async function deleteGoal(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(`/v1/goals/${encodeURIComponent(id)}`)
  unwrapVoid(res)
}
