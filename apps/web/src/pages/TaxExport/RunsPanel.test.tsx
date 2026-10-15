/**
 * Tax export runs — behaviour, not "it rendered".
 *
 * The download is the interesting case: the bytes come through the session
 * rather than a link, and every object URL made from them is revoked. A run
 * that is not `COMPLETE` answers 409 and must read as "still generating",
 * never as a failure.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState, resetMswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import { RunsPanel } from './RunsPanel'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

beforeEach(() => {
  let n = 0
  URL.createObjectURL = vi.fn(() => `blob:trove/${(n += 1)}`)
  URL.revokeObjectURL = vi.fn()
  authenticate()
})

describe('Tax export runs', () => {
  it(
    'polls a run to COMPLETE and only then offers a download',
    { timeout: 15000 },
    async () => {
      resetMswState({
        taxExportRuns: [
          {
            id: 'run-poll',
            userId: mswState.me.userId,
            taxYear: 2025,
            jurisdiction: 'CA',
            format: 'CSV',
            status: 'GENERATING',
            fileSizeBytes: null,
            errorMessage: null,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
          },
        ],
        taxExportPollsRemaining: 1,
      })
      renderWithProviders(<RunsPanel />)

      const table = await screen.findByTestId('runs-table')
      expect(table).toHaveTextContent('Generating')
      expect(table).not.toHaveTextContent('Ready')

      // The list polls every three seconds while a row is not terminal; the
      // fixture's poll budget flips it to COMPLETE on the second fetch.
      await waitFor(() => expect(table).toHaveTextContent('Ready'), { timeout: 10000 })
    }
  )

  it('downloads a completed run through the authenticated client and revokes the object URL', async () => {
    resetMswState({
      taxExportRuns: [
        {
          id: 'run-done',
          userId: mswState.me.userId,
          taxYear: 2025,
          jurisdiction: 'CA',
          format: 'CSV',
          status: 'COMPLETE',
          fileSizeBytes: 4096,
          errorMessage: null,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      ],
      taxExportPollsRemaining: 0,
    })
    const user = userEvent.setup()
    renderWithProviders(<RunsPanel />)

    // There is no anchor to the API path anywhere: the bytes need the
    // caller's own credentials, so the control is a button.
    expect(screen.queryByRole('link', { name: /download/i })).not.toBeInTheDocument()

    const table = await screen.findByTestId('runs-table')
    const clicks = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    await user.click(within(table).getByRole('button', { name: 'Download' }))

    await waitFor(() => expect(clicks).toHaveBeenCalled())
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled())
    const created = vi.mocked(URL.createObjectURL).mock.results[0].value as string
    await waitFor(() => expect(URL.revokeObjectURL).toHaveBeenCalledWith(created))
    clicks.mockRestore()
  })

  it('a download of a non-complete run shows "still generating", never a failure', async () => {
    resetMswState({
      taxExportRuns: [
        {
          id: 'run-generating',
          userId: mswState.me.userId,
          taxYear: 2024,
          jurisdiction: 'CA',
          format: 'PDF',
          status: 'GENERATING',
          fileSizeBytes: null,
          errorMessage: null,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      ],
      // A high budget so the fixture does not auto-complete mid-test.
      taxExportPollsRemaining: 999,
    })
    const user = userEvent.setup()
    renderWithProviders(<RunsPanel />)

    const table = await screen.findByTestId('runs-table')
    await user.click(within(table).getByRole('button', { name: 'Download' }))

    const status = await screen.findByTestId('download-still-generating')
    expect(status).toHaveTextContent('still generating')
    expect(screen.queryByTestId('download-error')).not.toBeInTheDocument()
  })

  it('deletes a run and its file together', async () => {
    resetMswState({ taxExportPollsRemaining: 0 })
    const user = userEvent.setup()
    renderWithProviders(<RunsPanel />)

    const table = await screen.findByTestId('runs-table')
    await user.click(within(table).getAllByRole('button', { name: 'Delete' })[0])
    const dialog = await screen.findByTestId('delete-run-dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(mswState.taxExportRuns).toHaveLength(2))
  })

  it('a FAILED run shows its own errorMessage and offers no Download button — it will never become COMPLETE', async () => {
    // The default fixture already has `run-failed` (status FAILED,
    // errorMessage 'Export generation could not be queued'). PENDING and
    // GENERATING also answer 409 on download, but only FAILED is permanent —
    // reading it as "still generating" would be permanently misleading.
    resetMswState({ taxExportPollsRemaining: 0 })
    renderWithProviders(<RunsPanel />)

    const table = await screen.findByTestId('runs-table')
    const failedRow = within(table).getByText('Failed').closest('tr')
    expect(failedRow).not.toBeNull()
    expect(within(failedRow as HTMLElement).queryByRole('button', { name: 'Download' })).not.toBeInTheDocument()
    expect(failedRow).toHaveTextContent('Export generation could not be queued')
  })
})
