/**
 * Budget periods and envelopes — the eleven `/v1/budgets*` operations of
 * `openapi/trove-core.yaml`.
 *
 * Every call goes through the one `api` instance. Shapes to keep straight:
 *
 *  - `listBudgetPeriods` and `listEnvelopes` answer a **bare array** in
 *    `data`; neither is paged (the spec says so explicitly on both — a
 *    household accumulates twelve period rows a year).
 *  - `deleteBudgetPeriod` and `deleteEnvelope` answer `EmptySuccess`.
 *  - `getCurrentBudgetPeriod`'s 404 is the EMPTY STATE ("no budget this
 *    month"), not a failure — callers must read it that way, not through
 *    `ScreenState`'s generic error branch.
 *  - `spent`, `rolloverAmount` and `health` on `EnvelopeResponse` are the
 *    server's own figures. Nothing here recomputes them.
 */

import { api } from './client';
import { expectSuccess, unwrap } from './errors';
import { pathSegment } from './paths';
import type {
  ApiResponse,
  BudgetPeriodRequest,
  BudgetPeriodResponse,
  BudgetPeriodUpdateRequest,
  EnvelopeRequest,
  EnvelopeResponse,
  EnvelopeUpdateRequest,
} from './types';

/** `GET /v1/budgets` — every period the household has, month descending. Not paged. */
export async function fetchBudgetPeriods(): Promise<BudgetPeriodResponse[]> {
  const response = await api.get<ApiResponse<BudgetPeriodResponse[]>>('/v1/budgets');
  return unwrap(response.data);
}

/**
 * `POST /v1/budgets` — 201. One period per household per month: a second for
 * a month the household already has is 409 `conflict`.
 */
export async function createBudgetPeriod(body: BudgetPeriodRequest): Promise<BudgetPeriodResponse> {
  const response = await api.post<ApiResponse<BudgetPeriodResponse>>('/v1/budgets', body);
  return unwrap(response.data);
}

/**
 * `GET /v1/budgets/current` — the period for the CALLER's own current month
 * (their own timezone). **A 404 here means "no budget for this month" — the
 * caller's empty state, not an error to surface generically.**
 */
export async function fetchCurrentBudgetPeriod(): Promise<BudgetPeriodResponse> {
  const response = await api.get<ApiResponse<BudgetPeriodResponse>>('/v1/budgets/current');
  return unwrap(response.data);
}

/** `GET /v1/budgets/{id}`. */
export async function fetchBudgetPeriod(id: string): Promise<BudgetPeriodResponse> {
  const response = await api.get<ApiResponse<BudgetPeriodResponse>>(
    `/v1/budgets/${pathSegment(id)}`
  );
  return unwrap(response.data);
}

/**
 * `PUT /v1/budgets/{id}` — full replace of `totalIncome`/`budgetType`.
 * `month`/`currency` are not writable. On a `ZERO_BASED` period, a
 * `totalIncome` that would break the allocation-sum invariant is 400
 * `validation-error` naming the field.
 */
export async function updateBudgetPeriod(
  id: string,
  body: BudgetPeriodUpdateRequest
): Promise<BudgetPeriodResponse> {
  const response = await api.put<ApiResponse<BudgetPeriodResponse>>(
    `/v1/budgets/${pathSegment(id)}`,
    body
  );
  return unwrap(response.data);
}

/** `DELETE /v1/budgets/{id}` — cascades to its envelopes (`ON DELETE CASCADE`). `EmptySuccess`. */
export async function deleteBudgetPeriod(id: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(`/v1/budgets/${pathSegment(id)}`);
  expectSuccess(response.data);
}

/**
 * `GET /v1/budgets/{id}/envelopes` — every envelope of one period, with live
 * health. Not paged.
 */
export async function fetchEnvelopes(periodId: string): Promise<EnvelopeResponse[]> {
  const response = await api.get<ApiResponse<EnvelopeResponse[]>>(
    `/v1/budgets/${pathSegment(periodId)}/envelopes`
  );
  return unwrap(response.data);
}

/**
 * `POST /v1/budgets/{id}/envelopes` — 201. One envelope per category per
 * period: a second for a category the period already budgets is 409
 * `conflict`. On a `ZERO_BASED` period, an allocation that would break the
 * sum invariant is 400 `validation-error` naming `allocated`.
 */
export async function createEnvelope(
  periodId: string,
  body: EnvelopeRequest
): Promise<EnvelopeResponse> {
  const response = await api.post<ApiResponse<EnvelopeResponse>>(
    `/v1/budgets/${pathSegment(periodId)}/envelopes`,
    body
  );
  return unwrap(response.data);
}

/** `GET /v1/budgets/{id}/envelopes/{envelopeId}`. Health recomputed on every read. */
export async function fetchEnvelope(
  periodId: string,
  envelopeId: string
): Promise<EnvelopeResponse> {
  const response = await api.get<ApiResponse<EnvelopeResponse>>(
    `/v1/budgets/${pathSegment(periodId)}/envelopes/${pathSegment(envelopeId)}`
  );
  return unwrap(response.data);
}

/**
 * `PUT /v1/budgets/{id}/envelopes/{envelopeId}` — `allocated` only, and it is
 * required. On a `ZERO_BASED` period a value that breaks the sum invariant is
 * 400 `validation-error`.
 */
export async function updateEnvelope(
  periodId: string,
  envelopeId: string,
  body: EnvelopeUpdateRequest
): Promise<EnvelopeResponse> {
  const response = await api.put<ApiResponse<EnvelopeResponse>>(
    `/v1/budgets/${pathSegment(periodId)}/envelopes/${pathSegment(envelopeId)}`,
    body
  );
  return unwrap(response.data);
}

/**
 * `DELETE /v1/budgets/{id}/envelopes/{envelopeId}` — the transactions it
 * aggregated, and the category itself, are untouched. `EmptySuccess`.
 */
export async function deleteEnvelope(periodId: string, envelopeId: string): Promise<void> {
  const response = await api.delete<ApiResponse<never>>(
    `/v1/budgets/${pathSegment(periodId)}/envelopes/${pathSegment(envelopeId)}`
  );
  expectSuccess(response.data);
}
