/**
 * Savings goals — behaviour, not "it rendered".
 */
import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import Goals from './Goals'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

describe('Goals', () => {
  it('renders the amounts the server sent and no invented progress figure', async () => {
    authenticate()
    renderWithProviders(<Goals />)

    const row = await screen.findByTestId('goal-row-goal-1')
    expect(row.textContent).toContain('Emergency Fund')
    expect(row.textContent).toContain('2,500.00')
    expect(row.textContent).toContain('10,000.00')
    // `GoalResponse` carries no progress percentage field at all — only
    // `achieved` (server-derived) is shown, never a client-computed ratio
    // rendered as a percentage.
    expect(row.textContent).not.toMatch(/%/)
    expect(within(row).getByText('In progress')).toBeInTheDocument()
  })

  it('shows Achieved when the server says so', async () => {
    authenticate()
    mswState.goals = [{ ...mswState.goals[0], achieved: true, currentAmount: '10000.0000' }]
    renderWithProviders(<Goals />)

    const row = await screen.findByTestId('goal-row-goal-1')
    expect(within(row).getByText('Achieved')).toBeInTheDocument()
  })

  it('starts a goal through the dialog', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Goals />)
    await screen.findByTestId('goal-list')

    await user.click(screen.getByRole('button', { name: /start a goal/i }))
    const dialog = await screen.findByTestId('create-goal-dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'New roof')
    await user.type(within(dialog).getByLabelText('Target amount'), '5000')
    await user.click(within(dialog).getByRole('button', { name: /start goal/i }))

    await screen.findByText('New roof')
    expect(mswState.goals.some((g) => g.name === 'New roof')).toBe(true)
  })

  it('records a contribution through the edit dialog', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Goals />)
    const row = await screen.findByTestId('goal-row-goal-1')

    await user.click(within(row).getByRole('button', { name: /contribute \/ edit/i }))
    const dialog = await screen.findByTestId('edit-goal-dialog')
    const savedInput = within(dialog).getByLabelText('Saved so far')
    await user.clear(savedInput)
    await user.type(savedInput, '3000')
    await user.click(within(dialog).getByRole('button', { name: /^save$/i }))

    await waitFor(() => expect(row.textContent).toContain('3,000.00'))
    expect(mswState.goals.find((g) => g.id === 'goal-1')?.currentAmount).toBe('3000')
  })

  it('deletes a goal', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Goals />)
    const row = await screen.findByTestId('goal-row-goal-1')

    await user.click(within(row).getByRole('button', { name: /delete/i }))
    const dialog = await screen.findByTestId('delete-goal-dialog')
    await user.click(within(dialog).getByRole('button', { name: /^delete$/i }))

    await screen.findByText('No savings goals yet')
    expect(mswState.goals).toHaveLength(0)
  })
})
