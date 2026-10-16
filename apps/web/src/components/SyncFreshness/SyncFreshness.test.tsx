import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '@/test/test-utils'
import { SyncFreshness } from './SyncFreshness'

/**
 * C-01r: a SimpleFIN account says how fresh it is in words a member reads at a
 * glance, and keeps the exact moment one hover (or one screen-reader pass) away.
 */
describe('SyncFreshness', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-15T12:00:00Z'))
  })
  afterEach(() => vi.useRealTimers())

  it('says "Synced 3 hours ago" with the absolute time as its title', () => {
    renderWithProviders(
      <SyncFreshness account={{ sourceKind: 'SIMPLEFIN', lastSyncedAt: '2026-10-15T09:00:00Z' }} />
    )
    const time = screen.getByText('Synced 3 hours ago')
    expect(time.tagName).toBe('TIME')
    expect(time).toHaveAttribute('dateTime', '2026-10-15T09:00:00Z')
    expect(time.getAttribute('title')).toMatch(/2026/)
  })

  it('says "Never synced" for a bank connection that has not run yet', () => {
    renderWithProviders(
      <SyncFreshness account={{ sourceKind: 'SIMPLEFIN', lastSyncedAt: null }} />
    )
    expect(screen.getByText('Never synced')).toBeInTheDocument()
  })

  it('shows nothing new on an account that is not a bank connection', () => {
    renderWithProviders(
      <SyncFreshness account={{ sourceKind: 'CSV', lastSyncedAt: '2026-10-15T09:00:00Z' }} />
    )
    expect(screen.queryByText(/ago/)).not.toBeInTheDocument()
    expect(screen.getByText(/^Last synced .*2026/)).toBeInTheDocument()
  })
})
