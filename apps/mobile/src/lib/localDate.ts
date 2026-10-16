/** `YYYY-MM-DD` for the device's local calendar day (never the UTC one). */
export function localDateIso(date: Date = new Date()): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}
