/** A whole number typed into a text field, or null: `abc`, blank, `1e3` and `5.5` are all null, never `NaN` or 0. */
export function wholeNumber(text: string): number | null {
  return /^\d+$/.test(text.trim()) ? Number(text.trim()) : null
}

export const WHOLE_NUMBER_ERROR = 'Enter a whole number.'
