// Imported first: installs the mock adapter before `client.ts` is evaluated.
import { calls, failureOf, reply, replyError, replyOk, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { setSession, __resetClientForTests } from './client';
import {
  createManualTransaction,
  deleteTransaction,
  fetchTransaction,
  fetchTransactions,
  patchTransaction,
  transactionParams,
  unlinkTransfer,
  DEFAULT_TRANSACTION_LIMIT,
  MAX_TRANSACTION_LIMIT,
} from './transactions';
import type { TransactionResponse } from './types';

const BASE = 'https://trove.example.com';

const TRANSACTION: TransactionResponse = {
  id: 't1',
  householdId: 'h1',
  accountId: 'a1',
  createdBy: 'u1',
  providerTransactionId: null,
  amount: '4.75',
  currency: 'CAD',
  type: 'DEBIT',
  description: 'SQ *BLUE DOOR COFFEE',
  merchantName: 'Blue Door Coffee',
  date: '2026-09-04',
  postedAt: null,
  pending: false,
  manual: true,
  categoryId: null,
  transferGroupId: null,
  importBatchId: null,
  tags: null,
  notes: null,
  createdAt: '2026-09-04T18:00:00Z',
  updatedAt: '2026-09-04T18:00:00Z',
};

function body(index = 0): Record<string, unknown> {
  return JSON.parse(seen[index].data as string) as Record<string, unknown>;
}

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('transactionParams', () => {
  it('drops undefined entries rather than sending empty strings', () => {
    expect(transactionParams({})).toEqual({});
  });

  it('carries every parameter the operation declares', () => {
    expect(
      transactionParams({
        page: 2,
        limit: 25,
        sort: 'amount,asc',
        from: '2026-08-01',
        to: '2026-08-31',
        type: 'CREDIT',
        accountId: 'a1',
        categoryId: 'c1',
        q: 'coffee',
      })
    ).toEqual({
      page: 2,
      limit: 25,
      sort: 'amount,asc',
      from: '2026-08-01',
      to: '2026-08-31',
      type: 'CREDIT',
      accountId: 'a1',
      categoryId: 'c1',
      q: 'coffee',
    });
  });

  it('treats a blank q as absent, which is what the operation says it means', () => {
    expect(transactionParams({ q: '   ' })).toEqual({});
    expect(transactionParams({ q: '  coffee  ' })).toEqual({ q: 'coffee' });
  });

  it('keeps page zero, which is a real page and not a missing value', () => {
    expect(transactionParams({ page: 0 })).toEqual({ page: 0 });
  });

  it('states the server-side limit bounds the operation documents', () => {
    expect(DEFAULT_TRANSACTION_LIMIT).toBe(50);
    expect(MAX_TRANSACTION_LIMIT).toBe(500);
  });
});

describe('fetchTransactions', () => {
  it('returns BOTH the page of data and the meta the 200 requires', async () => {
    replyOk([TRANSACTION], { total: 137, page: 0, limit: 50 });
    const page = await fetchTransactions();
    expect(page.data).toEqual([TRANSACTION]);
    expect(page.meta).toEqual({ total: 137, page: 0, limit: 50 });
  });

  it('reaches page two with the page parameter the operation declares', async () => {
    replyOk([TRANSACTION], { total: 137, page: 1, limit: 50 });
    await fetchTransactions({ page: 1, limit: 50, sort: 'date,desc' });
    expect(calls()).toEqual(['GET /v1/transactions']);
    expect(seen[0].params).toEqual({ page: 1, limit: 50, sort: 'date,desc' });
  });

  it('refuses a page that arrived without its pagination metadata', async () => {
    // A bare array in `data` and no `meta` is exactly the pre-collapse
    // expectation this client had, and it is a silent truncation.
    reply(() => ({ status: 200, data: { success: true, data: [TRANSACTION] } }));
    await expect(fetchTransactions()).rejects.toThrow(/pagination metadata/);
  });

  it('surfaces the 400 the query validator answers, naming the parameter', async () => {
    replyError(400, 'validation-error', 'limit must be at most 500', {
      fieldErrors: [{ field: 'limit', message: 'must be less than or equal to 500' }],
    });
    const failure = await failureOf(fetchTransactions({ limit: 5000 }));
    expect(failure.fieldErrors[0]).toEqual({
      field: 'limit',
      message: 'must be less than or equal to 500',
    });
  });
});

describe('fetchTransaction', () => {
  it('reads one row by id', async () => {
    replyOk(TRANSACTION);
    await expect(fetchTransaction('t1')).resolves.toEqual(TRANSACTION);
    expect(calls()).toEqual(['GET /v1/transactions/t1']);
  });
});

describe('createManualTransaction', () => {
  it('sends the absolute amount as a decimal string with the direction in type', async () => {
    replyOk(TRANSACTION);
    await createManualTransaction({
      accountId: 'a1',
      amount: '4.75',
      date: '2026-09-04',
      type: 'DEBIT',
      merchant: 'Blue Door Coffee',
    });
    expect(calls()).toEqual(['POST /v1/transactions']);
    expect(body()).toEqual({
      accountId: 'a1',
      amount: '4.75',
      date: '2026-09-04',
      type: 'DEBIT',
      merchant: 'Blue Door Coffee',
    });
    // `currency` is the account's and is not accepted on this operation.
    expect(body()).not.toHaveProperty('currency');
  });

  it('surfaces the 409 a non-MANUAL account answers', async () => {
    replyError(409, 'conflict', 'That account is not a manual account.');
    const failure = await failureOf(
      createManualTransaction({ accountId: 'a2', amount: '1.00', date: '2026-09-04' })
    );
    expect(failure).toMatchObject({ status: 409, code: 'conflict' });
  });
});

describe('patchTransaction — presence is meaningful', () => {
  it('sends ONLY the field that changed', async () => {
    replyOk(TRANSACTION);
    await patchTransaction('t1', { notes: 'Reimbursable' });
    expect(calls()).toEqual(['PATCH /v1/transactions/t1']);
    expect(body()).toEqual({ notes: 'Reimbursable' });
    expect(Object.keys(body())).toHaveLength(1);
  });

  it('keeps an explicit null in the body — present-with-null clears the field', async () => {
    replyOk(TRANSACTION);
    await patchTransaction('t1', { categoryId: null });
    expect(body()).toEqual({ categoryId: null });
    expect('categoryId' in body()).toBe(true);
  });

  it('sends an empty body for a no-op rather than inventing fields', async () => {
    replyOk(TRANSACTION);
    await patchTransaction('t1', {});
    expect(body()).toEqual({});
  });
});

describe('transfers — unlinking is an explicit null', () => {
  it('unlinkTransfer sends transferGroupId: null, not an omitted field', async () => {
    replyOk(TRANSACTION);
    await unlinkTransfer('t1');
    expect(body()).toEqual({ transferGroupId: null });
    // Omitting the key would mean "leave it alone" and would not unlink.
    expect('transferGroupId' in body()).toBe(true);
    expect(body().transferGroupId).toBeNull();
  });
});

describe('deleteTransaction', () => {
  it('accepts the closed EmptySuccess shape', async () => {
    reply(() => ({ status: 200, data: { success: true } }));
    await expect(deleteTransaction('t1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/transactions/t1']);
  });

  it('surfaces the 409 a source-fetched row answers', async () => {
    replyError(409, 'conflict', 'Only a hand-entered row can be deleted.');
    const failure = await failureOf(deleteTransaction('t1'));
    expect(failure).toMatchObject({ status: 409, code: 'conflict' });
  });
});

describe('the shipped client, not a second one', () => {
  it('a 401 refreshes once and retries the original request once', async () => {
    await setSession(
      { accessToken: 'stale', refreshToken: 'refresh-1', expiresIn: 900 },
      {
        id: 'u1',
        email: 'a@b.co',
        displayName: 'A',
        householdId: 'h1',
        role: 'MEMBER',
        totpEnabled: false,
      }
    );

    let unauthorisedOnce = false;
    reply((config) => {
      if (config.url === '/v1/auth/refresh') {
        return {
          status: 200,
          data: {
            success: true,
            data: { accessToken: 'fresh', refreshToken: 'refresh-2', expiresIn: 900 },
          },
        };
      }
      if (!unauthorisedOnce) {
        unauthorisedOnce = true;
        return { status: 401, data: { success: false, error: { status: 401 } } };
      }
      return {
        status: 200,
        data: { success: true, data: [], meta: { total: 0, page: 0, limit: 50 } },
      };
    });

    await expect(fetchTransactions()).resolves.toEqual({
      data: [],
      meta: { total: 0, page: 0, limit: 50 },
    });

    // Exactly one refresh — a second concurrent one would rotate a stale token
    // and family-revoke the whole session, which is why `client.ts` is
    // single-flight and why nothing here re-implements it.
    expect(calls().filter((call) => call.endsWith('/v1/auth/refresh'))).toHaveLength(1);
    expect(calls()).toEqual([
      'GET /v1/transactions',
      'POST /v1/auth/refresh',
      'GET /v1/transactions',
    ]);
  });

  it('a 429 does not trigger a refresh and does not end the session', async () => {
    await setSession(
      { accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 900 },
      {
        id: 'u1',
        email: 'a@b.co',
        displayName: 'A',
        householdId: 'h1',
        role: 'MEMBER',
        totpEnabled: false,
      }
    );
    replyError(429, 'rate-limited', 'Slow down.', { retryAfter: 7 });

    const failure = await failureOf(fetchTransactions());
    expect(failure.retryAfterSeconds).toBe(7);
    expect(calls()).toEqual(['GET /v1/transactions']);
    expect(calls().some((call) => call.includes('/v1/auth/refresh'))).toBe(false);
  });
});

describe('the id is a path segment, not a path', () => {
  const TRAVERSAL = '../accounts/00000000-0000-4000-8000-0000000000a1';

  it('encodes the id rather than letting it add a segment', async () => {
    reply(() => ({ status: 200, data: { success: true } }));
    await deleteTransaction(TRAVERSAL);
    const [url] = calls();
    expect(url).toBe(
      'DELETE /v1/transactions/..%2Faccounts%2F00000000-0000-4000-8000-0000000000a1'
    );
    expect(url).not.toContain('/v1/accounts/');
  });
});
