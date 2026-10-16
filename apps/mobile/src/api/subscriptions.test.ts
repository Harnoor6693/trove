import { calls, failureOf, replyOk, replyError, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests } from './client';
import {
  cancelSubscription,
  fetchSubscription,
  fetchSubscriptionAlerts,
  fetchSubscriptionSummary,
  fetchSubscriptions,
  markSubscriptionAlertRead,
  scanSubscriptions,
} from './subscriptions';
import type {
  SubscriptionAlertResponse,
  SubscriptionResponse,
  SubscriptionSummaryResponse,
} from './types';

const BASE = 'https://trove.example.com';

const SUBSCRIPTION: SubscriptionResponse = {
  id: 's1',
  householdId: 'h1',
  createdBy: 'u1',
  merchantName: 'SPOTIFY',
  categoryId: null,
  normalizedAmount: '11.9900',
  previousAmount: '9.9900',
  currency: 'CAD',
  billingCycle: 'MONTHLY',
  nextChargeDate: '2026-10-04',
  signal: 'AMBER',
  priceChanged: true,
  firstSeenDate: '2026-03-04',
  lastSeenDate: '2026-09-04',
  cancelled: false,
  createdAt: '2026-09-04T02:00:00Z',
  updatedAt: '2026-09-05T02:00:00Z',
};

const ALERT: SubscriptionAlertResponse = {
  id: 'al1',
  householdId: 'h1',
  createdBy: 'u1',
  subscriptionId: 's1',
  alertType: 'PRICE_INCREASE',
  alertMessage: 'SPOTIFY went up from 9.99 CAD to 11.99 CAD.',
  isRead: false,
  createdAt: '2026-09-05T02:00:00Z',
};

const SUMMARY: SubscriptionSummaryResponse = {
  currency: 'CAD',
  activeCount: 5,
  flaggedCount: 1,
  byCurrency: [
    { currency: 'CAD', monthlyRunRate: '45.9700', activeCount: 4, flaggedCount: 1 },
    { currency: 'USD', monthlyRunRate: '12.0000', activeCount: 1, flaggedCount: 0 },
  ],
  next14Days: [],
};

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('fetchSubscriptions', () => {
  it('unwraps the bare array — not paged', async () => {
    replyOk([SUBSCRIPTION]);
    await expect(fetchSubscriptions()).resolves.toEqual([SUBSCRIPTION]);
    expect(calls()).toEqual(['GET /v1/subscriptions']);
  });
});

describe('fetchSubscriptionSummary', () => {
  it('carries per-currency totals and no top-level total', async () => {
    replyOk(SUMMARY);
    const result = await fetchSubscriptionSummary();
    expect(result.byCurrency).toHaveLength(2);
    expect(result).not.toHaveProperty('totalMonthlyRunRate');
    expect(calls()).toEqual(['GET /v1/subscriptions/summary']);
  });
});

describe('fetchSubscriptionAlerts', () => {
  it('unwraps the bare array of unread alerts', async () => {
    replyOk([ALERT]);
    await expect(fetchSubscriptionAlerts()).resolves.toEqual([ALERT]);
    expect(calls()).toEqual(['GET /v1/subscriptions/alerts']);
  });
});

describe('markSubscriptionAlertRead', () => {
  it('patches with no body and returns the read alert', async () => {
    replyOk({ ...ALERT, isRead: true });
    const result = await markSubscriptionAlertRead('al1');
    expect(result.isRead).toBe(true);
    expect(calls()).toEqual(['PATCH /v1/subscriptions/alerts/al1/read']);
  });

  it('surfaces 404 for another household’s alert', async () => {
    replyError(404, 'not-found', 'No such alert.');
    const failure = await failureOf(markSubscriptionAlertRead('other'));
    expect(failure.status).toBe(404);
  });
});

describe('scanSubscriptions', () => {
  it('posts with no body and answers the fresh active list', async () => {
    replyOk([SUBSCRIPTION]);
    await expect(scanSubscriptions()).resolves.toEqual([SUBSCRIPTION]);
    expect(calls()).toEqual(['POST /v1/subscriptions/scan']);
  });
});

describe('fetchSubscription', () => {
  it('returns a cancelled row exactly as the server sent it — still readable', async () => {
    replyOk({ ...SUBSCRIPTION, cancelled: true });
    const result = await fetchSubscription('s1');
    expect(result.cancelled).toBe(true);
    expect(calls()).toEqual(['GET /v1/subscriptions/s1']);
  });
});

describe('cancelSubscription', () => {
  it('deletes (soft) and expects EmptySuccess', async () => {
    replyOk(undefined);
    await expect(cancelSubscription('s1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/subscriptions/s1']);
  });

  it('encodes a path-hostile id rather than letting it change the route', async () => {
    replyOk(undefined);
    await cancelSubscription('../networth/assets/x');
    expect(seen[0].url).toBe('/v1/subscriptions/..%2Fnetworth%2Fassets%2Fx');
  });
});
