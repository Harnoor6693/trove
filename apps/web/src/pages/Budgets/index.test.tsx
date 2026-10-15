/**
 * Budgets overview — behaviour, not "it rendered".
 */
import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import Budgets from './index'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

describe('Budgets overview', () => {
  it('renders the create-a-budget empty state, not an error, when there is no period this month', async () => {
    authenticate()
    mswState.currentBudgetPeriodId = null
    mswState.budgetPeriods = []
    renderWithProviders(<Budgets />)

    const empty = await screen.findByTestId('no-current-budget')
    expect(empty.textContent).toContain('No budget for this month')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('still offers the picker over a period the household has for another month', async () => {
    authenticate()
    mswState.currentBudgetPeriodId = null
    // budget-1 (September) is already in mswState.budgetPeriods by default.
    renderWithProviders(<Budgets />)

    await screen.findByTestId('no-current-budget')
    expect(screen.getByLabelText('Period')).toBeInTheDocument()
  })

  it('renders each envelope from its own row and computes no total across currencies', async () => {
    authenticate()
    mswState.envelopesByPeriod['budget-1'] = [
      ...mswState.envelopesByPeriod['budget-1'],
      {
        id: 'env-usd',
        householdId: '00000000-0000-0000-0000-0000000000ff',
        periodId: 'budget-1',
        categoryId: 'cat-transport',
        categoryName: 'Transport',
        allocated: '300.0000',
        spent: '50.0000',
        rolloverAmount: '0.0000',
        currency: 'USD',
        health: { healthPercentage: 17, paceMarkerPercentage: 50, overPace: false, remainingAmount: '250.0000', expectedSpend: '150.0000' },
        createdAt: '2026-09-01T10:00:00Z',
        updatedAt: '2026-09-01T10:00:00Z',
      },
    ]
    renderWithProviders(<Budgets />)

    const grid = await screen.findByTestId('envelope-grid')
    expect(grid.textContent).toContain('Groceries')
    expect(grid.textContent).toContain('Transport')
    // The two currencies' figures appear on their own rows, each tagged with
    // its own currency (`MoneyDisplay`'s `data-currency`) — never blended.
    const currencies = Array.from(grid.querySelectorAll('[data-currency]')).map((el) =>
      el.getAttribute('data-currency')
    )
    expect(currencies).toContain('CAD')
    expect(currencies).toContain('USD')
    // ...and nowhere is there a combined figure: no row/column sums a CAD and a
    // USD amount together (there is no exchange rate anywhere in this product).
    expect(screen.queryByText(/^total$/i)).not.toBeInTheDocument()
    expect(screen.queryByTestId('envelope-total')).not.toBeInTheDocument()
  })

  it('a ZERO_BASED 400 lands on the allocated field, not as a generic failure', async () => {
    authenticate()
    mswState.budgetPeriods = mswState.budgetPeriods.map((p) =>
      p.id === 'budget-1' ? { ...p, budgetType: 'ZERO_BASED' } : p
    )
    mswState.zeroBasedViolationField = 'allocated'
    const user = userEvent.setup()
    renderWithProviders(<Budgets />)
    await screen.findByTestId('envelope-grid')

    await user.click(screen.getByRole('button', { name: /add envelope/i }))
    const dialog = await screen.findByTestId('create-envelope-dialog')
    await user.selectOptions(within(dialog).getByLabelText('Category'), 'cat-transport')
    await user.type(within(dialog).getByLabelText('Allocated'), '999')
    await user.click(within(dialog).getByRole('button', { name: /add envelope/i }))

    const fieldError = await within(dialog).findByTestId('ql-input-error')
    expect(fieldError.textContent).toContain('zero-based')
    // The envelope was NOT created — the server's answer is the authority.
    expect(mswState.envelopesByPeriod['budget-1']).toHaveLength(1)
  })

  it('a genuine fetch failure on the current/period queries shows only the error, never the create-a-budget empty state', async () => {
    authenticate()
    mswState.budgetsRateLimited = true
    renderWithProviders(<Budgets />)

    // Both `current` and `periods` fail on this toggle, so two error alerts
    // render (one per query) — assert on whichever surfaces first.
    const alerts = await screen.findAllByRole('alert')
    expect(alerts.some((a) => a.textContent?.includes('Too many requests just now'))).toBe(true)
    // The misleading "no budget this month" prompt must never appear
    // alongside a real error — a 429/401/500 is not the same thing as the
    // household genuinely having no period this month.
    expect(screen.queryByTestId('no-current-budget')).not.toBeInTheDocument()
  })

  it('a 409 on create (a period already exists for that month) shows the conflict copy and creates nothing', async () => {
    authenticate()
    mswState.budgetPeriodConflict = true
    const periodsBefore = mswState.budgetPeriods.length
    const user = userEvent.setup()
    renderWithProviders(<Budgets />)
    await screen.findByTestId('envelope-grid')

    await user.click(screen.getByRole('button', { name: /^open a period$/i }))
    const createDialog = await screen.findByTestId('create-period-dialog')
    await user.clear(within(createDialog).getByLabelText('Month'))
    await user.type(within(createDialog).getByLabelText('Month'), '2026-11')
    await user.type(within(createDialog).getByLabelText('Total income'), '4000')
    await user.click(within(createDialog).getByRole('button', { name: /open period/i }))

    const alert = await within(createDialog).findByRole('alert')
    expect(alert.textContent).toContain('already has a budget period')
    expect(mswState.budgetPeriods).toHaveLength(periodsBefore)
  })

  it('a 409 on add-envelope (period already budgets that category) shows the conflict copy and adds nothing', async () => {
    authenticate()
    mswState.envelopeDuplicateConflict = true
    const user = userEvent.setup()
    renderWithProviders(<Budgets />)
    await screen.findByTestId('envelope-grid')
    const envelopesBefore = mswState.envelopesByPeriod['budget-1'].length

    await user.click(screen.getByRole('button', { name: /add envelope/i }))
    const dialog = await screen.findByTestId('create-envelope-dialog')
    await user.selectOptions(within(dialog).getByLabelText('Category'), 'cat-transport')
    await user.type(within(dialog).getByLabelText('Allocated'), '100')
    await user.click(within(dialog).getByRole('button', { name: /add envelope/i }))

    const alert = await within(dialog).findByRole('alert')
    expect(alert.textContent).toContain('already budgets that category')
    expect(mswState.envelopesByPeriod['budget-1']).toHaveLength(envelopesBefore)
  })

  it('a ZERO_BASED 400 on editing the period lands on the total-income field, not as a generic failure', async () => {
    authenticate()
    mswState.budgetPeriods = mswState.budgetPeriods.map((p) =>
      p.id === 'budget-1' ? { ...p, budgetType: 'ZERO_BASED' } : p
    )
    mswState.zeroBasedViolationField = 'totalIncome'
    const user = userEvent.setup()
    renderWithProviders(<Budgets />)
    await screen.findByTestId('envelope-grid')

    await user.click(screen.getByRole('button', { name: /edit period/i }))
    const dialog = await screen.findByTestId('edit-period-dialog')
    const totalIncomeInput = within(dialog).getByLabelText('Total income')
    await user.clear(totalIncomeInput)
    await user.type(totalIncomeInput, '999')
    await user.click(within(dialog).getByRole('button', { name: /^save$/i }))

    const fieldError = await within(dialog).findByTestId('ql-input-error')
    expect(fieldError.textContent).toContain('zero-based')
    // The period was NOT updated — the server's answer is the authority.
    expect(mswState.budgetPeriods.find((p) => p.id === 'budget-1')?.totalIncome).not.toBe('999')
  })

  it('a 429 on the envelope list shows the wait-and-retry copy and does not refresh or log out', async () => {
    authenticate()
    mswState.envelopesRateLimited = true
    renderWithProviders(<Budgets />)

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('Too many requests just now')
    expect(mswState.refreshCalls).toBe(0)
    expect(useAuthStore.getState().status).toBe('authenticated')
  })

  it('opens a new period and deletes one, both through their dialogs', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Budgets />)
    await screen.findByTestId('envelope-grid')

    await user.click(screen.getByRole('button', { name: /^open a period$/i }))
    const createDialog = await screen.findByTestId('create-period-dialog')
    await user.clear(within(createDialog).getByLabelText('Month'))
    await user.type(within(createDialog).getByLabelText('Month'), '2026-10')
    await user.type(within(createDialog).getByLabelText('Total income'), '4000')
    await user.click(within(createDialog).getByRole('button', { name: /open period/i }))

    await waitFor(() =>
      expect(mswState.budgetPeriods.some((p) => p.month === '2026-10-01')).toBe(true)
    )
    await waitFor(() => expect(screen.queryByTestId('create-period-dialog')).not.toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: /delete period/i }))
    const deleteDialog = await screen.findByTestId('delete-period-dialog')
    await user.click(within(deleteDialog).getByRole('button', { name: /delete period and its envelopes/i }))

    expect(mswState.budgetPeriods.some((p) => p.id === 'budget-1')).toBe(false)
  })

  it('edits a period, and adds, edits and removes an envelope through the dialogs', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Budgets />)
    await screen.findByTestId('envelope-grid')

    await user.click(screen.getByRole('button', { name: /edit period/i }))
    const editPeriodDialog = await screen.findByTestId('edit-period-dialog')
    const totalIncomeInput = within(editPeriodDialog).getByLabelText('Total income')
    await user.clear(totalIncomeInput)
    await user.type(totalIncomeInput, '5000')
    await user.click(within(editPeriodDialog).getByRole('button', { name: /^save$/i }))

    await waitFor(() =>
      expect(mswState.budgetPeriods.find((p) => p.id === 'budget-1')?.totalIncome).toBe('5000')
    )

    await user.click(screen.getByRole('button', { name: /add envelope/i }))
    const createEnvelopeDialog = await screen.findByTestId('create-envelope-dialog')
    await user.selectOptions(within(createEnvelopeDialog).getByLabelText('Category'), 'cat-transport')
    await user.type(within(createEnvelopeDialog).getByLabelText('Allocated'), '150')
    await user.click(within(createEnvelopeDialog).getByRole('button', { name: /add envelope/i }))

    await screen.findByText('Transport')
    expect(mswState.envelopesByPeriod['budget-1']).toHaveLength(2)

    const transportRow = screen.getByText('Transport').closest('tr') as HTMLElement
    await user.click(within(transportRow).getByRole('button', { name: /^edit$/i }))
    const editEnvelopeDialog = await screen.findByTestId('edit-envelope-dialog')
    const allocatedInput = within(editEnvelopeDialog).getByLabelText('Allocated')
    await user.clear(allocatedInput)
    await user.type(allocatedInput, '175')
    await user.click(within(editEnvelopeDialog).getByRole('button', { name: /^save$/i }))

    await waitFor(() =>
      expect(
        mswState.envelopesByPeriod['budget-1'].find((e) => e.categoryId === 'cat-transport')
          ?.allocated
      ).toBe('175')
    )

    const updatedTransportRow = screen.getByText('Transport').closest('tr') as HTMLElement
    await user.click(within(updatedTransportRow).getByRole('button', { name: /^remove$/i }))
    const deleteEnvelopeDialog = await screen.findByTestId('delete-envelope-dialog')
    await user.click(within(deleteEnvelopeDialog).getByRole('button', { name: /^remove$/i }))

    await waitFor(() => expect(mswState.envelopesByPeriod['budget-1']).toHaveLength(1))
  })
})
