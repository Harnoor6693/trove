/**
 * React Query bindings for `/v1/goals*`. Same shape as `useAccounts.ts`: one
 * root, a mutation invalidates it.
 */

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { createGoal, deleteGoal, fetchGoals, updateGoal } from '@api/goals';
import type { GoalRequest, GoalResponse, GoalUpdateRequest } from '@api/types';
import { goalKeys } from './queryKeys';
import { retryUnlessRefused } from './retryPolicy';

export { goalKeys } from './queryKeys';

/** `GET /v1/goals`. Every savings goal of the household, newest first. Not paged. */
export function useGoals(): UseQueryResult<GoalResponse[]> {
  return useQuery({
    queryKey: goalKeys.list(),
    queryFn: fetchGoals,
    staleTime: 60_000,
    retry: retryUnlessRefused,
  });
}

/** `POST /v1/goals`. `currentAmount`/`achieved` are never accepted from the caller. */
export function useCreateGoal(): UseMutationResult<GoalResponse, unknown, GoalRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: GoalRequest) => createGoal(body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: goalKeys.list() });
    },
  });
}

/** `PUT /v1/goals/{id}`. Full replace; `achieved` is derived server-side. */
export function useUpdateGoal(
  id: string
): UseMutationResult<GoalResponse, unknown, GoalUpdateRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: GoalUpdateRequest) => updateGoal(id, body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: goalKeys.list() });
    },
  });
}

/** `DELETE /v1/goals/{id}`. */
export function useDeleteGoal(): UseMutationResult<void, unknown, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteGoal(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: goalKeys.list() });
    },
  });
}
