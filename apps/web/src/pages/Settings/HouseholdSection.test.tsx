/**
 * Household invites — the recorded debt (handoff 15 §8): `Invite.token` and
 * `Invite.url` are NULL once the invitation is accepted, revoked or expired,
 * and this list used to render that null as the literal text "null" beside a
 * Revoke button for a link that no longer existed.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, resetMswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import { HouseholdSection } from './index'

function signIn(role: 'ADMIN' | 'MEMBER') {
  resetMswState({ me: { ...DEFAULT_ME, role } })
  useAuthStore.setState({
    status: 'authenticated',
    user: { ...DEFAULT_ME, role },
    hydrationError: null,
  })
}

beforeEach(() => signIn('ADMIN'))

describe('Household invites', () => {
  it('shows a spent invite as a state, with no link text and nothing left to revoke', async () => {
    const user = userEvent.setup()
    renderWithProviders(<HouseholdSection />)

    await user.click(await screen.findByRole('button', { name: /create invite link/i }))

    const live = await screen.findByText(/https:\/\/trove\.test\/invite\//)
    const row = live.closest('li')!
    await user.click(within(row).getByRole('button', { name: /revoke/i }))

    // The server nulls `token`/`url` and keeps the row in the listing.
    const spent = await screen.findByTestId('invite-spent')
    expect(spent).toHaveTextContent(/no longer usable — revoked or expired/i)
    // The defect itself: neither the link nor the literal "null" is on screen…
    expect(screen.queryByText(/https:\/\/trove\.test\/invite\//)).not.toBeInTheDocument()
    expect(screen.queryByText(/\bnull\b/)).not.toBeInTheDocument()
    // …and a link that no longer exists is not offered for revoking again.
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /^revoke$/i })).not.toBeInTheDocument()
    )
  })

  it('does not ask a MEMBER for invites at all — the route is admin-only', async () => {
    signIn('MEMBER')
    renderWithProviders(<HouseholdSection />)

    expect(
      await screen.findByText(/only an administrator can invite new members/i)
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /create invite link/i })).not.toBeInTheDocument()
  })
})
