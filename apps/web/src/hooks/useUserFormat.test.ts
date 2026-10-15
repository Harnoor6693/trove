import { describe, expect, it } from 'vitest'
import { createUserFormat } from './useUserFormat'

function normalise(text: string): string {
  return text.replace(/[\u00A0\u202F]/g, ' ')
}

describe('useUserFormat', () => {
  it('formats money from a Money object', () => {
    const f = createUserFormat('en-CA', 'America/Toronto', true)
    expect(normalise(f.formatMoney({ amount: '12.34', currency: 'CAD' }))).toBe('$12.34')
  })

  it('formats money from (amount, currency)', () => {
    const f = createUserFormat('en-US', 'America/New_York', true)
    expect(normalise(f.formatMoney('12.34', 'USD'))).toBe('$12.34')
  })

  it('renders a calendar date without shifting it into a timezone', () => {
    // A Vancouver user must still see 2026-09-03 for a transaction dated
    // 2026-09-03, not the day before (gap P2-15).
    const f = createUserFormat('en-CA', 'America/Vancouver', true)
    expect(f.formatDate('2026-09-03')).toContain('2026')
    expect(f.formatDate('2026-09-03')).toContain('3')
  })

  it('renders the same instant differently per locale', () => {
    const ca = createUserFormat('en-CA', 'UTC', true)
    const us = createUserFormat('en-US', 'UTC', true)
    expect(ca.formatDate('2026-09-03')).not.toBe(us.formatDate('2026-09-03'))
  })

  it('renders an instant in the user’s own timezone', () => {
    const toronto = createUserFormat('en-CA', 'America/Toronto', true)
    const vancouver = createUserFormat('en-CA', 'America/Vancouver', true)
    const instant = '2026-09-03T02:00:00Z'
    expect(toronto.formatDateTime(instant)).not.toBe(vancouver.formatDateTime(instant))
  })

  it('returns the input rather than a wrong value for an unparseable date', () => {
    const f = createUserFormat('en-CA', 'UTC', true)
    expect(f.formatDate('not-a-date')).toBe('not-a-date')
  })
})
