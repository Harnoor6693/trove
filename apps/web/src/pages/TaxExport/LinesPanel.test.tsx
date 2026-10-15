/**
 * Tax lines — behaviour, not "it rendered".
 *
 * This is an EXPORT, not an engine: nothing here computes anything. The
 * per-category line code and wording are labels the member writes and the
 * server stores verbatim.
 */
import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState, resetMswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import { LinesPanel } from './LinesPanel'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

describe('Tax lines', () => {
  it('saves a per-category override with the values the member typed, never a computed one', async () => {
    authenticate()
    resetMswState()
    const user = userEvent.setup()
    renderWithProviders(<LinesPanel />)

    const row = await screen.findByTestId('tax-line-food.groceries')
    await user.type(within(row).getByLabelText('Line code'), '9270')
    await user.type(within(row).getByLabelText('Line wording'), 'Other expenses')
    await user.click(within(row).getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(mswState.lastTaxLineBody).toMatchObject({
        lineCode: '9270',
        lineLabel: 'Other expenses',
      })
    )
    // Nothing about the write is a calculation — no rate, no total.
    expect(mswState.lastTaxLineBody).not.toHaveProperty('rate')
  })

  it('"Mark as no line" writes the seeded NONE convention rather than an empty string', async () => {
    authenticate()
    resetMswState()
    const user = userEvent.setup()
    renderWithProviders(<LinesPanel />)

    const row = await screen.findByTestId('tax-line-business.software')
    await user.click(within(row).getByRole('button', { name: 'Mark as no line' }))

    await waitFor(() => expect(mswState.lastTaxLineBody?.lineCode).toBe('NONE'))
  })
})
