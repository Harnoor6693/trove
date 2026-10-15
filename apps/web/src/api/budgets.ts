/**
 * `/v1/budgets` and `/v1/budgets/{id}/envelopes` — eleven operations, all of
 * them, and nothing else (§4.6, step 202).
 *
 * `GET /v1/budgets/current` 404s when the household has no budget for the
 * current month; that is a normal empty state, not a failure, so it is mapped
 * to `null` here rather than thrown. `BudgetPeriodResponse` carries no
 * `envelopes` field — envelopes are a separate, unpaged list.
 */
import { api, unwrap, unwrapVoid } from './client'
import { ApiError } from './client'
import type {
  ApiResponse,
  BudgetPeriodRequest,
  BudgetPeriodResponse,
  BudgetPeriodUpdateRequest,
  EnvelopeRequest,
  EnvelopeResponse,
  EnvelopeUpdateRequest,
} from './types'

/* ----------------------------------------------------------- budget periods */

/** `GET /v1/budgets/current`. A 404 (no period for the caller's current month) is a normal empty state, not a failure. */
export async function getCurrentBudget(): Promise<BudgetPeriodResponse | null> {
  try {
    const res = await api.get<ApiResponse<BudgetPeriodResponse>>('/v1/budgets/current')
    return unwrap(res)
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null
    throw e
  }
}

/** `GET /v1/budgets` — every period the household has, `month` descending. Not paged. */
export async function listBudgetPeriods(): Promise<BudgetPeriodResponse[]> {
  const res = await api.get<ApiResponse<BudgetPeriodResponse[]>>('/v1/budgets')
  return unwrap(res)
}

/** `GET /v1/budgets/{id}`. 404 for an id outside the caller's household, never 403. */
export async function getBudgetPeriod(id: string): Promise<BudgetPeriodResponse> {
  const res = await api.get<ApiResponse<BudgetPeriodResponse>>(
    `/v1/budgets/${encodeURIComponent(id)}`
  )
  return unwrap(res)
}

/**
 * `POST /v1/budgets` — 201. One period per household per month: a second
 * period for a month the household already has is 409 `conflict`.
 */
export async function createBudgetPeriod(
  body: BudgetPeriodRequest
): Promise<BudgetPeriodResponse> {
  const res = await api.post<ApiResponse<BudgetPeriodResponse>>('/v1/budgets', body)
  return unwrap(res)
}

/**
 * `PUT /v1/budgets/{id}` — a full replace of `totalIncome`/`budgetType`.
 * On a `ZERO_BASED` period a `totalIncome` that would break the envelope-sum
 * invariant is 400 `validation-error` naming the field.
 */
export async function updateBudgetPeriod(
  id: string,
  body: BudgetPeriodUpdateRequest
): Promise<BudgetPeriodResponse> {
  const res = await api.put<ApiResponse<BudgetPeriodResponse>>(
    `/v1/budgets/${encodeURIComponent(id)}`,
    body
  )
  return unwrap(res)
}

/** `DELETE /v1/budgets/{id}` — removes the period AND its envelopes (`ON DELETE CASCADE`). `EmptySuccess`. */
export async function deleteBudgetPeriod(id: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(`/v1/budgets/${encodeURIComponent(id)}`)
  unwrapVoid(res)
}

/* --------------------------------------------------------------- envelopes */

/**
 * `GET /v1/budgets/{id}/envelopes` — every envelope of one period, with its
 * live health. `spent` is the ledger's own rewritten figure. Not paged.
 */
export async function listEnvelopes(periodId: string): Promise<EnvelopeResponse[]> {
  const res = await api.get<ApiResponse<EnvelopeResponse[]>>(
    `/v1/budgets/${encodeURIComponent(periodId)}/envelopes`
  )
  return unwrap(res)
}

/** `GET /v1/budgets/{id}/envelopes/{envelopeId}`. Health is computed fresh on every read. */
export async function getEnvelope(periodId: string, envelopeId: string): Promise<EnvelopeResponse> {
  const res = await api.get<ApiResponse<EnvelopeResponse>>(
    `/v1/budgets/${encodeURIComponent(periodId)}/envelopes/${encodeURIComponent(envelopeId)}`
  )
  return unwrap(res)
}

/**
 * `POST /v1/budgets/{id}/envelopes` — 201. One envelope per category per
 * period: a second one for a category the period already budgets is 409
 * `conflict`. On a `ZERO_BASED` period, a value that would break the
 * allocation-sum invariant is 400 `validation-error` naming `allocated`.
 */
export async function createEnvelope(
  periodId: string,
  body: EnvelopeRequest
): Promise<EnvelopeResponse> {
  const res = await api.post<ApiResponse<EnvelopeResponse>>(
    `/v1/budgets/${encodeURIComponent(periodId)}/envelopes`,
    body
  )
  return unwrap(res)
}

/**
 * `PUT /v1/budgets/{id}/envelopes/{envelopeId}` — `allocated` is the only
 * writable field. `categoryId`/`currency`/`spent`/`rolloverAmount` are fixed
 * or computed and never sent here.
 */
export async function updateEnvelope(
  periodId: string,
  envelopeId: string,
  body: EnvelopeUpdateRequest
): Promise<EnvelopeResponse> {
  const res = await api.put<ApiResponse<EnvelopeResponse>>(
    `/v1/budgets/${encodeURIComponent(periodId)}/envelopes/${encodeURIComponent(envelopeId)}`,
    body
  )
  return unwrap(res)
}

/** `DELETE /v1/budgets/{id}/envelopes/{envelopeId}`. The transactions it aggregated are untouched. `EmptySuccess`. */
export async function deleteEnvelope(periodId: string, envelopeId: string): Promise<void> {
  const res = await api.delete<ApiResponse<never>>(
    `/v1/budgets/${encodeURIComponent(periodId)}/envelopes/${encodeURIComponent(envelopeId)}`
  )
  unwrapVoid(res)
}
