/**
 * Money, number and date formatting driven by the signed-in user's own
 * settings — never the device's, and never a hardcoded currency.
 *
 * Three inputs, all from the server:
 *   - `currency` and `country` from `GET /v1/auth/me` (§4.2 — identity fields,
 *     fixed at registration).
 *   - `locale` and `timezone` from `GET /v1/me/preferences` (§4.2).
 *
 * Amounts arrive as decimal strings (`"12.34"`, §5.4). They are parsed here
 * and nowhere else, so the `*Cents` integers this app used to carry — a third
 * representation matching neither the server nor the web client (§4.13) — are
 * gone for good.
 *
 * `toLocaleString`, a bare `'$'` and a hardcoded `'CAD'` are all banned by an
 * ESLint rule (`.eslintrc.cjs`); this module is the single exemption.
 */

import { useMemo } from 'react';
import { useAuthStore } from '@stores/authStore';
import type { CurrencyCode, Decimal, Money } from '@api/types';

/** Shown wherever an amount cannot be formatted yet (no currency loaded). */
export const UNKNOWN_VALUE = '—';

const DEFAULT_LOCALE = 'en-CA';

export interface UserFormatContext {
  /** null until `GET /v1/auth/me` has answered. */
  currency: CurrencyCode | null;
  locale: string;
  timeZone: string;
}

export interface UserFormat extends UserFormatContext {
  /** Format a decimal-string amount, or a `Money` wrapper, as currency. */
  money: (value: Decimal | Money | null | undefined, options?: MoneyOptions) => string;
  /** Format a plain number (counts, percentages) in the user's locale. */
  number: (value: number, options?: Intl.NumberFormatOptions) => string;
  /** Format an ISO date (`2026-09-03`) or instant in the user's timezone. */
  date: (iso: string | null | undefined, style?: DateStyle) => string;
}

export interface MoneyOptions {
  /** Override the user's currency (a row in a foreign currency). */
  currency?: CurrencyCode;
  /** Drop the decimals — for chart axes and compact summaries. */
  hideCents?: boolean;
  /** Always show a leading `+` on positive amounts. */
  signed?: boolean;
}

export type DateStyle = 'short' | 'medium' | 'long' | 'monthYear' | 'dateTime';

const DATE_OPTIONS: Record<DateStyle, Intl.DateTimeFormatOptions> = {
  short: { month: 'short', day: 'numeric' },
  medium: { year: 'numeric', month: 'short', day: 'numeric' },
  long: { year: 'numeric', month: 'long', day: 'numeric' },
  monthYear: { year: 'numeric', month: 'long' },
  dateTime: { year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' },
};

/** The device's locale, used only until the server's preference arrives. */
export function deviceLocale(): string {
  try {
    return new Intl.DateTimeFormat().resolvedOptions().locale || DEFAULT_LOCALE;
  } catch {
    return DEFAULT_LOCALE;
  }
}

/** The device's IANA zone, used only until the server's preference arrives. */
export function deviceTimeZone(): string {
  try {
    return new Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

function isMoney(value: Decimal | Money): value is Money {
  return typeof value === 'object' && value !== null && 'amount' in value;
}

/**
 * Parse a decimal string. Returns null for anything unparseable so callers
 * render the placeholder rather than `NaN` or, worse, a confident wrong
 * number.
 *
 * The test is a regex, not `Number()`, and deliberately so: `Number('')` and
 * `Number('   ')` are both `0`, `Number('0x1f')` is 31, `Number('1e3')` is
 * 1000 and `Number('Infinity')` is `Infinity`. A blank or malformed amount
 * must never render as a zero balance, and the only shape `trove-core`'s
 * global `BigDecimal` `ToStringSerializer` ever emits is a plain decimal
 * literal (§5.4). `apps/web/src/hooks/useUserFormat.ts` uses the same guard.
 */
const DECIMAL_LITERAL = /^-?\d+(\.\d+)?$/;

export function parseDecimal(value: Decimal | null | undefined): number | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!DECIMAL_LITERAL.test(trimmed)) return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * The formatting core, free of React so it can be unit-tested and reused
 * outside a component.
 */
export function createUserFormat(context: UserFormatContext): UserFormat {
  const { currency, locale, timeZone } = context;

  const money: UserFormat['money'] = (value, options = {}) => {
    const resolvedCurrency = options.currency ?? currency;
    if (resolvedCurrency === null || resolvedCurrency === undefined) return UNKNOWN_VALUE;
    if (value === null || value === undefined) return UNKNOWN_VALUE;

    const raw = isMoney(value) ? value.amount : value;
    const currencyCode = isMoney(value) ? value.currency : resolvedCurrency;
    const amount = parseDecimal(raw);
    if (amount === null) return UNKNOWN_VALUE;

    const fractionDigits = options.hideCents === true ? 0 : 2;
    const formatted = new Intl.NumberFormat(locale, {
      style: 'currency',
      currency: currencyCode,
      minimumFractionDigits: fractionDigits,
      maximumFractionDigits: fractionDigits,
    }).format(amount);

    return options.signed === true && amount > 0 ? `+${formatted}` : formatted;
  };

  const number: UserFormat['number'] = (value, options) =>
    new Intl.NumberFormat(locale, options).format(value);

  const date: UserFormat['date'] = (iso, style = 'medium') => {
    if (typeof iso !== 'string' || iso === '') return UNKNOWN_VALUE;
    // A bare `YYYY-MM-DD` is a calendar date; parsing it as UTC midnight and
    // formatting it in the user's zone would shift it a day west of UTC, so
    // it is rendered as the literal date the server sent.
    const dateOnly = /^\d{4}-\d{2}-\d{2}$/.test(iso);
    const parsed = new Date(dateOnly ? `${iso}T00:00:00` : iso);
    if (Number.isNaN(parsed.getTime())) return UNKNOWN_VALUE;

    return new Intl.DateTimeFormat(locale, {
      ...DATE_OPTIONS[style],
      ...(dateOnly ? {} : { timeZone }),
    }).format(parsed);
  };

  return { currency, locale, timeZone, money, number, date };
}

/**
 * The hook screens use. Selects primitives from the store — never an object —
 * so it cannot trigger the zustand v5 re-render loop.
 */
export function useUserFormat(): UserFormat {
  const currency = useAuthStore((s) => s.me?.currency ?? null);
  // `/v1/auth/me` carries `locale` and `timezone` as well as `/v1/me/preferences`
  // does. Preferences win because that is the surface Settings writes to, so an
  // edit shows immediately; `me` is the fallback that makes formatting correct
  // from the first authenticated call, before preferences have loaded.
  const locale = useAuthStore((s) => s.preferences?.locale ?? s.me?.locale ?? null);
  const timezone = useAuthStore((s) => s.preferences?.timezone ?? s.me?.timezone ?? null);

  return useMemo(
    () =>
      createUserFormat({
        currency,
        locale: locale ?? deviceLocale(),
        timeZone: timezone ?? deviceTimeZone(),
      }),
    [currency, locale, timezone]
  );
}
