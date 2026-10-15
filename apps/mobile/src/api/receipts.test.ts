// Imported first: it installs the mock adapter on `axios.defaults`, which
// `axios.create()` inside `client.ts` copies at module-evaluation time.
import { calls, failureOf, reply, replyError, replyOk, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests } from './client';
import {
  RECEIPT_FILE_FIELD,
  RECEIPT_MAX_UPLOAD_BYTES,
  RECEIPT_SOURCE_FIELD,
  declaredImageType,
  deleteReceipt,
  fetchReceipt,
  fetchReceiptImage,
  fetchReceiptStatus,
  fetchReceipts,
  fetchReceiptThumbnail,
  patchReceipt,
  receiptFilePart,
  reviewReceipt,
  uploadReceipt,
} from './receipts';
import type {
  ReceiptResponse,
  ReceiptStatusResponse,
  ReceiptSummaryResponse,
  ReceiptUploadResponse,
} from './types';

const BASE = 'https://trove.example.com';

const SUMMARY: ReceiptSummaryResponse = {
  id: 'r1',
  status: 'COMPLETE',
  source: 'SCAN',
  extractedMerchant: 'Blue Bottle Coffee',
  extractedAmount: '12.3400',
  extractedCurrency: 'CAD',
  extractedDate: '2026-06-05',
  confidence: '0.940',
  reviewRequired: true,
  createdAt: '2026-09-05T14:00:00Z',
};

const FULL: ReceiptResponse = {
  id: 'r1',
  status: 'COMPLETE',
  source: 'SCAN',
  uploadSizeBytes: 148320,
  extractedMerchant: 'Blue Bottle Coffee',
  extractedAmount: '12.3400',
  extractedCurrency: 'CAD',
  extractedDate: '2026-06-05',
  gstAmount: '0.5900',
  pstAmount: '0.8600',
  hstAmount: '0.0000',
  confidence: '0.940',
  reviewRequired: true,
  matchedTransactionId: null,
  thumbnailUrl: '/api/v1/receipts/r1/thumbnail',
  lineItems: [],
  errorMessage: null,
  processingStartedAt: null,
  processingCompletedAt: null,
  createdAt: '2026-09-05T14:00:00Z',
  updatedAt: '2026-09-05T14:00:09Z',
};

const META = { total: 137, page: 0, limit: 50 };

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('uploadReceipt', () => {
  it('posts multipart with the field names the controller binds, and answers 202', async () => {
    reply(() => ({
      status: 202,
      data: {
        success: true,
        data: {
          id: 'r1',
          status: 'UPLOADED',
          createdAt: '2026-09-05T14:00:00Z',
        } satisfies ReceiptUploadResponse,
      },
    }));

    const accepted = await uploadReceipt('file://receipt.jpg');
    expect(accepted).toEqual({ id: 'r1', status: 'UPLOADED', createdAt: '2026-09-05T14:00:00Z' });
    expect(calls()).toEqual(['POST /v1/receipts/upload']);
    expect(seen[0].headers?.['Content-Type']).toBe('multipart/form-data');

    // The two field names are the contract's, not this app's invention.
    // React Native's `FormData` lib type declares neither `keys` nor `get`,
    // but the runtime under jest provides both, so the parts are read through
    // the standard shape rather than a private `_parts` field.
    const form = seen[0].data as unknown as {
      keys: () => Iterable<string>;
      get: (name: string) => unknown;
    };
    expect(Array.from(form.keys())).toEqual([RECEIPT_FILE_FIELD, RECEIPT_SOURCE_FIELD]);
    expect(form.get(RECEIPT_SOURCE_FIELD)).toBe('SCAN');
  });

  it('sends UPLOAD as the source when asked', async () => {
    replyOk({ id: 'r2', status: 'UPLOADED', createdAt: '2026-09-05T14:00:00Z' });
    await uploadReceipt('file://x.jpg', 'UPLOAD');
    const form = seen[0].data as unknown as { get: (name: string) => unknown };
    expect(form.get(RECEIPT_SOURCE_FIELD)).toBe('UPLOAD');
  });

  it('surfaces 413 upload-too-large as its own slug, with the server sentence', async () => {
    replyError(413, 'upload-too-large', 'That image is larger than this server accepts.');
    const failure = await failureOf(uploadReceipt('file://big.jpg'));
    expect(failure.status).toBe(413);
    expect(failure.code).toBe('upload-too-large');
    expect(failure.message).toBe('That image is larger than this server accepts.');
  });

  it('states a size ceiling for the capture screen to quote', () => {
    expect(RECEIPT_MAX_UPLOAD_BYTES).toBeGreaterThan(0);
  });

  it('still posts a PNG the member picked, rather than refusing an accepted format', async () => {
    replyOk({ id: 'r3', status: 'UPLOADED', createdAt: '2026-09-05T14:00:00Z' });
    await uploadReceipt('file://shot.png', 'UPLOAD', 'image/png');
    expect(calls()).toEqual(['POST /v1/receipts/upload']);
    const form = seen[0].data as unknown as { get: (name: string) => unknown };
    expect(form.get(RECEIPT_SOURCE_FIELD)).toBe('UPLOAD');
  });

  it('refuses a HEIC before it leaves the device rather than declaring it a JPEG', async () => {
    // The operation refuses HEIC *on the stated assumption that the mobile
    // client converts first*, and this app has no conversion step. Sending it
    // as `image/jpeg` earns an opaque 400 the member cannot act on; saying so
    // here is the honest half of that gap.
    const failure = await failureOf(uploadReceipt('file://IMG_0042.HEIC', 'UPLOAD', 'image/heic'));
    expect(failure.message).toContain('JPEG or a PNG');
    // Nothing was sent at all.
    expect(calls()).toEqual([]);
  });

  it('refuses a HEIC recognised only by its extension', async () => {
    const failure = await failureOf(uploadReceipt('file://IMG_0042.heic', 'UPLOAD', null));
    expect(failure.message).toContain('JPEG or a PNG');
    expect(calls()).toEqual([]);
  });
});

describe('receiptFilePart', () => {
  // The part the multipart body carries. Asserted here rather than through
  // `FormData`, whose implementation in this environment stringifies an
  // appended object into "[object Object]".
  it('names and declares a JPEG as a JPEG', () => {
    expect(receiptFilePart('file://cam.jpg', 'image/jpeg')).toEqual({
      uri: 'file://cam.jpg',
      name: 'receipt.jpg',
      type: 'image/jpeg',
    });
  });

  it('names and declares a PNG as a PNG — the server checks the bytes against this', () => {
    expect(receiptFilePart('file://shot.png', 'image/png')).toEqual({
      uri: 'file://shot.png',
      name: 'receipt.png',
      type: 'image/png',
    });
  });
});

describe('declaredImageType', () => {
  it.each([
    ['image/jpeg', 'file://a.bin', 'image/jpeg'],
    ['image/jpg', 'file://a.bin', 'image/jpeg'],
    ['IMAGE/PNG; charset=binary', 'file://a.bin', 'image/png'],
  ])('trusts the declared type %s', (mime, uri, expected) => {
    expect(declaredImageType(mime, uri)).toBe(expected);
  });

  it.each(['image/heic', 'image/heif', 'image/webp', 'application/pdf'])(
    'refuses %s outright rather than relabelling it',
    (mime) => {
      expect(declaredImageType(mime, 'file://a.jpg')).toBeNull();
    }
  );

  it.each([
    ['file://a.png', 'image/png'],
    ['file://a.PNG?x=1', 'image/png'],
    ['file://a.jpeg', 'image/jpeg'],
    ['file://a.JPG', 'image/jpeg'],
  ])('falls back to the extension of %s when the picker declared nothing', (uri, expected) => {
    expect(declaredImageType(null, uri)).toBe(expected);
  });

  it.each(['file://a.heic', 'file://a.HEIF', 'file://a.webp'])(
    'refuses %s on its extension alone',
    (uri) => {
      expect(declaredImageType(null, uri)).toBeNull();
    }
  );

  it('falls back to JPEG for a URI with no extension — what launchCameraAsync produces', () => {
    expect(declaredImageType(null, 'content://media/external/images/1')).toBe('image/jpeg');
    expect(declaredImageType('', 'file://tmp/capture')).toBe('image/jpeg');
  });
});

describe('fetchReceipts', () => {
  it('consumes the operation as a PAGE — data and meta together, never a bare array', async () => {
    replyOk([SUMMARY], META);
    await expect(fetchReceipts()).resolves.toEqual({ data: [SUMMARY], meta: META });
    expect(calls()).toEqual(['GET /v1/receipts']);
  });

  it('sends the page the caller asked for, so page two is reachable', async () => {
    replyOk([SUMMARY], { total: 137, page: 1, limit: 50 });
    const page = await fetchReceipts({ page: 1 });
    expect(seen[0].params).toEqual({ page: 1 });
    expect(page.meta.page).toBe(1);
  });

  it('refuses a page whose pagination metadata is missing rather than silently truncating', async () => {
    replyOk([SUMMARY]);
    const failure = await failureOf(fetchReceipts());
    expect(failure.message).toBe('The server returned a page without its pagination metadata.');
  });
});

describe('fetchReceipt', () => {
  it('returns the whole row', async () => {
    replyOk(FULL);
    await expect(fetchReceipt('r1')).resolves.toEqual(FULL);
    expect(calls()).toEqual(['GET /v1/receipts/r1']);
  });

  it("is 404, never 403, for another household's id", async () => {
    replyError(404, 'not-found', 'No such receipt.');
    const failure = await failureOf(fetchReceipt('nope'));
    expect(failure.status).toBe(404);
    expect(failure.code).toBe('not-found');
  });
});

describe('fetchReceiptStatus', () => {
  it('asks the cheap status route rather than re-fetching the row', async () => {
    const status: ReceiptStatusResponse = {
      id: 'r1',
      status: 'EXTRACTING',
      reviewRequired: false,
      confidence: null,
    };
    replyOk(status);
    await expect(fetchReceiptStatus('r1')).resolves.toEqual(status);
    expect(calls()).toEqual(['GET /v1/receipts/r1/status']);
  });
});

describe('patchReceipt', () => {
  it('PATCHes exactly the body it was given', async () => {
    replyOk(FULL);
    await patchReceipt('r1', { extractedMerchant: 'Loblaws' });
    expect(calls()).toEqual(['PATCH /v1/receipts/r1']);
    expect(seen[0].data).toBe(JSON.stringify({ extractedMerchant: 'Loblaws' }));
  });

  it('surfaces the 409 a still-running pipeline answers', async () => {
    replyError(409, 'conflict', 'This receipt is still being processed.');
    const failure = await failureOf(patchReceipt('r1', { extractedMerchant: 'X' }));
    expect(failure.status).toBe(409);
    expect(failure.code).toBe('conflict');
  });
});

describe('reviewReceipt', () => {
  it('is a different route from the field patch', async () => {
    replyOk(FULL);
    await reviewReceipt('r1', { accepted: true });
    expect(calls()).toEqual(['PATCH /v1/receipts/r1/review']);
    expect(seen[0].data).toBe(JSON.stringify({ accepted: true }));
  });
});

describe('deleteReceipt', () => {
  it('accepts the closed EmptySuccess shape and reads no data off it', async () => {
    reply(() => ({ status: 200, data: { success: true } }));
    await expect(deleteReceipt('r1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/receipts/r1']);
  });

  it('throws when the envelope says the delete was refused', async () => {
    reply(() => ({
      status: 200,
      data: { success: false, error: { status: 409, detail: 'Still processing.' } },
    }));
    const failure = await failureOf(deleteReceipt('r1'));
    expect(failure.message).toBe('Still processing.');
  });
});

describe('the authenticated image routes', () => {
  /** A one-pixel JPEG's worth of bytes is enough — this is about the transport. */
  const bytes = new Uint8Array([0xff, 0xd8, 0xff, 0xe0]);

  it('fetches the thumbnail through `api` as a data URI, with no token in the URL', async () => {
    reply(() => ({
      status: 200,
      data: bytes.buffer,
      headers: { 'content-type': 'image/jpeg' },
    }));
    const uri = await fetchReceiptThumbnail('r1');
    expect(uri.startsWith('data:image/jpeg;base64,')).toBe(true);
    expect(calls()).toEqual(['GET /v1/receipts/r1/thumbnail']);
    expect(seen[0].responseType).toBe('arraybuffer');
    // The credential is a header, never a query string.
    expect(seen[0].url).not.toMatch(/token|access|Bearer/i);
    expect(seen[0].url).not.toContain('?');
  });

  it('fetches the full stored image from the download route', async () => {
    reply(() => ({ status: 200, data: bytes.buffer, headers: { 'content-type': 'image/jpeg' } }));
    await fetchReceiptImage('r1');
    expect(calls()).toEqual(['GET /v1/receipts/r1/download']);
  });

  it('is 404 when the receipt has no thumbnail row', async () => {
    replyError(404, 'not-found', 'No thumbnail.');
    const failure = await failureOf(fetchReceiptThumbnail('r1'));
    expect(failure.status).toBe(404);
  });
});
