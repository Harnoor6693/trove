// Imported first: installs the mock adapter before `client.ts` is evaluated.
import { calls, failureOf, reply, replyError, replyOk, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests } from './client';
import {
  commitImport,
  createImport,
  fetchImportPreview,
  undoImport,
  IMPORT_ACCOUNT_FIELD,
  IMPORT_FILE_FIELD,
  IMPORT_SOURCE_KIND_FIELD,
} from './imports';
import type { ImportPreviewResponse, SyncLogResponse } from './types';

const BASE = 'https://trove.example.com';

const PREVIEW: ImportPreviewResponse = {
  id: 'b1',
  householdId: 'h1',
  accountId: 'a1',
  sourceKind: 'CSV',
  status: 'PREVIEW',
  originalFilename: 'statement-2026-09.csv',
  rowCount: 42,
  newCount: 40,
  duplicateCount: 2,
  fuzzyDuplicateCount: 0,
  invalidCount: 0,
  mapping: {
    dateColumn: 'Date',
    descriptionColumn: 'Description',
    amountColumn: 'Amount',
    debitColumn: null,
    creditColumn: null,
    currencyColumn: null,
    balanceColumn: null,
    dateFormat: null,
    delimiter: ',',
    charset: 'UTF-8',
    hasHeader: true,
  },
  dateFormatCandidates: [
    { format: 'dd/MM/yyyy', sampleValues: ['03/09/2026'], interpretedDates: ['2026-09-03'] },
    { format: 'MM/dd/yyyy', sampleValues: ['03/09/2026'], interpretedDates: ['2026-03-09'] },
  ],
  rows: [],
  warnings: [],
  createdAt: '2026-09-05T03:20:00Z',
};

const LOG: SyncLogResponse = {
  id: 'l1',
  householdId: 'h1',
  accountId: 'a1',
  sourceKind: 'CSV',
  status: 'SUCCESS',
  transactionsFetched: 42,
  transactionsNew: 40,
  transactionsUpdated: 0,
  transactionsSkipped: 2,
  transactionsFailed: 0,
  errorMessage: null,
  startedAt: '2026-09-05T03:21:00Z',
  completedAt: '2026-09-05T03:21:04Z',
};

/** The parts a `FormData` was given, as `[field, value]` pairs. */
function formParts(index = 0): Array<[string, unknown]> {
  // React Native's TypeScript lib declares `FormData` without the iterator
  // methods the runtime (and the whatwg-fetch polyfill jest-expo installs)
  // actually has, so the shape is asserted here rather than in the source.
  const form = seen[index].data as unknown as {
    entries(): IterableIterator<[string, unknown]>;
  };
  return Array.from(form.entries());
}

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('createImport', () => {
  it('uploads multipart with the field names the operation declares', async () => {
    replyOk(PREVIEW);
    const preview = await createImport({
      file: { uri: 'file://statement.csv', name: 'statement.csv', type: 'text/csv' },
      accountId: 'a1',
      sourceKind: 'CSV',
    });

    expect(preview.status).toBe('PREVIEW');
    expect(calls()).toEqual(['POST /v1/imports']);
    expect(seen[0].headers['Content-Type']).toBe('multipart/form-data');

    const fields = formParts().map(([field]) => field);
    expect(fields).toContain(IMPORT_FILE_FIELD);
    expect(fields).toContain(IMPORT_ACCOUNT_FIELD);
    expect(fields).toContain(IMPORT_SOURCE_KIND_FIELD);
  });

  it('omits accountId and sourceKind when the caller has neither', async () => {
    replyOk(PREVIEW);
    await createImport({
      file: { uri: 'file://s.ofx', name: 's.ofx', type: 'application/x-ofx' },
    });
    const fields = formParts().map(([field]) => field);
    expect(fields).toEqual([IMPORT_FILE_FIELD]);
  });

  it('surfaces the 413 as its own slug rather than a generic failure', async () => {
    replyError(413, 'upload-too-large', 'That file exceeds the per-file cap.');
    const failure = await failureOf(
      createImport({
        file: { uri: 'file://big.csv', name: 'big.csv', type: 'text/csv' },
        accountId: 'a1',
      })
    );
    expect(failure).toMatchObject({ status: 413, code: 'upload-too-large' });
  });

  it('surfaces the 400 a CSV with no accountId is refused with', async () => {
    replyError(400, 'validation-error', 'accountId is required for a CSV upload.');
    const failure = await failureOf(
      createImport({
        file: { uri: 'file://s.csv', name: 's.csv', type: 'text/csv' },
      })
    );
    expect(failure.code).toBe('validation-error');
  });
});

describe('fetchImportPreview', () => {
  it('re-reads the batch, counts and all', async () => {
    replyOk(PREVIEW);
    const preview = await fetchImportPreview('b1');
    expect(preview.newCount).toBe(40);
    expect(preview.duplicateCount).toBe(2);
    expect(preview.dateFormatCandidates).toHaveLength(2);
    expect(calls()).toEqual(['GET /v1/imports/b1/preview']);
  });
});

describe('commitImport', () => {
  it('sends no body at all when the member changed nothing', async () => {
    replyOk(LOG);
    await commitImport('b1');
    expect(calls()).toEqual(['POST /v1/imports/b1/commit']);
    expect(seen[0].data).toBeUndefined();
  });

  it('sends only the corrections, so omitted fields keep their detected values', async () => {
    replyOk(LOG);
    await commitImport('b1', { mapping: { dateFormat: 'dd/MM/yyyy' } });
    expect(JSON.parse(seen[0].data as string)).toEqual({ mapping: { dateFormat: 'dd/MM/yyyy' } });
  });

  it('returns the sync run the commit wrote', async () => {
    replyOk(LOG);
    const log = await commitImport('b1');
    expect(log.transactionsNew).toBe(40);
    expect(log.transactionsSkipped).toBe(2);
  });

  it('surfaces the 409 a batch that is no longer in PREVIEW answers', async () => {
    replyError(409, 'conflict', 'This batch has already been committed.');
    const failure = await failureOf(commitImport('b1'));
    expect(failure).toMatchObject({ status: 409, code: 'conflict' });
  });
});

describe('undoImport', () => {
  it('accepts the closed EmptySuccess shape', async () => {
    reply(() => ({ status: 200, data: { success: true } }));
    await expect(undoImport('b1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/imports/b1']);
  });

  it('surfaces the 409 a never-committed batch answers', async () => {
    replyError(409, 'conflict', 'This batch was never committed.');
    const failure = await failureOf(undoImport('b1'));
    expect(failure).toMatchObject({ status: 409, code: 'conflict' });
  });
});

describe('the id is a path segment, not a path', () => {
  /**
   * A batch id reaches these modules from a pasted field and from a `trove://`
   * deep link. Interpolating one raw would let `../accounts/<uuid>` turn
   * "undo this import" into `DELETE /v1/accounts/<uuid>` — the operation that
   * deletes an account and every transaction on it — once the URL layer
   * resolved the dot segments. Encoding is what makes that impossible.
   */
  const TRAVERSAL = '../accounts/00000000-0000-4000-8000-0000000000a1';

  it('never lets a traversal id leave /v1/imports/ on the undo', async () => {
    reply(() => ({ status: 200, data: { success: true } }));
    await undoImport(TRAVERSAL);
    const [url] = calls();
    expect(url).toBe('DELETE /v1/imports/..%2Faccounts%2F00000000-0000-4000-8000-0000000000a1');
    expect(url).not.toContain('/v1/accounts/');
    expect(url).not.toContain('..%2F..');
  });

  it('encodes the id on the preview and the commit too', async () => {
    replyOk(PREVIEW);
    await fetchImportPreview(TRAVERSAL);
    replyOk(LOG);
    await commitImport(TRAVERSAL);
    expect(calls()).toEqual([
      'GET /v1/imports/..%2Faccounts%2F00000000-0000-4000-8000-0000000000a1/preview',
      'POST /v1/imports/..%2Faccounts%2F00000000-0000-4000-8000-0000000000a1/commit',
    ]);
  });
});
