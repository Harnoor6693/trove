import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { QlSkeleton } from './QlSkeleton'

describe('QlSkeleton', () => {
  it('renders with default card variant', () => {
    render(<QlSkeleton data-testid="sk" />)
    const el = screen.getByTestId('sk')
    expect(el).toBeInTheDocument()
    expect(el.className).toMatch(/card/)
  })

  it('honors the variant prop', () => {
    render(<QlSkeleton variant="text" data-testid="sk" />)
    expect(screen.getByTestId('sk').className).toMatch(/text/)
  })

  it('honors the rows prop for row variant', () => {
    render(<QlSkeleton variant="row" rows={3} data-testid="sk" />)
    const rows = screen.getByTestId('sk').querySelectorAll('[data-row]')
    expect(rows.length).toBe(3)
  })
})
