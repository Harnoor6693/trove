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

  it('says how long ago an instant was, in the largest whole unit', () => {
    const now = Date.parse('2026-10-15T12:00:00Z')
    const ca = createUserFormat('en-CA', 'America/Toronto', true)
    expect(ca.formatRelativeTime('2026-10-15T11:59:40Z', now)).toBe('just now')
    expect(ca.formatRelativeTime('2026-10-15T11:55:00Z', now)).toBe('5 minutes ago')
    expect(ca.formatRelativeTime('2026-10-15T09:00:00Z', now)).toBe('3 hours ago')
    expect(ca.formatRelativeTime('2026-10-14T09:00:00Z', now)).toBe('yesterday')
    expect(ca.formatRelativeTime('2026-10-05T12:00:00Z', now)).toBe('10 days ago')
    expect(ca.formatRelativeTime('2026-07-15T12:00:00Z', now)).toBe('3 months ago')
    expect(ca.formatRelativeTime('2024-10-01T12:00:00Z', now)).toBe('2 years ago')
  })

  it('returns the input rather than a wrong value for an unparseable instant', () => {
    expect(createUserFormat('en-CA', 'UTC', true).formatRelativeTime('soon')).toBe('soon')
  })
})
