/**
 * The single money/date renderer for the whole SPA.
 *
 * Readiness gap P2-15: a Vancouver user's evening purchase must not land on
 * tomorrow, Canadians write 2026-09-03 and Americans write 9/3/2026, and a USD
 * household must never see a bare `$` that means CAD. Every formatted value in
 * the app comes from here, keyed on the user's own `locale` and `timezone`
 * (`/v1/me/preferences`) and the currency carried by the amount itself.
 *
 * There is deliberately no default currency. An amount arrives from the API as a
 * `Money` — `{amount: "12.34", currency: "CAD"}` — and its currency travels with
 * it, so no call site can guess and no screen can render the wrong symbol.
 */
import { useMemo } from 'react'
import { useAuthStore } from '@stores/authStore'
import { usePreferences } from './usePreferences'
import type { CurrencyCode, LocaleCode, Money } from '@api/types'

/** Browser fallback used only until the user's own preferences have loaded. */
function browserLocale(): LocaleCode {
  const langs = typeof navigator === 'undefined' ? [] : [navigator.language]
  return langs.some((l) => l?.toLowerCase().endsWith('-us')) ? 'en-US' : 'en-CA'
}

function browserTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

/**
 * Explicit parts rather than `dateStyle: 'medium'`, which renders identically in
 * en-CA and en-US ("Sep 3, 2026") and so would defeat the whole point of gap
 * P2-15. These give 2026-09-03 for a Canadian and 09/03/2026 for an American.
 */
const DATE_PARTS = { year: 'numeric', month: '2-digit', day: '2-digit' } as const

export interface FormatMoney {
  (money: Money): string
  (amount: string, currency: CurrencyCode): string
}

export interface UserFormat {
  locale: LocaleCode
  timezone: string
  /** True once the values above come from the user rather than the browser. */
  isResolved: boolean
  formatMoney: FormatMoney
  /** A calendar date (`YYYY-MM-DD`) rendered as written, with no timezone shift. */
  formatDate: (isoDate: string) => string
  /** An instant (`…T…Z`) rendered in the user's own timezone. */
  formatDateTime: (isoInstant: string) => string
  /** How long ago an instant was ("3 hours ago"), in the user's locale. */
  formatRelativeTime: (isoInstant: string, now?: number) => string
}

/**
 * `Number('')` and `Number('   ')` are both `0`. A blank amount must never render
 * as a confident zero balance, so parsing is strict: only a decimal literal counts.
 */
export function parseDecimal(amount: string): number | null {
  if (typeof amount !== 'string' || !/^-?\d+(\.\d+)?$/.test(amount.trim())) return null
  const value = Number(amount.trim())
  return Number.isFinite(value) ? value : null
}

/** Largest unit first: an instant 26 hours old reads "yesterday", not "26 hours ago". */
const RELATIVE_UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 365 * 24 * 3600],
  ['month', 30 * 24 * 3600],
  ['day', 24 * 3600],
  ['hour', 3600],
  ['minute', 60],
]

function buildDateOnly(isoDate: string): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoDate)
  if (!m) return null
  return new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])))
}

export function createUserFormat(locale: LocaleCode, timezone: string, isResolved: boolean): UserFormat {
  const money = (first: Money | string, second?: CurrencyCode): string => {
    const amount = typeof first === 'string' ? first : first.amount
    const currency = typeof first === 'string' ? second : first.currency
    if (!currency) {
      throw new Error('formatMoney requires a currency; pass a Money or (amount, currency)')
    }
    const value = parseDecimal(amount)
    if (value === null) return '—'
    return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(value)
  }

  return {
    locale,
    timezone,
    isResolved,
    formatMoney: money as FormatMoney,
    formatDate: (isoDate: string) => {
      const asDate = buildDateOnly(isoDate)
      if (asDate) {
        // A plain calendar date has no instant, so it is formatted in UTC to keep
        // the day the server sent. Converting it into a timezone would move it.
        return new Intl.DateTimeFormat(locale, { ...DATE_PARTS, timeZone: 'UTC' }).format(asDate)
      }
      const parsed = new Date(isoDate)
      if (Number.isNaN(parsed.getTime())) return isoDate
      return new Intl.DateTimeFormat(locale, { ...DATE_PARTS, timeZone: timezone }).format(parsed)
    },
    formatDateTime: (isoInstant: string) => {
      const parsed = new Date(isoInstant)
      if (Number.isNaN(parsed.getTime())) return isoInstant
      return new Intl.DateTimeFormat(locale, {
        dateStyle: 'medium',
        timeStyle: 'short',
        timeZone: timezone,
      }).format(parsed)
    },
    formatRelativeTime: (isoInstant: string, now: number = Date.now()) => {
      const at = Date.parse(isoInstant)
      if (Number.isNaN(at)) return isoInstant
      const seconds = Math.round((at - now) / 1000)
      if (Math.abs(seconds) < 60) return 'just now'
      const [unit, size] =
        RELATIVE_UNITS.find(([, length]) => Math.abs(seconds) >= length) ?? RELATIVE_UNITS[4]
      return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(
        Math.round(seconds / size),
        unit
      )
    },
  }
}

export function useUserFormat(): UserFormat {
  const prefs = usePreferences()
  const isAuthenticated = useAuthStore((s) => s.status === 'authenticated')
  const locale = prefs.data?.locale
  const timezone = prefs.data?.timezone

  return useMemo(
    () =>
      createUserFormat(
        locale ?? browserLocale(),
        timezone ?? browserTimezone(),
        Boolean(isAuthenticated && locale && timezone)
      ),
    [locale, timezone, isAuthenticated]
  )
}
