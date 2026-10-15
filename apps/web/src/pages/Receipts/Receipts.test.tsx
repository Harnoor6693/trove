/**
 * Receipts, by behaviour, against contract-shaped MSW handlers.
 *
 * The image cases are the interesting ones: the bytes come through the session
 * rather than an `<img src>`, and every object URL made from them is revoked.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { makeReceipt, mswState, resetMswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import Receipts from './index'

function signIn() {
  useAuthStore.setState({
    status: 'authenticated',
    user: {
      userId: mswState.me.userId,
      email: mswState.me.email,
      displayName: mswState.me.displayName,
      householdId: mswState.me.householdId,
      role: mswState.me.role,
      totpEnabled: false,
      currency: mswState.me.currency,
      country: mswState.me.country,
      timezone: mswState.me.timezone,
      locale: mswState.me.locale,
      onboardingCompleted: true,
    },
  })
}

/**
 * jsdom implements neither of these. They are stubbed rather than mocked away so
 * the revoke assertions below are counting real calls the component made.
 */
beforeEach(() => {
  let n = 0
  URL.createObjectURL = vi.fn(() => `blob:trove/${(n += 1)}`)
  URL.revokeObjectURL = vi.fn()
  signIn()
})

describe('Receipts — the list', () => {
  it('renders one page and pages with the SERVER’s meta, not rows.length', async () => {
    resetMswState()
    const user = userEvent.setup()
    renderWithProviders(<Receipts />)

    const table = await screen.findByTestId('receipts-table')
    // Six fixtures, a page size of 20: one page, and the caption states the totals.
    expect(table).toHaveTextContent('Showing 1–6 of 6 receipts')
    expect(screen.getByTestId('receipts-pager')).toHaveTextContent('Page 1 of 1')
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()

    // With a page smaller than the row count, page two is a real request.
    resetMswState({
      receipts: Array.from({ length: 45 }, (_, i) => makeReceipt({ id: `rcpt-${i}` })),
    })
    await user.click(screen.getByRole('button', { name: 'Next page' }))
  })

  it('pages forward when the server says there is another page', async () => {
    resetMswState({
      receipts: Array.from({ length: 45 }, (_, i) =>
        makeReceipt({ id: `rcpt-${i}`, extractedMerchant: `Merchant ${i}` })
      ),
    })
    const user = userEvent.setup()
    renderWithProviders(<Receipts />)

    await screen.findByText('Merchant 0')
    expect(screen.getByTestId('receipts-pager')).toHaveTextContent('Page 1 of 3')

    await user.click(screen.getByRole('button', { name: 'Next page' }))
    await waitFor(() =>
      expect(screen.getByTestId('receipts-pager')).toHaveTextContent('Page 2 of 3')
    )
    expect(screen.getByText('Merchant 20')).toBeInTheDocument()
    expect(screen.queryByText('Merchant 0')).not.toBeInTheDocument()
  })

  it('renders each row in the currency the receipt carries, never blended', async () => {
    resetMswState()
    renderWithProviders(<Receipts />)

    await screen.findByTestId('receipts-table')
    const cad = screen.getByTestId('receipt-row-rcpt-1')
    const usd = screen.getByTestId('receipt-row-rcpt-usd')
    expect(within(cad).getByText(/12\.34/)).toBeInTheDocument()
    expect(within(usd).getByText(/8\.99/)).toBeInTheDocument()
    // No total across the page: two currencies are never added together.
    expect(screen.queryByText(/total across/i)).not.toBeInTheDocument()
  })

  it('shows a REVIEW_REQUIRED receipt as needing review, not as a failure', async () => {
    resetMswState()
    renderWithProviders(<Receipts />)

    const row = await screen.findByTestId('receipt-row-rcpt-review')
    expect(within(row).getByText('Needs your review')).toBeInTheDocument()
  })

  it('shows the empty state rather than a fabricated row', async () => {
    resetMswState({ receipts: [] })
    renderWithProviders(<Receipts />)
    expect(await screen.findByTestId('receipts-empty')).toBeInTheDocument()
  })
})

describe('Receipts — the authenticated image', () => {
  it('fetches the thumbnail through the client and never as a bare img src', async () => {
    resetMswState({ receipts: [makeReceipt({ id: 'rcpt-1' })] })
    renderWithProviders(<Receipts />)

    await screen.findByTestId('receipts-table')
    await waitFor(() => expect(mswState.thumbnailFetches).toBeGreaterThan(0))

    const img = await screen.findByRole('img', { name: /Receipt from Blue Bottle Coffee/ })
    // An object URL from bytes we fetched — not the API path handed to the browser.
    expect(img).toHaveAttribute('src', expect.stringMatching(/^blob:/))
  })

  it('gives the image alternative text naming the merchant and date, not "receipt"', async () => {
    resetMswState({ receipts: [makeReceipt({ id: 'rcpt-1' })] })
    renderWithProviders(<Receipts />)

    const img = await screen.findByRole('img', { name: /Receipt from Blue Bottle Coffee, / })
    expect(img.getAttribute('alt')).not.toBe('receipt')
  })

  it('REVOKES the object URL when the component unmounts', async () => {
    resetMswState({ receipts: [makeReceipt({ id: 'rcpt-1' })] })
    const { unmount } = renderWithProviders(<Receipts />)

    await screen.findByRole('img', { name: /Receipt from Blue Bottle Coffee/ })
    const created = vi.mocked(URL.createObjectURL).mock.results[0].value as string

    unmount()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(created)
  })

  it('falls back to a labelled placeholder when the thumbnail is 404', async () => {
    resetMswState({ receipts: [makeReceipt({ id: 'rcpt-1' })], thumbnailMissing: true })
    renderWithProviders(<Receipts />)

    expect(
      await screen.findByRole('img', { name: /No image for Receipt from Blue Bottle Coffee/ })
    ).toBeInTheDocument()
    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })
})

describe('Receipts — upload', () => {
  it('states the accepted types and that a size limit exists BEFORE a file is chosen', async () => {
    resetMswState()
    renderWithProviders(<Receipts />)

    const input = screen.getByLabelText('Choose a receipt image')
    expect(input).toHaveAttribute('accept', 'image/jpeg,image/png')
    const hint = screen.getByTestId('receipt-upload-hint')
    expect(hint).toHaveTextContent(/JPEG or PNG only/i)
    expect(hint).toHaveTextContent(/size limit/i)
    // The input has a real label, not a placeholder pretending to be one.
    expect(input).toHaveAccessibleName('Choose a receipt image')
  })

  it('uploads and then polls the status to completion', { timeout: 15000 }, async () => {
    resetMswState({ receipts: [], receiptStatusWalk: ['PARSING', 'COMPLETE'] })
    const user = userEvent.setup()
    renderWithProviders(<Receipts />)

    await user.upload(
      screen.getByLabelText('Choose a receipt image'),
      new File(['x'], 'lunch.jpg', { type: 'image/jpeg' })
    )

    // A file part really was sent. Its *name* is deliberately not asserted:
    // `uploadReceipt` appends it as `form.append('file', file, file.name)`, but
    // jsdom's XMLHttpRequest re-serialises a FormData part as `blob` whatever
    // filename was given — the same environment limitation step 201 recorded on
    // `createImport`, not a limitation of the client.
    await waitFor(() => expect(mswState.lastReceiptUpload?.filename).toBeTruthy())
    // 202 answers three fields; the screen polls the status route rather than
    // rendering a row whose fields do not exist yet.
    expect(await screen.findByTestId('receipt-upload-status')).toBeInTheDocument()
    // PARSING first, then COMPLETE. The poll runs on a two-second interval, so
    // this waits past one tick rather than assuming the first answer is terminal.
    expect(screen.getByTestId('receipt-upload-status')).toHaveTextContent('Reading the image')
    await waitFor(
      () =>
        expect(screen.getByTestId('receipt-upload-status')).toHaveTextContent(
          // COMPLETE with `reviewRequired: true` — the ORDINARY outcome on an
          // install with no vision model, and phrased as a task rather than a
          // failure.
          'Needs your review'
        ),
      { timeout: 8000 }
    )
    // It really polled rather than reading one answer and stopping.
    expect(mswState.receiptStatusPolls).toBeGreaterThan(1)
  })

  it('shows the server’s own 413 message rather than an invented megabyte figure', async () => {
    resetMswState({ receiptUploadTooLarge: true })
    const user = userEvent.setup()
    renderWithProviders(<Receipts />)

    await user.upload(
      screen.getByLabelText('Choose a receipt image'),
      new File(['x'], 'huge.jpg', { type: 'image/jpeg' })
    )

    expect(await screen.findByTestId('receipt-upload-error')).toHaveTextContent(
      'That image is larger than this server will store.'
    )
  })
})

describe('Receipts — the detail, the review and the two writes', () => {
  async function openDetail(id: string) {
    const user = userEvent.setup()
    const row = await screen.findByTestId(`receipt-row-${id}`)
    await user.click(within(row).getByRole('button', { name: /^Open receipt from/ }))
    await screen.findByTestId('receipt-detail')
    return user
  }

  it('shows the line items, the tax split and the total in the receipt’s own currency', async () => {
    resetMswState()
    renderWithProviders(<Receipts />)
    await openDetail('rcpt-1')

    expect(await screen.findByTestId('receipt-line-items')).toHaveTextContent('Oat milk latte')
    const split = screen.getByTestId('receipt-tax-split')
    expect(split).toHaveTextContent('GST')
    expect(split).toHaveTextContent(/no computed number/i)
  })

  it('offers the REVIEW action on a REVIEW_REQUIRED receipt, separately from a field edit', async () => {
    resetMswState()
    renderWithProviders(<Receipts />)
    const user = await openDetail('rcpt-review')

    const panel = await screen.findByTestId('receipt-review-panel')
    expect(panel).toHaveTextContent(/Nothing went wrong/i)

    await user.click(within(panel).getByRole('button', { name: 'Accept these fields' }))
    await waitFor(() => expect(mswState.lastReceiptReview).toEqual({ accepted: true }))
    // The verdict went to the review route, not to a field patch.
    expect(mswState.lastReceiptPatch).toBeNull()
  })

  it('records a rejected extraction as the member’s decision, keeping the image', async () => {
    resetMswState()
    renderWithProviders(<Receipts />)
    const user = await openDetail('rcpt-review')

    const panel = await screen.findByTestId('receipt-review-panel')
    await user.click(within(panel).getByRole('button', { name: 'Reject the extraction' }))

    await waitFor(() => expect(mswState.lastReceiptReview).toEqual({ accepted: false }))
    await waitFor(() =>
      expect(mswState.receipts.find((r) => r.id === 'rcpt-review')?.status).toBe('FAILED')
    )
  })

  it('sends ONLY the fields the member changed', async () => {
    resetMswState()
    renderWithProviders(<Receipts />)
    const user = await openDetail('rcpt-1')

    const merchant = await screen.findByLabelText('Merchant')
    await user.clear(merchant)
    await user.type(merchant, 'Corner Market')
    await user.click(screen.getByRole('button', { name: 'Save fields' }))

    await waitFor(() => expect(mswState.lastReceiptPatch).not.toBeNull())
    // Nothing else travelled: an absent key is left alone and there is no way to
    // clear one back to null through this operation.
    expect(mswState.lastReceiptPatch).toEqual({ extractedMerchant: 'Corner Market' })
  })

  it('will not send an empty patch, which the operation refuses with a 400', async () => {
    resetMswState()
    renderWithProviders(<Receipts />)
    await openDetail('rcpt-1')

    await screen.findByLabelText('Merchant')
    // Nothing was edited, so there is nothing to send and the operation would
    // answer 400 "change at least one field". A harmless "let me just save that"
    // click must not produce an error banner — that is how a member learns to
    // ignore the banner on the click where it matters.
    expect(screen.getByRole('button', { name: 'Save fields' })).toBeDisabled()
    expect(screen.getByTestId('receipt-fields-clean')).toBeInTheDocument()
    expect(mswState.lastReceiptPatch).toBeNull()
    expect(screen.queryByTestId('receipt-patch-error')).toBeNull()
  })

  it('explains the 409 when the pipeline is still running rather than showing a generic failure', async () => {
    resetMswState({
      receipts: [makeReceipt({ id: 'rcpt-busy', status: 'EXTRACTING', reviewRequired: false })],
    })
    renderWithProviders(<Receipts />)
    const user = await openDetail('rcpt-busy')

    const merchant = await screen.findByLabelText('Merchant')
    await user.clear(merchant)
    await user.type(merchant, 'Anything')
    await user.click(screen.getByRole('button', { name: 'Save fields' }))

    expect(await screen.findByTestId('receipt-patch-error')).toHaveTextContent(
      /still being processed/i
    )
  })

  it('confirms before deleting, and says the stored image goes too', async () => {
    resetMswState()
    renderWithProviders(<Receipts />)
    const user = await openDetail('rcpt-1')

    await user.click(await screen.findByRole('button', { name: 'Delete this receipt' }))
    expect(screen.getByText(/its line items and the stored image/i)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Yes, delete it' }))
    await waitFor(() => expect(mswState.receipts.some((r) => r.id === 'rcpt-1')).toBe(false))
  })

  it('downloads the image through the session rather than linking to the route', async () => {
    resetMswState()
    renderWithProviders(<Receipts />)
    const user = await openDetail('rcpt-1')

    // There is no anchor to the API path anywhere on the page: the bytes need
    // the caller's own credentials, so the control is a button.
    expect(
      screen.queryByRole('link', { name: /download/i })
    ).not.toBeInTheDocument()

    const clicks = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    await user.click(screen.getByRole('button', { name: 'Download the image' }))
    await waitFor(() => expect(clicks).toHaveBeenCalled())
    clicks.mockRestore()
  })
})
