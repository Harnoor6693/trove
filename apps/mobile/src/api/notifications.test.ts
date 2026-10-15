import { calls, failureOf, reply, replyOk, replyError, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests } from './client';
import {
  dismissNotification,
  fetchNotificationPreferences,
  fetchNotifications,
  fetchUnreadNotificationCount,
  markAllNotificationsRead,
  markNotificationRead,
  updateNotificationPreferences,
} from './notifications';
import type { NotificationPreferencesResponse, NotificationResponse } from './types';

const BASE = 'https://trove.example.com';

const NOTIFICATION: NotificationResponse = {
  id: 'n1',
  type: 'BUDGET_THRESHOLD',
  title: 'Groceries is at 92% of its September budget',
  body: 'You have spent CAD 368.00 of the CAD 400.00 budgeted for Groceries this month.',
  actionUrl: '/budgets/2026-09',
  read: false,
  dismissed: false,
  createdAt: '2026-09-05T18:00:00Z',
  readAt: null,
  dismissedAt: null,
};

const PREFERENCES: NotificationPreferencesResponse = {
  inAppEnabled: true,
  emailEnabled: false,
  emailAddress: null,
  weeklyDigestEnabled: true,
  subscriptionAlertEnabled: true,
  syncFailureAlertEnabled: true,
  budgetAlertThresholdPercent: 90,
  updatedAt: '2026-09-05T18:00:00Z',
};

const META = { total: 12, page: 0, limit: 20 };

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('fetchNotifications', () => {
  it('unwraps data+meta — a paged inbox', async () => {
    replyOk([NOTIFICATION], META);
    await expect(fetchNotifications()).resolves.toEqual({ data: [NOTIFICATION], meta: META });
    expect(calls()).toEqual(['GET /v1/notifications']);
  });

  it('throws when the server omits meta on this paged route', async () => {
    reply(() => ({ status: 200, data: { success: true, data: [NOTIFICATION] } }));
    await expect(fetchNotifications()).rejects.toThrow(/pagination metadata/);
  });

  it('sends page/limit as query params', async () => {
    replyOk([NOTIFICATION], META);
    await fetchNotifications({ page: 2, limit: 20 });
    expect(seen[0].params).toEqual({ page: 2, limit: 20 });
  });
});

describe('fetchUnreadNotificationCount', () => {
  it('returns the object shape, never a bare number', async () => {
    replyOk({ unread: 3 });
    await expect(fetchUnreadNotificationCount()).resolves.toEqual({ unread: 3 });
    expect(calls()).toEqual(['GET /v1/notifications/unread-count']);
  });
});

describe('markNotificationRead', () => {
  it('patches with no body and returns the read row', async () => {
    replyOk({ ...NOTIFICATION, read: true, readAt: '2026-09-05T19:00:00Z' });
    const result = await markNotificationRead('n1');
    expect(result.read).toBe(true);
    expect(calls()).toEqual(['PATCH /v1/notifications/n1/read']);
  });

  it('surfaces 404 for another member’s notification', async () => {
    replyError(404, 'not-found', 'No such notification.');
    const failure = await failureOf(markNotificationRead('other'));
    expect(failure.status).toBe(404);
  });
});

describe('markAllNotificationsRead', () => {
  it('posts with no body and answers how many rows changed', async () => {
    replyOk({ marked: 4 });
    await expect(markAllNotificationsRead()).resolves.toEqual({ marked: 4 });
    expect(calls()).toEqual(['POST /v1/notifications/read-all']);
  });
});

describe('dismissNotification', () => {
  it('deletes (hides) and expects EmptySuccess', async () => {
    replyOk(undefined);
    await expect(dismissNotification('n1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/notifications/n1']);
  });
});

describe('fetchNotificationPreferences', () => {
  it('returns the created-on-first-access defaults; emailEnabled is false', async () => {
    replyOk(PREFERENCES);
    const result = await fetchNotificationPreferences();
    expect(result.emailEnabled).toBe(false);
    expect(result).not.toHaveProperty('pushEnabled');
    expect(calls()).toEqual(['GET /v1/notifications/preferences']);
  });
});

describe('updateNotificationPreferences', () => {
  it('puts a partial body and returns the whole row after the write', async () => {
    replyOk({ ...PREFERENCES, budgetAlertThresholdPercent: 80 });
    const result = await updateNotificationPreferences({ budgetAlertThresholdPercent: 80 });
    expect(result.budgetAlertThresholdPercent).toBe(80);
    expect(calls()).toEqual(['PUT /v1/notifications/preferences']);
  });
});
