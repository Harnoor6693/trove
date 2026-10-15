/**
 * `GET /v1/dashboard/today`.
 *
 * Day and month boundaries are computed server-side from the caller's own
 * `users.timezone` (gap P2-15), never from the browser clock and never from the
 * container's UTC clock. The response says which date, in which zone, the
 * figures describe — the client renders that, it never re-derives it.
 *
 * `byCurrency` carries one subtotal per currency actually present in the
 * household's ledger. Nothing here or downstream adds two of them together:
 * there is no exchange rate anywhere in this product (owner decision 2).
 */
import { api, unwrap } from './client'
import type { ApiResponse, DashboardTodayResponse } from './types'

export async function getToday(): Promise<DashboardTodayResponse> {
  const res = await api.get<ApiResponse<DashboardTodayResponse>>('/v1/dashboard/today')
  return unwrap(res)
}
