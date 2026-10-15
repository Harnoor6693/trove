import { render, screen, fireEvent } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ErrorBoundary } from './ErrorBoundary'

function Boom({ fail }: { fail: boolean }) {
  if (fail) throw new Error('explode')
  return <div>OK</div>
}

describe('ErrorBoundary', () => {
  beforeEach(() => vi.spyOn(console, 'error').mockImplementation(() => {}))
  afterEach(() => vi.restoreAllMocks())

  it('renders children when no error', () => {
    render(<ErrorBoundary><Boom fail={false} /></ErrorBoundary>)
    expect(screen.getByText('OK')).toBeInTheDocument()
  })

  it('renders fallback when child throws', () => {
    render(<ErrorBoundary><Boom fail /></ErrorBoundary>)
    expect(screen.getByText(/Couldn't load/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Retry/i })).toBeInTheDocument()
  })

  it('Retry resets and re-renders children', () => {
    const { rerender } = render(<ErrorBoundary><Boom fail /></ErrorBoundary>)
    fireEvent.click(screen.getByRole('button', { name: /Retry/i }))
    rerender(<ErrorBoundary><Boom fail={false} /></ErrorBoundary>)
    expect(screen.getByText('OK')).toBeInTheDocument()
  })

  it('Retry re-renders children with no parent rerender (B4-01)', () => {
    let fail = true
    function Once() {
      if (fail) throw new Error('explode')
      return <div>OK</div>
    }
    render(<ErrorBoundary><Once /></ErrorBoundary>)
    fail = false
    fireEvent.click(screen.getByRole('button', { name: /Retry/i }))
    expect(screen.getByText('OK')).toBeInTheDocument()
  })

  it('includes label in fallback message when provided', () => {
    render(<ErrorBoundary label="balance"><Boom fail /></ErrorBoundary>)
    expect(screen.getByText(/Couldn't load balance/i)).toBeInTheDocument()
  })
})
