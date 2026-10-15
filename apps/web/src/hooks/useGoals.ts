/** Savings goals — the list and every mutation (§4.6, step 202). */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createGoal, deleteGoal, listGoals, updateGoal } from '@api/goals'
import type { ApiError } from '@api/client'
import type { GoalRequest, GoalResponse, GoalUpdateRequest } from '@api/types'

export const goalsKey = ['goals'] as const

/** `GET /v1/goals` — every goal of the household, newest first. Not paged. */
export function useGoals() {
  return useQuery<GoalResponse[], ApiError>({
    queryKey: goalsKey,
    queryFn: listGoals,
    staleTime: 60 * 1000,
  })
}

function invalidateGoals(qc: ReturnType<typeof useQueryClient>) {
  void qc.invalidateQueries({ queryKey: goalsKey })
}

export function useCreateGoalMutation() {
  const qc = useQueryClient()
  return useMutation<GoalResponse, ApiError, GoalRequest>({
    mutationFn: createGoal,
    onSuccess: () => invalidateGoals(qc),
  })
}

export function useUpdateGoalMutation() {
  const qc = useQueryClient()
  return useMutation<GoalResponse, ApiError, { id: string; body: GoalUpdateRequest }>({
    mutationFn: ({ id, body }) => updateGoal(id, body),
    onSuccess: () => invalidateGoals(qc),
  })
}

export function useDeleteGoalMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string>({
    mutationFn: deleteGoal,
    onSuccess: () => invalidateGoals(qc),
  })
}
