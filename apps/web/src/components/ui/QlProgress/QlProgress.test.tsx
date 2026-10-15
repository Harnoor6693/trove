import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { QlProgress } from './QlProgress'

describe('QlProgress', () => {
  it('is not over at exactly 100 percent: fully used is not over budget', () => {
    render(<QlProgress value={100} max={100} label="Rent" />)
    expect(screen.queryByTestId('ql-progress-over')).toBeNull()
    expect(screen.getByTestId('ql-progress')).toBeInTheDocument()
  })

  it('is over only when the value exceeds the max', () => {
    render(<QlProgress value={288} max={100} label="Shopping" />)
    expect(screen.getByTestId('ql-progress-over')).toBeInTheDocument()
  })

  it('still fills the bar fully when over', () => {
    render(<QlProgress value={288} max={100} label="Shopping" />)
    expect(screen.getByTestId('ql-progress-fill')).toHaveStyle({ width: '100%' })
  })
})
