/**
 * The dashboard figures — `GET /v1/dashboard/today`.
 *
 * The whole point of this operation is that the **server** decided which day
 * it is. "Today" and "this month" are computed from `users.timezone`, so a
 * Vancouver member's evening purchase lands on today's figure rather than
 * tomorrow's. `date` and `timezone` on the response say which day, in which
 * zone, the numbers describe — a client that re-derives a boundary from the
 * device clock is answering a different question from the one the totals
 * answer.
 *
 * `byCurrency` carries one subtotal per currency actually present in the
 * ledger. There is no exchange rate anywhere in this product, so the entries
 * are rendered separately and are never added together; the headline fields
 * are the caller's own currency's subtotal repeated, not a blend.
 */

import { api } from './client';
import { unwrap } from './errors';
import type { ApiResponse, DashboardTodayResponse } from './types';

/** `GET /v1/dashboard/today` — household figures for the caller's own today. */
export async function fetchDashboardToday(): Promise<DashboardTodayResponse> {
  const response = await api.get<ApiResponse<DashboardTodayResponse>>('/v1/dashboard/today');
  return unwrap(response.data);
}
