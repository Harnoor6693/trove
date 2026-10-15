/**
 * Webhook subscriptions — a security surface, behaviour not "it rendered".
 */
import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState, resetMswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import { WebhooksSection } from './WebhooksSection'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

describe('Webhook subscriptions', () => {
  it('shows the SERVER’s own refusal copy for a rejected URL, verbatim', async () => {
    authenticate()
    resetMswState({ webhookUrlRejected: true })
    const user = userEvent.setup()
    renderWithProviders(<WebhooksSection />)

    await screen.findByTestId('webhooks-list')
    await user.click(screen.getByRole('button', { name: 'Add webhook' }))
    const dialog = await screen.findByTestId('create-webhook-dialog')
    await user.type(within(dialog).getByLabelText('Target URL'), 'https://hooks.example.test/x')
    await user.type(within(dialog).getByLabelText('Signing secret'), 'a-long-enough-secret-value')
    await user.click(within(dialog).getByRole('button', { name: 'Create webhook' }))

    const error = await screen.findByTestId('webhook-create-error')
    expect(error).toHaveTextContent('private network range')
  })

  it('reminds the member the secret will not be shown again, from what they typed — never re-rendered from cache', async () => {
    authenticate()
    resetMswState()
    const user = userEvent.setup()
    const { client } = renderWithProviders(<WebhooksSection />)

    await screen.findByTestId('webhooks-list')
    await user.click(screen.getByRole('button', { name: 'Add webhook' }))
    const dialog = await screen.findByTestId('create-webhook-dialog')
    await user.type(within(dialog).getByLabelText('Target URL'), 'https://hooks.example.test/new')
    await user.type(within(dialog).getByLabelText('Signing secret'), 'a-brand-new-secret-value')
    await user.click(within(dialog).getByRole('button', { name: 'Create webhook' }))

    const reminder = await screen.findByTestId('webhook-secret-reminder')
    expect(reminder).toHaveTextContent('will not be shown again')
    // The secret itself is never printed on the page — there is nothing to
    // read it back from, because the server never returned one.
    expect(screen.queryByText('a-brand-new-secret-value')).not.toBeInTheDocument()

    await waitFor(() =>
      expect(mswState.webhooks.some((w) => w.targetUrl === 'https://hooks.example.test/new')).toBe(
        true
      )
    )

    // Regression: the secret must not be reachable from the shared mutation
    // cache either, not merely absent from the page. Passing it as a mutation
    // `variables` value would leave it in `client.getMutationCache()` for the
    // whole gc window after the dialog unmounts.
    const serializedVariables = JSON.stringify(
      client.getMutationCache().getAll().map((m) => m.state.variables)
    )
    expect(serializedVariables).not.toContain('a-brand-new-secret-value')
  })

  it('never renders a secret for an existing subscription — only hasSecret, never the value', async () => {
    authenticate()
    resetMswState()
    renderWithProviders(<WebhooksSection />)

    const list = await screen.findByTestId('webhooks-list')
    expect(list).toHaveTextContent('hooks.example.test/trove')
    // hasSecret is a boolean the server sends; there is no field anywhere on
    // the wire that could contain the real secret to accidentally print.
    expect(list.textContent).not.toMatch(/secret[a-z0-9]{10,}/i)
  })

  it('deleting a subscription asks for confirmation first', async () => {
    authenticate()
    resetMswState()
    const user = userEvent.setup()
    renderWithProviders(<WebhooksSection />)

    const list = await screen.findByTestId('webhooks-list')
    await user.click(within(list).getByRole('button', { name: 'Delete' }))
    const dialog = await screen.findByTestId('delete-webhook-dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(mswState.webhooks).toHaveLength(0))
  })
})
