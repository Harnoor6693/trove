/**
 * The tax year a member most likely wants: from January to April (filing
 * season) the year that just ended, otherwise the current one. Read on the
 * member's own calendar, so the last evening of April is still April.
 * (Ported from the web app's `defaultTaxYear`.)
 */
export function defaultTaxYear(timeZone: string, now: Date = new Date()): number {
  let year = now.getFullYear();
  let month = now.getMonth() + 1;
  try {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone,
      year: 'numeric',
      month: 'numeric',
    }).formatToParts(now);
    year = Number(parts.find((p) => p.type === 'year')?.value ?? year);
    month = Number(parts.find((p) => p.type === 'month')?.value ?? month);
  } catch {
    // An unknown zone keeps the device's own calendar.
  }
  return month <= 4 ? year - 1 : year;
}
