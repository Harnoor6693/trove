/**
 * Turns what a member typed into the plain decimal literal the server wants
 * (`-?\d+(\.\d+)?`). `expo-localization` is not installed, so the separator is
 * inferred from the text itself rather than the device locale:
 *
 * - a lone comma followed by 1 or 2 digits is a decimal comma (`12,34`);
 * - any other comma is a thousands separator (`1,234`, `1,234.50`);
 * - when both appear, whichever comes last is the decimal mark (`1.234,56`).
 *
 * Anything that is still not a number is returned trimmed and untouched, so
 * the server (or the form) rejects it instead of it being silently altered.
 */
export function normaliseAmountInput(raw: string): string {
  const text = raw.trim();
  const lastComma = text.lastIndexOf(',');
  if (lastComma === -1) return text;
  const lastDot = text.lastIndexOf('.');
  const shortTail = /^\d{1,2}$/.test(text.slice(lastComma + 1));
  // With no dot to disambiguate, only a single comma can be the decimal mark.
  const decimalComma =
    shortTail && lastComma > lastDot && (lastDot !== -1 || text.indexOf(',') === lastComma);
  if (decimalComma) {
    return `${text.slice(0, lastComma).replace(/[.,]/g, '')}.${text.slice(lastComma + 1)}`;
  }
  return text.replace(/,/g, '');
}
