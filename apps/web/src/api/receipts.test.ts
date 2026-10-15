/**
 * The receipts API layer, at the level the screens do not exercise: the `meta`
 * guard, the terminal-status rule, the authenticated binary fetch, and the
 * server-supplied `thumbnailUrl` — which is text from a response that ends up in
 * a request, and is therefore checked rather than trusted.
 */
import { describe, expect, it } from 'vitest'
import {
  downloadReceipt,
  fetchReceiptThumbnail,
  isTerminalReceiptStatus,
  listReceipts,
  thumbnailPathFor,
} from './receipts'
import { filenameFromDisposition } from './binary'
import { makeReceipt, mswState, resetMswState } from '@/test/msw/handlers'

describe('listReceipts', () => {
  it('returns the page AND the meta the operation requires', async () => {
    resetMswState()
    const page = await listReceipts({ page: 0, limit: 2 })
    expect(page.rows).toHaveLength(2)
    // The totals describe the whole collection, not the page in hand.
    expect(page.meta).toEqual({ total: 6, page: 0, limit: 2 })
  })

  it('raises the typed slug for a refused query rather than showing page zero', async () => {
    resetMswState()
    await expect(listReceipts({ page: 0, limit: 500 })).rejects.toMatchObject({
      status: 400,
      code: 'validation-error',
    })
  })
})

describe('isTerminalReceiptStatus', () => {
  it('treats COMPLETE and FAILED as terminal and every stage as in flight', () => {
    expect(isTerminalReceiptStatus('COMPLETE')).toBe(true)
    expect(isTerminalReceiptStatus('FAILED')).toBe(true)
    for (const stage of [
      'UPLOADED',
      'VALIDATING',
      'PARSING',
      'EXTRACTING',
      'MATCHING',
      'TAX_SPLIT',
      'SCORING',
    ] as const) {
      expect(isTerminalReceiptStatus(stage)).toBe(false)
    }
  })
})

describe('thumbnailPathFor — server text that ends up in a request', () => {
  it('strips the API prefix so the client’s own baseURL is not doubled', () => {
    const receipt = makeReceipt({ id: 'r1' })
    expect(receipt.thumbnailUrl).toBe('/api/v1/receipts/r1/thumbnail')
    expect(thumbnailPathFor(receipt)).toBe('/v1/receipts/r1/thumbnail')
  })

  it('passes through a path that is already relative to the API root', () => {
    expect(thumbnailPathFor(makeReceipt({ id: 'r1', thumbnailUrl: '/v1/receipts/r1/thumbnail' }))).toBe(
      '/v1/receipts/r1/thumbnail'
    )
  })

  it('is null when the receipt has no thumbnail row', () => {
    expect(thumbnailPathFor(makeReceipt({ id: 'r1', thumbnailUrl: null }))).toBeNull()
  })

  it('REFUSES anything that is not a path on this API and uses the documented one', () => {
    // The operation states this is never an absolute URL, never presigned and
    // never a data URI. A response that says otherwise is not followed.
    for (const hostile of [
      'https://elsewhere.test/steal',
      '//elsewhere.test/steal',
      'data:image/jpeg;base64,AAAA',
      '/v2/somewhere-else',
    ]) {
      expect(thumbnailPathFor(makeReceipt({ id: 'r1', thumbnailUrl: hostile }))).toBe(
        '/v1/receipts/r1/thumbnail'
      )
    }
  })
})

describe('the authenticated binary fetch', () => {
  it('fetches the thumbnail bytes through the session, with the declared type', async () => {
    resetMswState()
    const file = await fetchReceiptThumbnail('rcpt-1')
    expect(mswState.thumbnailFetches).toBe(1)
    expect(file.contentType).toContain('image/jpeg')
    expect(file.blob.size).toBeGreaterThan(0)
    // A thumbnail is meant to be rendered, so the route sends no disposition.
    expect(file.filename).toBeNull()
  })

  it('reads the server-built filename off the download’s Content-Disposition', async () => {
    resetMswState()
    const file = await downloadReceipt('rcpt-1')
    expect(file.filename).toBe('receipt-rcpt-1.jpg')
  })

  it('raises a 404 as an ApiError rather than handing back an empty blob', async () => {
    resetMswState()
    await expect(fetchReceiptThumbnail('not-mine')).rejects.toMatchObject({
      status: 404,
      code: 'not-found',
    })
  })
})

describe('filenameFromDisposition', () => {
  it('reads the quoted form', () => {
    expect(filenameFromDisposition('attachment; filename="receipt-1.jpg"')).toBe('receipt-1.jpg')
  })

  it('reads the unquoted form', () => {
    expect(filenameFromDisposition('attachment; filename=receipt-1.jpg')).toBe('receipt-1.jpg')
  })

  it('prefers RFC 5987’s filename*, which is what a non-ASCII merchant name needs', () => {
    expect(
      filenameFromDisposition("attachment; filename=\"fallback.jpg\"; filename*=UTF-8''caf%C3%A9.jpg")
    ).toBe('café.jpg')
  })

  it('is null when there is no header and when there is no filename in one', () => {
    expect(filenameFromDisposition(null)).toBeNull()
    expect(filenameFromDisposition('inline')).toBeNull()
  })
})
