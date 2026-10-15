/**
 * The daily-spending sparkline on Today.
 *
 * **One currency, never a blend.** Every row carries its own currency and there
 * is no exchange rate anywhere in this product (owner decision 2), so rows
 * outside `currency` are left out rather than added in. The line sits directly
 * beside the per-currency card, which exists precisely because two currencies
 * are never summed; a blended line next to it would be a figure that cannot
 * exist, produced by arithmetic this client invented.
 *
 * **The window ends on the server's day, not the browser's.** `endDate` is the
 * `date` from `GET /v1/dashboard/today`, which the server computed in the
 * member's own zone. Nothing here derives a boundary from the device clock — a
 * member reading a household kept in another zone would otherwise get a chart a
 * day out of step with the figures printed above it.
 */
import { useQuery } from '@tanstack/react-query'
import { listTransactions } from '@api/transactions'
import type { CurrencyCode } from '@api/types'

export const dailySpendingTrendKey = (days: number, currency: string, endDate: string) =>
  ['transactions', 'trend', days, currency, endDate] as const

/** The operation's maximum `limit`; a value above it is rejected, not clamped. */
const MAX_TREND_ROWS = 500

export interface DailyTotal {
  date: string
  total: number
}

/**
 * @param days how many days the window covers, ending on `endDate`
 * @param currency the ONE currency the line is drawn in — the caller's own
 *   reporting currency, as the dashboard reports it
 * @param endDate the server's own "today" for this member; the query stays idle
 *   until both it and the currency are known
 */
export function useDailySpendingTrend(
  days: number,
  currency: CurrencyCode | undefined,
  endDate: string | undefined
) {
  return useQuery({
    queryKey: dailySpendingTrendKey(days, currency ?? '', endDate ?? ''),
    enabled: currency !== undefined && endDate !== undefined,
    queryFn: async (): Promise<DailyTotal[]> => {
      const isoDate = (d: Date) => d.toISOString().slice(0, 10)
      const to = new Date(`${endDate as string}T00:00:00Z`)
      const from = new Date(to)
      from.setUTCDate(to.getUTCDate() - days + 1)
      // An explicit `limit` matters: the server's own default is 50, so a
      // window with more debits than that would silently lose the tail of the
      // chart. `.rows` is the page's rows — `listTransactions` returns the
      // server's `meta` alongside them now and no caller throws it away for
      // everyone else.
      const txns = (
        await listTransactions({
          from: isoDate(from),
          to: isoDate(to),
          type: 'DEBIT',
          limit: MAX_TREND_ROWS,
        })
      ).rows
      const byDay = new Map<string, number>()
      for (let i = 0; i < days; i++) {
        const d = new Date(from)
        d.setUTCDate(from.getUTCDate() + i)
        byDay.set(isoDate(d), 0)
      }
      for (const t of txns) {
        // `listTransactions` has no currency parameter — the contract declares
        // none — so the filter is here, on rows the server already scoped to
        // this household. Dropping a row of another currency is the only honest
        // option: adding it would need a rate that does not exist.
        if (t.currency !== currency) continue
        // Only bucket dates inside the pre-seeded window; anything else would
        // silently add an extra day to the chart.
        if (!byDay.has(t.date)) continue
        byDay.set(t.date, (byDay.get(t.date) ?? 0) + Number(t.amount))
      }
      return Array.from(byDay.entries())
        .sort((a, b) => a[0].localeCompare(b[0]))
        .map(([date, total]) => ({ date, total }))
    },
    staleTime: 5 * 60 * 1000,
  })
}
