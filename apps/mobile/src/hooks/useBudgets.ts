/**
 * React Query bindings for `/v1/budgets*` — budget periods and their
 * envelopes.
 *
 * **`useCurrentBudgetPeriod`'s 404 is the household's empty state, not a
 * transport failure.** `getCurrentBudgetPeriod` 404s when there is no period
 * for the caller's current month (§4.6: "a month with no period is 404, not
 * an empty 200"). `retryUnlessRefused` already refuses to retry a 404, so the
 * query settles into `isError` once, quickly; the screen reads `failure.status
 * === 404` off it (via `toApiFailure`) to choose "create one" over a generic
 * failure message, rather than `ScreenState`'s default error copy.
 *
 * Same invalidation shape as `useAccounts.ts`: a period write invalidates the
 * whole `budgets` root (which reaches `list`, `current` and every `detail` by
 * key-prefix matching); an envelope write invalidates that period's own
 * envelope list (which reaches a single envelope's key the same way).
 */

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  createBudgetPeriod,
  createEnvelope,
  deleteBudgetPeriod,
  deleteEnvelope,
  fetchBudgetPeriod,
  fetchBudgetPeriods,
  fetchCurrentBudgetPeriod,
  fetchEnvelope,
  fetchEnvelopes,
  updateBudgetPeriod,
  updateEnvelope,
} from '@api/budgets';
import type {
  BudgetPeriodRequest,
  BudgetPeriodResponse,
  BudgetPeriodUpdateRequest,
  EnvelopeRequest,
  EnvelopeResponse,
  EnvelopeUpdateRequest,
} from '@api/types';
import { budgetKeys } from './queryKeys';
import { retryUnlessRefused } from './retryPolicy';

export { budgetKeys } from './queryKeys';

/** `GET /v1/budgets`. Every period the household has, month descending. Not paged. */
export function useBudgetPeriods(): UseQueryResult<BudgetPeriodResponse[]> {
  return useQuery({
    queryKey: budgetKeys.list(),
    queryFn: fetchBudgetPeriods,
    staleTime: 60_000,
    retry: retryUnlessRefused,
  });
}

/** `GET /v1/budgets/current`. See the file header for the 404-is-empty-state rule. */
export function useCurrentBudgetPeriod(): UseQueryResult<BudgetPeriodResponse> {
  return useQuery({
    queryKey: budgetKeys.current(),
    queryFn: fetchCurrentBudgetPeriod,
    retry: retryUnlessRefused,
  });
}

/** `GET /v1/budgets/{id}`. Idle until an id is known. */
export function useBudgetPeriod(id: string): UseQueryResult<BudgetPeriodResponse> {
  return useQuery({
    queryKey: budgetKeys.detail(id),
    queryFn: () => fetchBudgetPeriod(id),
    enabled: id !== '',
    retry: retryUnlessRefused,
  });
}

/** `POST /v1/budgets`. One period per household per month — a second is 409. */
export function useCreateBudgetPeriod(): UseMutationResult<
  BudgetPeriodResponse,
  unknown,
  BudgetPeriodRequest
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: BudgetPeriodRequest) => createBudgetPeriod(body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: budgetKeys.all() });
    },
  });
}

/**
 * `PUT /v1/budgets/{id}`. On a `ZERO_BASED` period, a `totalIncome` that
 * breaks the envelope-sum invariant is 400 `validation-error` naming the
 * field — the caller reads it with `fieldError(failure, 'totalIncome')`.
 */
export function useUpdateBudgetPeriod(
  id: string
): UseMutationResult<BudgetPeriodResponse, unknown, BudgetPeriodUpdateRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: BudgetPeriodUpdateRequest) => updateBudgetPeriod(id, body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: budgetKeys.all() });
    },
  });
}

/** `DELETE /v1/budgets/{id}`. Cascades to its envelopes. */
export function useDeleteBudgetPeriod(): UseMutationResult<void, unknown, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteBudgetPeriod(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: budgetKeys.all() });
    },
  });
}

/** `GET /v1/budgets/{id}/envelopes`. Live health on every read. Not paged. */
export function useEnvelopes(periodId: string): UseQueryResult<EnvelopeResponse[]> {
  return useQuery({
    queryKey: budgetKeys.envelopes(periodId),
    queryFn: () => fetchEnvelopes(periodId),
    enabled: periodId !== '',
    retry: retryUnlessRefused,
  });
}

/** `GET /v1/budgets/{id}/envelopes/{envelopeId}`. */
export function useEnvelope(
  periodId: string,
  envelopeId: string
): UseQueryResult<EnvelopeResponse> {
  return useQuery({
    queryKey: budgetKeys.envelope(periodId, envelopeId),
    queryFn: () => fetchEnvelope(periodId, envelopeId),
    enabled: periodId !== '' && envelopeId !== '',
    retry: retryUnlessRefused,
  });
}

/**
 * `POST /v1/budgets/{id}/envelopes`. One envelope per category per period —
 * a second is 409. On a `ZERO_BASED` period, an allocation that breaks the
 * sum invariant is 400 `validation-error` naming `allocated`.
 */
export function useCreateEnvelope(
  periodId: string
): UseMutationResult<EnvelopeResponse, unknown, EnvelopeRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: EnvelopeRequest) => createEnvelope(periodId, body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: budgetKeys.envelopes(periodId) });
    },
  });
}

/** `PUT /v1/budgets/{id}/envelopes/{envelopeId}`. `allocated` only. */
export function useUpdateEnvelope(
  periodId: string,
  envelopeId: string
): UseMutationResult<EnvelopeResponse, unknown, EnvelopeUpdateRequest> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: EnvelopeUpdateRequest) => updateEnvelope(periodId, envelopeId, body),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: budgetKeys.envelopes(periodId) });
    },
  });
}

/** `DELETE /v1/budgets/{id}/envelopes/{envelopeId}`. */
export function useDeleteEnvelope(periodId: string): UseMutationResult<void, unknown, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (envelopeId: string) => deleteEnvelope(periodId, envelopeId),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: budgetKeys.envelopes(periodId) });
    },
  });
}
