/**
 * Categories — behaviour, not "it rendered".
 */
import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import Categories from './Categories'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

describe('Categories', () => {
  it('renders the tree with the household child nested under its parent', async () => {
    authenticate()
    renderWithProviders(<Categories />)

    const tree = await screen.findByTestId('category-tree')
    expect(tree.textContent).toContain('Groceries')
    expect(tree.textContent).toContain('Hobbies')
    expect(tree.textContent).toContain('Golf')

    // The parent has a toggle (it has a child); Groceries, a leaf, does not.
    const parentToggle = screen.getByRole('button', { name: /hobbies subcategories/i })
    expect(parentToggle).toHaveAttribute('aria-expanded', 'true')
  })

  it('offers no rename or delete on a system category', async () => {
    authenticate()
    renderWithProviders(<Categories />)
    const tree = await screen.findByTestId('category-tree')

    // "Groceries" is a system row (isSystem: true) — no destructive action is
    // offered on it at all, rather than a button that would always 403.
    const groceriesRow = within(tree).getByText('Groceries').closest('.treeRow') as HTMLElement
    expect(within(groceriesRow).queryByRole('button', { name: /rename/i })).not.toBeInTheDocument()
    expect(within(groceriesRow).queryByRole('button', { name: /^delete$/i })).not.toBeInTheDocument()
  })

  it('offers rename and delete on a household category', async () => {
    authenticate()
    renderWithProviders(<Categories />)
    const tree = await screen.findByTestId('category-tree')

    const hobbiesRow = within(tree).getByText('Hobbies').closest('.treeRow') as HTMLElement
    expect(within(hobbiesRow).getByRole('button', { name: /rename/i })).toBeInTheDocument()
    expect(within(hobbiesRow).getByRole('button', { name: /^delete$/i })).toBeInTheDocument()
  })

  it('renames a household category through the dialog', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Categories />)
    const tree = await screen.findByTestId('category-tree')

    const hobbiesRow = within(tree).getByText('Hobbies').closest('.treeRow') as HTMLElement
    await user.click(within(hobbiesRow).getByRole('button', { name: /rename/i }))

    const dialog = await screen.findByTestId('category-form-dialog')
    const nameInput = within(dialog).getByLabelText('Name')
    await user.clear(nameInput)
    await user.type(nameInput, 'Golfing and hobbies')
    await user.click(within(dialog).getByRole('button', { name: /save/i }))

    await screen.findByText('Golfing and hobbies')
    expect(mswState.categories.find((c) => c.id === 'cat-household-parent')?.name).toBe(
      'Golfing and hobbies'
    )
  })

  it('shows the 409 detail when deleting a category that is still used by a rule', async () => {
    authenticate()
    mswState.categoryDeleteConflict = true
    const user = userEvent.setup()
    renderWithProviders(<Categories />)
    const tree = await screen.findByTestId('category-tree')

    const hobbiesRow = within(tree).getByText('Hobbies').closest('.treeRow') as HTMLElement
    await user.click(within(hobbiesRow).getByRole('button', { name: /^delete$/i }))
    const dialog = await screen.findByTestId('delete-category-dialog')
    await user.click(within(dialog).getByRole('button', { name: /^delete$/i }))

    const alert = await screen.findByTestId('delete-category-error')
    expect(alert.textContent).toContain('still used by one of your merchant rules')
    // The category is still there — the delete was refused, not silently retried.
    expect(mswState.categories.some((c) => c.id === 'cat-household-parent')).toBe(true)
  })

  it('deletes a household category that is not in use', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Categories />)
    const tree = await screen.findByTestId('category-tree')

    const golfRow = within(tree).getByText('Golf').closest('.treeRow') as HTMLElement
    await user.click(within(golfRow).getByRole('button', { name: /^delete$/i }))
    const dialog = await screen.findByTestId('delete-category-dialog')
    await user.click(within(dialog).getByRole('button', { name: /^delete$/i }))

    await waitFor(() => expect(screen.queryByText('Golf')).not.toBeInTheDocument())
    expect(mswState.categories.some((c) => c.id === 'cat-household-child')).toBe(false)
  })

  it('creates a household category', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Categories />)
    await screen.findByTestId('category-tree')

    await user.click(screen.getByRole('button', { name: /add category/i }))
    const dialog = await screen.findByTestId('category-form-dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Streaming')
    await user.click(within(dialog).getByRole('button', { name: /add category/i }))

    await screen.findByText('Streaming')
    expect(mswState.categories.some((c) => c.name === 'Streaming')).toBe(true)
  })
})
