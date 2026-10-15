/**
 * The import wizard, driven end to end from the Accounts page it lives on —
 * which also proves the flow is reachable from an account row rather than from
 * a route of its own.
 */
import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { renderWithProviders } from '@/test/test-utils'
import { server } from '@/test/mswServer'
import { DEFAULT_ME, fail, mswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import Accounts from './index'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

function csvFile() {
  return new File(['Date,Description,Amount\n03/09/2026,GROCERY STORE,42.50\n'], 'statement.csv', {
    type: 'text/csv',
  })
}

/** Opens the wizard on the CSV account and uploads a file, landing on the preview. */
async function openPreview(user: ReturnType<typeof userEvent.setup>) {
  renderWithProviders(<Accounts />)
  await user.click(await screen.findByRole('button', { name: /import a file into us savings/i }))
  const wizard = await screen.findByTestId('import-wizard')
  await user.upload(within(wizard).getByLabelText(/statement file/i), csvFile())
  await user.click(within(wizard).getByRole('button', { name: /preview import/i }))
  await screen.findByTestId('import-counts')
  return wizard
}

describe('ImportWizard', () => {
  it('uploads against the account it was opened from and previews without writing anything', async () => {
    authenticate()
    const user = userEvent.setup()
    await openPreview(user)

    // The parts the client decides: the account the wizard was opened from, and
    // the format sniff narrowed to that account's own kind.
    expect(mswState.lastImportUpload?.accountId).toBe('acct-csv')
    expect(mswState.lastImportUpload?.sourceKind).toBe('CSV')
    // A file part really was sent. Its *name* is deliberately not asserted:
    // `createImport` appends it as `form.append('file', file, file.name)`, but
    // jsdom's XMLHttpRequest re-serialises a FormData part as `blob` regardless
    // of the filename given — verified directly against this stack, and a
    // limitation of the test environment rather than of the client.
    expect(mswState.lastImportUpload?.filename).toBeTruthy()
    // POST /v1/imports writes no transaction row: the batch is still PREVIEW.
    expect(mswState.imports['import-1']?.status).toBe('PREVIEW')
  })

  it('shows what committing would do, per verdict, before it happens', async () => {
    authenticate()
    const user = userEvent.setup()
    await openPreview(user)

    expect(screen.getByTestId('import-count-new').textContent).toBe('2')
    expect(screen.getByTestId('import-count-duplicate').textContent).toBe('1')
    expect(screen.getByTestId('import-count-fuzzy').textContent).toBe('0')
    expect(screen.getByTestId('import-count-invalid').textContent).toBe('1')

    const rows = screen.getByTestId('import-rows')
    expect(rows.textContent).toContain('Will be added')
    expect(rows.textContent).toContain('Already in Trove — skipped')
    expect(rows.textContent).toContain('Cannot be read — rejected')
    // The rejected row says WHY, and the server never quotes the row itself.
    expect(rows.textContent).toContain('The date cell could not be read.')
  })

  it('renders preview amounts in the file’s own currency, not the household’s', async () => {
    authenticate()
    const user = userEvent.setup()
    await openPreview(user)

    // The household reports in CAD; this file is USD and every row says so.
    const rows = screen.getByTestId('import-rows')
    const usd = rows.querySelectorAll('[data-currency="USD"]')
    expect(usd.length).toBeGreaterThan(0)
    expect(rows.querySelectorAll('[data-currency="CAD"]').length).toBe(0)
    expect(Array.from(usd).some((el) => el.textContent?.includes('42.50'))).toBe(true)
  })

  it('refuses to commit until an ambiguous date format is resolved, then sends the choice', async () => {
    authenticate()
    const user = userEvent.setup()
    const wizard = await openPreview(user)

    const commitButton = within(wizard).getByRole('button', { name: /import 2 transactions/i })
    // The sniffer could not choose between dd/MM and MM/dd, so nothing is guessed.
    expect(commitButton).toBeDisabled()
    expect(screen.getByTestId('import-date-format').textContent).toContain('will not guess')

    await user.click(screen.getByRole('radio', { name: /dd\/MM\/yyyy/ }))
    expect(commitButton).toBeEnabled()
    await user.click(commitButton)

    await waitFor(() => expect(mswState.lastCommitBody).not.toBeNull())
    // Only the correction travels: fields the body omits keep their detected values.
    expect(mswState.lastCommitBody).toEqual({ mapping: { dateFormat: 'dd/MM/yyyy' } })
    expect(mswState.imports['import-1']?.status).toBe('COMMITTED')
  })

  /**
   * `commitImport` is the only route that takes a mapping, and
   * `CsvColumnMappingRequest` corrects any column — not just the date format.
   * With no control for the rest, a sniffer that misread the amount column left
   * the member no recourse inside Trove at all.
   */
  it('sends a corrected column, not only the date format', async () => {
    authenticate()
    const user = userEvent.setup()
    const wizard = await openPreview(user)

    const amount = within(wizard).getByLabelText(/amount column/i)
    expect(amount).toHaveValue('Amount') // what the sniffer detected
    await user.clear(amount)
    await user.type(amount, 'Transaction Amount')
    await user.click(screen.getByRole('radio', { name: /dd\/MM\/yyyy/ }))
    await user.click(within(wizard).getByRole('button', { name: /import 2 transactions/i }))

    await waitFor(() => expect(mswState.lastCommitBody).not.toBeNull())
    // The correction and the format, and nothing else: an untouched column keeps
    // its detected value by being absent from the body.
    expect(mswState.lastCommitBody).toEqual({
      mapping: { amountColumn: 'Transaction Amount', dateFormat: 'dd/MM/yyyy' },
    })
  })

  it('keeps undo reachable after the wizard has been closed', async () => {
    authenticate()
    const user = userEvent.setup()
    const wizard = await openPreview(user)

    await user.click(screen.getByRole('radio', { name: /dd\/MM\/yyyy/ }))
    await user.click(within(wizard).getByRole('button', { name: /import 2 transactions/i }))
    await screen.findByTestId('import-done')

    // Close the modal — the affordance must survive it, or a member who closes
    // the wizard can never take the rows back out.
    await user.click(screen.getByRole('button', { name: /^done$/i }))
    const card = await screen.findByTestId('committed-imports')
    expect(card.textContent).toContain('US Savings')
    expect(card.textContent).toContain('2 added')
    expect(card.textContent).toContain('1 skipped')

    await user.click(within(card).getByRole('button', { name: /undo this import/i }))
    await waitFor(() => expect(mswState.imports['import-1']?.status).toBe('UNDONE'))
    expect(await within(card).findByText('Undone')).toBeInTheDocument()
  })

  it('renders a 413 as the upload refusal it is, and imports nothing', async () => {
    authenticate()
    mswState.uploadTooLarge = true
    const user = userEvent.setup()
    renderWithProviders(<Accounts />)

    await user.click(await screen.findByRole('button', { name: /import a file into us savings/i }))
    const wizard = await screen.findByTestId('import-wizard')
    await user.upload(within(wizard).getByLabelText(/statement file/i), csvFile())
    await user.click(within(wizard).getByRole('button', { name: /preview import/i }))

    const alert = await within(wizard).findByRole('alert')
    expect(alert.textContent).toContain('larger than this server will store')
    expect(screen.queryByTestId('import-counts')).not.toBeInTheDocument()
    expect(Object.keys(mswState.imports)).toHaveLength(0)
  })

  it('refuses a second commit of the same batch rather than silently repeating it', async () => {
    authenticate()
    const user = userEvent.setup()
    const wizard = await openPreview(user)

    server.use(
      http.post('*/v1/imports/:id/commit', () =>
        fail(409, 'conflict', 'Conflict', { detail: 'This import has already been committed.' })
      )
    )
    await user.click(screen.getByRole('radio', { name: /dd\/MM\/yyyy/ }))
    await user.click(within(wizard).getByRole('button', { name: /import 2 transactions/i }))

    const alert = await within(wizard).findByRole('alert')
    expect(alert.textContent).toContain('already been committed')
    expect(screen.queryByTestId('import-done')).not.toBeInTheDocument()
  })

  it('is a real dialog: it takes focus on open and gives it back on close', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Accounts />)

    const trigger = await screen.findByRole('button', { name: /import a file into us savings/i })
    trigger.focus()
    await user.click(trigger)

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true))

    // Escape closes it, and the member is put back where they were.
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByTestId('import-wizard')).not.toBeInTheDocument())
    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })
})
