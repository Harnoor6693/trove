// Imported first: installs the mock adapter before `client.ts` is evaluated.
import { calls, failureOf, replyError, replyOk, reset } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests } from './client';
import { fetchDashboardToday } from './dashboard';
import type { DashboardTodayResponse } from './types';

const BASE = 'https://trove.example.com';

const TODAY: DashboardTodayResponse = {
  date: '2026-09-04',
  timezone: 'America/Halifax',
  currency: 'CAD',
  spentToday: '12.34',
  transactionsToday: 3,
  spentThisMonth: '480.00',
  incomeThisMonth: '2500.00',
  netThisMonth: '2020.00',
  byCurrency: [
    {
      currency: 'CAD',
      spentToday: '12.34',
      transactionsToday: 3,
      spentThisMonth: '480.00',
      incomeThisMonth: '2500.00',
      netThisMonth: '2020.00',
    },
    {
      currency: 'USD',
      spentToday: '40.00',
      transactionsToday: 1,
      spentThisMonth: '90.00',
      incomeThisMonth: '0.00',
      netThisMonth: '-90.00',
    },
  ],
};

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('fetchDashboardToday', () => {
  it('returns the day, the zone and one subtotal per currency', async () => {
    replyOk(TODAY);
    const today = await fetchDashboardToday();
    expect(calls()).toEqual(['GET /v1/dashboard/today']);
    expect(today.date).toBe('2026-09-04');
    expect(today.timezone).toBe('America/Halifax');
    expect(today.byCurrency.map((entry) => entry.currency)).toEqual(['CAD', 'USD']);
  });

  it('keeps every amount as the decimal string the ledger sent — never a number', async () => {
    replyOk(TODAY);
    const today = await fetchDashboardToday();
    expect(typeof today.spentToday).toBe('string');
    for (const entry of today.byCurrency) {
      expect(typeof entry.spentToday).toBe('string');
      expect(typeof entry.netThisMonth).toBe('string');
    }
  });

  it('surfaces a 429 with its Retry-After rather than as a generic failure', async () => {
    replyError(429, 'rate-limited', 'Slow down.', { retryAfter: 15 });
    const failure = await failureOf(fetchDashboardToday());
    expect(failure).toMatchObject({ status: 429, retryAfterSeconds: 15 });
  });
});
