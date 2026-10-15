/**
 * The admin console — the role gate, the refusals that are not errors, the
 * audit pager, and the one-time reset link.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState, resetMswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import { AdminSection } from './AdminSection'

function signIn(role: 'ADMIN' | 'MEMBER') {
  resetMswState({ me: { ...DEFAULT_ME, role } })
  useAuthStore.setState({
    status: 'authenticated',
    user: { ...DEFAULT_ME, role },
    hydrationError: null,
  })
}

beforeEach(() => signIn('ADMIN'))

describe('Admin console', () => {
  it('shows a MEMBER a clean explanation, not a broken page', async () => {
    signIn('MEMBER')
    renderWithProviders(<AdminSection />)

    const notice = await screen.findByTestId('admin-forbidden')
    expect(notice).toHaveAttribute('role', 'status')
    expect(notice).toHaveTextContent(/only an administrator of this household/i)
    // No member table, no audit table, nothing half-rendered.
    expect(screen.queryByTestId('members-table')).not.toBeInTheDocument()
    expect(screen.queryByTestId('audit-table')).not.toBeInTheDocument()
  })

  it('says only what `active` says about an inactive member, not what it cannot know', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AdminSection />)
    const table = await screen.findByTestId('members-table')
    expect(table).toHaveTextContent('second@example.test')
    expect(table).toHaveTextContent('departed@example.test')
    expect(table).toHaveTextContent(/cannot sign in/i)
    // `active: false` is BOTH "removed, inside the grace" and "merely
    // deactivated", and `AdminUserResponse` carries nothing to tell them apart.
    // A badge promising seven days would be a guess, and a wrong one for the
    // second case — whose Restore is a guaranteed 409.
    expect(table).not.toHaveTextContent(/restorable for seven days/i)

    // The grace window is stated where it is true and where the refusal lands.
    const rows = within(table).getAllByRole('row')
    const removedRow = rows.find((r) => /departed@example.test/.test(r.textContent ?? ''))!
    await user.click(within(removedRow).getByRole('button', { name: /restore/i }))
    const dialog = await screen.findByTestId('member-action-dialog')
    expect(dialog).toHaveTextContent(/removed within the last seven days/i)
    expect(dialog).toHaveTextContent(/deactivated rather than removed/i)
  })

  it('does not offer the two self-target actions on the admin’s own row, and says why', async () => {
    renderWithProviders(<AdminSection />)
    const table = await screen.findByTestId('members-table')
    const rows = within(table).getAllByRole('row')
    // Row 0 is the header; row 1 is the admin themself (the list is oldest first).
    const ownRow = rows[1]
    expect(within(ownRow).queryByRole('button', { name: /clear lock/i })).not.toBeInTheDocument()
    expect(
      within(ownRow).queryByRole('button', { name: /remove two-factor/i })
    ).not.toBeInTheDocument()
    // And the reason is stated rather than left to be discovered as a 403.
    expect(
      screen.getByText(/will not let you clear your own sign-in lock or your own second factor/i)
    ).toBeInTheDocument()
  })

  it('renders a restore outside the grace as the server’s own 409 explanation', async () => {
    resetMswState({ me: { ...DEFAULT_ME, role: 'ADMIN' }, restoreOutsideGrace: true })
    const user = userEvent.setup()
    renderWithProviders(<AdminSection />)

    const table = await screen.findByTestId('members-table')
    await user.click(within(table).getByRole('button', { name: /restore/i }))
    const dialog = await screen.findByTestId('member-action-dialog')
    await user.click(within(dialog).getByRole('button', { name: /restore member/i }))

    const message = await screen.findByTestId('member-action-error')
    expect(message).toHaveAttribute('role', 'status')
    expect(message).toHaveTextContent(/past its restore window/i)
  })

  it('renders the last-admin 409 on a member removal as an explanation', async () => {
    resetMswState({ me: { ...DEFAULT_ME, role: 'ADMIN' }, lastAdminConflict: true })
    const user = userEvent.setup()
    renderWithProviders(<AdminSection />)

    const table = await screen.findByTestId('members-table')
    const rows = within(table).getAllByRole('row')
    await user.click(within(rows[2]).getByRole('button', { name: /^remove$/i }))
    const dialog = await screen.findByTestId('member-action-dialog')
    await user.click(within(dialog).getByRole('button', { name: /remove member/i }))

    const message = await screen.findByTestId('member-action-error')
    expect(message).toHaveAttribute('role', 'status')
    expect(message).toHaveTextContent(/without an administrator/i)
  })

  it('says what clearing a second factor really does before it does it', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AdminSection />)

    const table = await screen.findByTestId('members-table')
    await user.click(within(table).getByRole('button', { name: /remove two-factor/i }))
    const dialog = await screen.findByTestId('member-action-dialog')
    expect(dialog).toHaveTextContent(/lost the phone/i)
    expect(dialog).toHaveTextContent(/protected by its password alone/i)
    expect(dialog).toHaveTextContent(/records that YOU removed it/i)

    await user.click(within(dialog).getByRole('button', { name: /remove second factor/i }))
    await waitFor(() =>
      expect(mswState.members.find((m) => m.id === 'member-2')?.totpEnabled).toBe(false)
    )
  })

  it('mints a reset link, shows it once and warns that it will not be shown again', async () => {
    const user = userEvent.setup()
    const { client } = renderWithProviders(<AdminSection />)

    const table = await screen.findByTestId('members-table')
    const rows = within(table).getAllByRole('row')
    await user.click(within(rows[2]).getByRole('button', { name: /reset link/i }))
    const dialog = await screen.findByTestId('reset-link-dialog')
    expect(dialog).toHaveTextContent(/no mail server/i)
    await user.click(within(dialog).getByRole('button', { name: /create link/i }))

    const shown = await screen.findByTestId('reset-link-shown')
    // The link panel replaces the form and unmounts the button that had focus,
    // so it has to take focus itself: the admin has exactly one chance to read
    // and copy what is in it.
    await waitFor(() => expect(shown.contains(document.activeElement)).toBe(true))
    expect(shown).toHaveTextContent(/only time trove will show you this link/i)
    expect(screen.getByTestId('reset-link-url')).toHaveTextContent(
      'https://trove.test/reset-password/good-reset-token'
    )
    expect(mswState.lastResetLinkRequest).toEqual({ email: 'second@example.test' })

    // "Shown once and never cached" has to hold for the MUTATION cache, where a
    // settled mutation's response data would otherwise outlive the dialog by the
    // gc window. This link sets somebody else's password: it goes when the
    // dialog goes.
    await user.click(within(shown).getByRole('button', { name: /done/i }))
    await waitFor(() => expect(screen.queryByTestId('reset-link-shown')).not.toBeInTheDocument())
    await waitFor(() => {
      const serialised = JSON.stringify(
        client
          .getMutationCache()
          .getAll()
          .map((m) => m.state)
      )
      expect(serialised).not.toContain('good-reset-token')
    })
    expect(document.body.innerHTML).not.toContain('good-reset-token')
  })

  it('writes the registration flag through PUT /v1/admin/settings', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AdminSection />)

    const toggle = await screen.findByRole('checkbox', { name: /anyone may sign up/i })
    expect(toggle).not.toBeChecked()
    await user.click(toggle)

    await waitFor(() => expect(mswState.lastAdminSettingsBody).toEqual({ registrationOpen: true }))
    await waitFor(() => expect(toggle).toBeChecked())
  })

  it('turns the scheduled bank sync on through PUT /v1/admin/settings, off by default', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AdminSection />)

    const toggle = await screen.findByRole('checkbox', { name: /sync connected banks every day/i })
    expect(toggle).not.toBeChecked()
    expect(screen.getByTestId('scheduled-sync-note')).toHaveTextContent(
      /SimpleFIN.*once a day.*only once/i
    )

    await user.click(toggle)

    await waitFor(() =>
      expect(mswState.lastAdminSettingsBody).toEqual({ scheduledSyncEnabled: true })
    )
    await waitFor(() => expect(toggle).toBeChecked())
    expect(mswState.adminSettings.registrationOpen).toBe(false)
  })

  it('pages the audit log and renders a row’s metadata as text, never as markup', async () => {
    resetMswState({
      me: { ...DEFAULT_ME, role: 'ADMIN' },
      auditEntries: Array.from({ length: 25 }, (_, i) => ({
        id: `audit-${i}`,
        method: 'POST' as const,
        path: i === 0 ? '/api/v1/<img src=x onerror="alert(1)">/*' : `/api/v1/thing/${i}`,
        status: 200,
        userId: null,
        clientIp: null,
        requestId: `req-${i}`,
        durationMs: i,
        at: '2026-09-04T18:30:00Z',
      })),
    })
    const user = userEvent.setup()
    renderWithProviders(<AdminSection />)

    const table = await screen.findByTestId('audit-table')
    // The angle brackets are TEXT. If any of this were interpreted as markup
    // there would be an <img> in the document and the text would be gone.
    expect(table).toHaveTextContent('/api/v1/<img src=x onerror="alert(1)">/*')
    expect(within(table).queryByRole('img')).not.toBeInTheDocument()
    expect(table).toHaveTextContent('anonymous')
    expect(table).toHaveTextContent('not recorded')

    expect(screen.getByTestId('audit-page')).toHaveTextContent('Page 1 of 2')
    await user.click(screen.getByRole('button', { name: /next/i }))
    await waitFor(() => expect(screen.getByTestId('audit-page')).toHaveTextContent('Page 2 of 2'))
    await waitFor(() =>
      expect(screen.getByTestId('audit-table')).toHaveTextContent('/api/v1/thing/24')
    )
  })
})
