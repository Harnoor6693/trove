/**
 * Merchant rules — behaviour, not "it rendered".
 */
import { describe, expect, it } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import Rules from './Rules'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

describe('Rules', () => {
  it('renders the rules in priority order', async () => {
    authenticate()
    renderWithProviders(<Rules />)

    const list = await screen.findByTestId('rule-list')
    const rows = within(list).getAllByRole('listitem')
    expect(rows[0].textContent).toContain('STARBUCKS')
    expect(rows[1].textContent).toContain('UBER')
  })

  it('moving a rule up sends the full ordered id list the operation declares', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Rules />)
    await screen.findByTestId('rule-list')

    await user.click(screen.getByRole('button', { name: /move uber up/i }))

    await new Promise((r) => setTimeout(r, 0))
    expect(mswState.lastReorderBody).toEqual({ orderedIds: ['rule-2', 'rule-1'] })
    // The list re-renders in the new, server-confirmed order.
    const rows = within(await screen.findByTestId('rule-list')).getAllByRole('listitem')
    expect(rows[0].textContent).toContain('UBER')
  })

  it('a dry run shows the mocked match and writes nothing', async () => {
    authenticate()
    const rulesBefore = mswState.categoryRules.length
    const user = userEvent.setup()
    renderWithProviders(<Rules />)
    await screen.findByTestId('rule-list')

    const tester = screen.getByTestId('dry-run-tester')
    await user.type(within(tester).getByLabelText(/merchant to test/i), 'STARBUCKS #4471')
    await user.click(within(tester).getByRole('button', { name: /^test$/i }))

    const result = await screen.findByTestId('dry-run-result')
    expect(result.textContent).toContain('Groceries')
    expect(result.textContent).toContain('0.990')
    expect(mswState.lastDryRunBody).toEqual({ merchant: 'STARBUCKS #4471' })
    expect(mswState.categoryRules).toHaveLength(rulesBefore)
  })

  it('a 429 on the list shows the wait-and-retry copy and does not refresh or log out', async () => {
    authenticate()
    mswState.categoryRulesRateLimited = true
    renderWithProviders(<Rules />)

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('Too many requests just now')
    // A 429 is not an authentication problem: nothing refreshed, nothing logged out.
    expect(mswState.refreshCalls).toBe(0)
    expect(useAuthStore.getState().status).toBe('authenticated')
  })

  it('a 409 on create (merchant already has a rule) shows the conflict copy and adds nothing', async () => {
    authenticate()
    mswState.categoryRuleConflict = true
    const rulesBefore = mswState.categoryRules.length
    const user = userEvent.setup()
    renderWithProviders(<Rules />)
    await screen.findByTestId('rule-list')

    await user.click(screen.getByRole('button', { name: /^add rule$/i }))
    const createDialog = await screen.findByTestId('rule-form-dialog')
    // A merchant name that does not naturally collide — the 409 here comes
    // only from the armed conflict toggle, proving that path independently
    // of the "same merchant twice" duplicate check.
    await user.type(within(createDialog).getByLabelText('Merchant'), 'Netflix')
    await user.selectOptions(within(createDialog).getByLabelText('Category'), 'cat-groceries')
    await user.click(within(createDialog).getByRole('button', { name: /add rule/i }))

    const alert = await within(createDialog).findByRole('alert')
    expect(alert.textContent).toContain('already has a rule')
    expect(mswState.categoryRules).toHaveLength(rulesBefore)
  })

  it('adds, edits and deletes a rule through the dialogs', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Rules />)
    await screen.findByTestId('rule-list')

    await user.click(screen.getByRole('button', { name: /^add rule$/i }))
    const createDialog = await screen.findByTestId('rule-form-dialog')
    await user.type(within(createDialog).getByLabelText('Merchant'), 'Costco')
    await user.selectOptions(within(createDialog).getByLabelText('Category'), 'cat-groceries')
    await user.click(within(createDialog).getByRole('button', { name: /add rule/i }))

    await screen.findByText('COSTCO')
    expect(mswState.categoryRules.some((r) => r.merchant === 'COSTCO')).toBe(true)

    const row = screen.getByText('COSTCO').closest('.ruleRow') as HTMLElement
    await user.click(within(row).getByRole('button', { name: /^edit$/i }))
    const editDialog = await screen.findByTestId('rule-form-dialog')
    const merchantInput = within(editDialog).getByLabelText('Merchant')
    await user.clear(merchantInput)
    await user.type(merchantInput, 'Costco Wholesale')
    await user.click(within(editDialog).getByRole('button', { name: /save/i }))

    await screen.findByText('COSTCO WHOLESALE')

    const updatedRow = screen.getByText('COSTCO WHOLESALE').closest('.ruleRow') as HTMLElement
    await user.click(within(updatedRow).getByRole('button', { name: /^delete$/i }))
    const deleteDialog = await screen.findByTestId('delete-rule-dialog')
    await user.click(within(deleteDialog).getByRole('button', { name: /^delete$/i }))

    expect(screen.queryByText('COSTCO WHOLESALE')).not.toBeInTheDocument()
    expect(mswState.categoryRules.some((r) => r.merchant === 'COSTCO WHOLESALE')).toBe(false)
  })
})
