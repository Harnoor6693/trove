import { describe, expect, it } from 'vitest'
import { compactCurrency } from './compactCurrency'

describe('compactCurrency', () => {
  it('abbreviates axis values so they fit the gutter instead of clipping', () => {
    expect(compactCurrency('en-CA', 'CAD')(120000)).toBe('$120K')
    expect(compactCurrency('en-US', 'USD')(1500000)).toBe('$1.5M')
    expect(compactCurrency('en-CA', 'CAD')(950)).toBe('$950')
  })
})
