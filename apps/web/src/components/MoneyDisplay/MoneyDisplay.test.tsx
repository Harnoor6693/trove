import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '@/test/test-utils'
import { MoneyDisplay } from './MoneyDisplay'

/**
 * Non-breaking spaces and narrow no-break spaces appear inside `Intl` currency
 * output; normalise so an assertion is about the format, not about which space
 * character the ICU data happens to use.
 */
function normalise(text: string): string {
  return text.replace(/[\u00A0\u202F]/g, ' ')
}

describe('MoneyDisplay', () => {
  it('renders CAD in en-CA', () => {
    renderWithProviders(
      <MoneyDisplay amount="1234.50" currency="CAD" locale="en-CA" data-testid="m" />
    )
    expect(normalise(screen.getByTestId('m').textContent!)).toBe('$1,234.50')
  })

  it('renders USD in en-US', () => {
    renderWithProviders(
      <MoneyDisplay amount="1234.50" currency="USD" locale="en-US" data-testid="m" />
    )
    expect(normalise(screen.getByTestId('m').textContent!)).toBe('$1,234.50')
  })

  it('distinguishes USD from CAD for a Canadian reader (defect I12)', () => {
    renderWithProviders(
      <MoneyDisplay amount="1234.50" currency="USD" locale="en-CA" data-testid="m" />
    )
    const text = normalise(screen.getByTestId('m').textContent!)
    expect(text).toContain('US$')
    expect(text).toContain('1,234.50')
  })

  it('distinguishes CAD from USD for an American reader', () => {
    renderWithProviders(
      <MoneyDisplay amount="1234.50" currency="CAD" locale="en-US" data-testid="m" />
    )
    const text = normalise(screen.getByTestId('m').textContent!)
    expect(text).toContain('CA$')
  })

  it('groups thousands and keeps two decimals — the old component did neither', () => {
    renderWithProviders(
      <MoneyDisplay amount="1234567.5" currency="CAD" locale="en-CA" data-testid="m" />
    )
    expect(normalise(screen.getByTestId('m').textContent!)).toBe('$1,234,567.50')
  })

  it('renders a negative amount as negative', () => {
    renderWithProviders(
      <MoneyDisplay amount="-42.00" currency="CAD" locale="en-CA" data-testid="m" />
    )
    expect(normalise(screen.getByTestId('m').textContent!)).toBe('-$42.00')
  })

  it('never invents a value from a non-numeric amount', () => {
    renderWithProviders(<MoneyDisplay amount="" currency="CAD" locale="en-CA" data-testid="m" />)
    expect(screen.getByTestId('m').textContent).toBe('—')
  })

  it('exposes the currency it rendered so a screen cannot silently mislabel money', () => {
    renderWithProviders(
      <MoneyDisplay amount="1.00" currency="USD" locale="en-US" data-testid="m" />
    )
    expect(screen.getByTestId('m')).toHaveAttribute('data-currency', 'USD')
  })
})
