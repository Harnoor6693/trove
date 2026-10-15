// Imported first: it installs the mock adapter on `axios.defaults`, which
// `axios.create()` inside `client.ts` copies at module-evaluation time.
import { calls, failureOf, reply, replyError, replyOk, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { setSession, __resetClientForTests } from './client';
import {
  createAccount,
  deleteAccount,
  exchangeSetupToken,
  fetchAccount,
  fetchAccountSyncLogs,
  fetchAccounts,
  syncAccount,
  updateAccount,
} from './accounts';
import type { AccountResponse, SyncLogResponse } from './types';

const BASE = 'https://trove.example.com';

const ACCOUNT: AccountResponse = {
  id: 'a1',
  householdId: 'h1',
  createdBy: 'u1',
  sourceKind: 'MANUAL',
  displayName: 'Cash',
  institution: null,
  accountType: 'OTHER',
  currency: 'CAD',
  syncStatus: 'PENDING',
  lastSyncedAt: null,
  errorMessage: null,
  createdAt: '2026-09-01T10:00:00Z',
  updatedAt: '2026-09-01T10:00:00Z',
};

const LOG: SyncLogResponse = {
  id: 'l1',
  householdId: 'h1',
  accountId: 'a1',
  sourceKind: 'SIMPLEFIN',
  status: 'FAILED',
  transactionsFetched: 0,
  transactionsNew: 0,
  transactionsUpdated: 0,
  transactionsSkipped: 0,
  transactionsFailed: 0,
  errorMessage: 'The bridge did not answer.',
  startedAt: '2026-09-05T10:00:00Z',
  completedAt: '2026-09-05T10:00:05Z',
};

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('fetchAccounts', () => {
  it('unwraps the bare array the operation declares — this list is not paged', async () => {
    replyOk([ACCOUNT]);
    await expect(fetchAccounts()).resolves.toEqual([ACCOUNT]);
    expect(calls()).toEqual(['GET /v1/accounts']);
  });

  it('goes through the one client, so the bearer token and the base URL are applied', async () => {
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
    replyOk([]);
    await fetchAccounts();
    expect(seen[0].baseURL).toBe(`${BASE}/api`);
    expect(seen[0].headers.Authorization).toBe('Bearer access-1');
    expect(seen[0].headers['X-Requested-With']).toBe('XMLHttpRequest');
  });

  it('surfaces a 429 as a wait-and-retry failure carrying Retry-After', async () => {
    replyError(429, 'rate-limited', 'Slow down.', { retryAfter: 42 });
    const failure = await failureOf(fetchAccounts());
    expect(failure).toMatchObject({ status: 429, code: 'rate-limited', retryAfterSeconds: 42 });
  });
});

describe('fetchAccount', () => {
  it('reads one account by id', async () => {
    replyOk(ACCOUNT);
    await expect(fetchAccount('a1')).resolves.toEqual(ACCOUNT);
    expect(calls()).toEqual(['GET /v1/accounts/a1']);
  });
});

describe('createAccount', () => {
  it('posts the request body the schema declares and returns the created account', async () => {
    replyOk(ACCOUNT);
    await createAccount({
      sourceKind: 'MANUAL',
      displayName: 'Cash',
      institution: null,
      accountType: 'OTHER',
    });
    expect(calls()).toEqual(['POST /v1/accounts']);
    expect(JSON.parse(seen[0].data as string)).toEqual({
      sourceKind: 'MANUAL',
      displayName: 'Cash',
      institution: null,
      accountType: 'OTHER',
    });
  });

  it('reports the 400 fieldErrors as the list of pairs the contract sends', async () => {
    replyError(400, 'validation-error', 'Check the form.', {
      fieldErrors: [{ field: 'displayName', message: 'must not be blank' }],
    });
    const failure = await failureOf(
      createAccount({
        sourceKind: 'MANUAL',
        displayName: '',
        accountType: 'OTHER',
      })
    );
    expect(failure.fieldErrors).toEqual([{ field: 'displayName', message: 'must not be blank' }]);
  });
});

describe('updateAccount', () => {
  it('PUTs only the three writable fields — sourceKind and currency are not in the body', async () => {
    replyOk(ACCOUNT);
    await updateAccount('a1', {
      displayName: 'Everyday',
      institution: 'Example CU',
      accountType: 'CHECKING',
    });
    expect(calls()).toEqual(['PUT /v1/accounts/a1']);
    const body = JSON.parse(seen[0].data as string);
    expect(body).toEqual({
      displayName: 'Everyday',
      institution: 'Example CU',
      accountType: 'CHECKING',
    });
    expect(body).not.toHaveProperty('sourceKind');
    expect(body).not.toHaveProperty('currency');
  });
});

describe('deleteAccount', () => {
  it('accepts the closed EmptySuccess shape without reading a data key off it', async () => {
    // Literally `{"success": true}` — no `data`. `unwrap` would have thrown.
    reply(() => ({ status: 200, data: { success: true } }));
    await expect(deleteAccount('a1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/accounts/a1']);
  });

  it('throws when the server said success: false', async () => {
    reply(() => ({
      status: 200,
      data: { success: false, error: { status: 200, detail: 'Nope.' } },
    }));
    await expect(deleteAccount('a1')).rejects.toThrow('Nope.');
  });
});

describe('syncAccount', () => {
  it('returns the sync log, and a FAILED run is a 200 rather than a rejection', async () => {
    replyOk(LOG);
    const log = await syncAccount('a1');
    expect(log.status).toBe('FAILED');
    expect(log.errorMessage).toBe('The bridge did not answer.');
    expect(calls()).toEqual(['POST /v1/accounts/a1/sync']);
  });

  it('rejects the 409 the claim answers when the account is already syncing', async () => {
    replyError(409, 'conflict', 'This account is already syncing.');
    const failure = await failureOf(syncAccount('a1'));
    expect(failure).toMatchObject({ status: 409, code: 'conflict' });
  });
});

describe('fetchAccountSyncLogs', () => {
  it('unwraps the bare array of runs', async () => {
    replyOk([LOG]);
    await expect(fetchAccountSyncLogs('a1')).resolves.toEqual([LOG]);
    expect(calls()).toEqual(['GET /v1/accounts/a1/sync-logs']);
  });
});

describe('exchangeSetupToken', () => {
  it('sends the token in the body and never in the URL', async () => {
    replyOk({ accountIds: ['a1', 'a2'], syncLogs: [LOG] });
    const result = await exchangeSetupToken({
      sourceKind: 'SIMPLEFIN',
      setupToken: 'c2VjcmV0LXRva2Vu',
    });
    expect(result.accountIds).toHaveLength(2);
    expect(seen[0].url).toBe('/v1/accounts/exchange-setup-token');
    expect(seen[0].url).not.toContain('c2VjcmV0LXRva2Vu');
    expect(JSON.parse(seen[0].data as string)).toEqual({
      sourceKind: 'SIMPLEFIN',
      setupToken: 'c2VjcmV0LXRva2Vu',
    });
  });

  it('surfaces the egress refusal as its own slug', async () => {
    replyError(400, 'outbound-url-rejected', 'The claim URL was not https.');
    const failure = await failureOf(
      exchangeSetupToken({
        sourceKind: 'SIMPLEFIN',
        setupToken: 'x',
      })
    );
    expect(failure.code).toBe('outbound-url-rejected');
    expect(failure.message).toBe('The claim URL was not https.');
  });
});

describe('the id is a path segment, not a path', () => {
  /**
   * An account id arrives from a list the server sent, but the account screen
   * is also reachable from a `trove://` deep link. Encoding every id keeps a
   * crafted one inside the operation the caller actually named — a 404 on
   * `/v1/accounts/...` rather than a resolved request to something else.
   */
  const TRAVERSAL = '../transactions/00000000-0000-4000-8000-0000000000t1';

  it('encodes the id on every account route that takes one', async () => {
    reply(() => ({ status: 200, data: { success: true } }));
    await deleteAccount(TRAVERSAL);
    const [url] = calls();
    expect(url).toBe(
      'DELETE /v1/accounts/..%2Ftransactions%2F00000000-0000-4000-8000-0000000000t1'
    );
    expect(url).not.toContain('/v1/transactions/');
  });
});
