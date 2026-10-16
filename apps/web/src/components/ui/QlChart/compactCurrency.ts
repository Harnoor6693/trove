/** Axis labels like `$120K`; the raw `120000` is wider than the gutter and clips. */
export function compactCurrency(locale: string, currency: string): (n: number) => string {
  const f = new Intl.NumberFormat(locale, { style: 'currency', currency, notation: 'compact', minimumFractionDigits: 0, maximumFractionDigits: 1 })
  return (n) => f.format(n)
}
