/**
 * The one money renderer.
 *
 * `currency` is a REQUIRED prop, not an optional one the component may ignore —
 * defect I12 was exactly a `currency?: string` that was destructured away while
 * the component printed a literal `$`. A CAD household and a USD household now
 * get visibly different output, and a call site that does not know its currency
 * cannot compile.
 *
 * `amount` is the decimal string the server sends (§5.4). It is never cents and
 * never a JSON number, so nothing here divides by 100 and nothing rounds twice.
 */
import { parseDecimal, useUserFormat } from '@hooks/useUserFormat'
import type { LocaleCode, Money } from '@api/types'
import styles from './MoneyDisplay.module.css'

export interface MoneyDisplayProps extends Money {
  /** Overrides the user's locale; only tests and previews should need this. */
  locale?: LocaleCode
  /** Colour the value by sign (green positive / red negative). Default true. */
  colored?: boolean
  className?: string
  'data-testid'?: string
}

export function MoneyDisplay({
  amount,
  currency,
  locale,
  colored = true,
  className,
  'data-testid': testId,
}: MoneyDisplayProps) {
  const format = useUserFormat()
  const effective = locale
    ? new Intl.NumberFormat(locale, { style: 'currency', currency })
    : null
  const numeric = parseDecimal(amount)
  const text = effective
    ? numeric === null
      ? '—'
      : effective.format(numeric)
    : format.formatMoney({ amount, currency })

  const tone =
    !colored || numeric === null
      ? styles.neutral
      : numeric > 0
        ? styles.positive
        : numeric < 0
          ? styles.negative
          : styles.neutral

  return (
    <span
      className={`${styles.amount} ${tone} ${className ?? ''}`}
      data-testid={testId}
      data-currency={currency}
    >
      {text}
    </span>
  )
}
