import fs from 'fs';
import path from 'path';
import React from 'react';
import { AppState, type AppStateStatus } from 'react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import {
  nextNotificationPage,
  notificationKeys,
  useDismissNotification,
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotificationPreferences,
  useNotifications,
  useUnreadNotificationCount,
  useUpdateNotificationPreferences,
} from './useNotifications';
import {
  dismissNotification,
  fetchNotificationPreferences,
  fetchNotifications,
  fetchUnreadNotificationCount,
  markAllNotificationsRead,
  markNotificationRead,
  updateNotificationPreferences,
} from '@api/notifications';
import type { NotificationPreferencesResponse, NotificationResponse, Page } from '@api/types';

jest.mock('@api/notifications', () => ({
  fetchNotifications: jest.fn(),
  fetchUnreadNotificationCount: jest.fn(),
  markNotificationRead: jest.fn(),
  markAllNotificationsRead: jest.fn(),
  dismissNotification: jest.fn(),
  fetchNotificationPreferences: jest.fn(),
  updateNotificationPreferences: jest.fn(),
}));

const mockList = fetchNotifications as jest.MockedFunction<typeof fetchNotifications>;
const mockUnread = fetchUnreadNotificationCount as jest.MockedFunction<
  typeof fetchUnreadNotificationCount
>;
const mockMarkRead = markNotificationRead as jest.MockedFunction<typeof markNotificationRead>;
const mockMarkAll = markAllNotificationsRead as jest.MockedFunction<
  typeof markAllNotificationsRead
>;
const mockDismiss = dismissNotification as jest.MockedFunction<typeof dismissNotification>;
const mockPreferences = fetchNotificationPreferences as jest.MockedFunction<
  typeof fetchNotificationPreferences
>;
const mockUpdatePreferences = updateNotificationPreferences as jest.MockedFunction<
  typeof updateNotificationPreferences
>;

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

function page(rows: NotificationResponse[], meta: Page<NotificationResponse>['meta']) {
  return { data: rows, meta };
}

/** Fires the most recently registered `AppState.addEventListener('change', …)` handler. */
function emitAppState(next: AppStateStatus) {
  const calls = (AppState.addEventListener as jest.Mock).mock.calls;
  const handler = calls[calls.length - 1][1] as (state: AppStateStatus) => void;
  act(() => handler(next));
}

let clients: ReturnType<typeof createTestQueryClient>[] = [];

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = createTestQueryClient();
  clients.push(qc);
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

function withClient() {
  const qc = createTestQueryClient();
  clients.push(qc);
  const invalidate = jest.spyOn(qc, 'invalidateQueries');
  const setData = jest.spyOn(qc, 'setQueryData');
  const provider = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  return { qc, invalidate, setData, provider };
}

beforeEach(() => {
  jest.clearAllMocks();
  jest.spyOn(AppState, 'addEventListener').mockReturnValue({ remove: jest.fn() } as never);
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('nextNotificationPage', () => {
  it('asks for the next page while the totals say there is one, and stops at the end', () => {
    expect(nextNotificationPage(page([], { total: 12, page: 0, limit: 5 }))).toBe(1);
    expect(nextNotificationPage(page([], { total: 12, page: 2, limit: 5 }))).toBeUndefined();
  });
});

describe('useNotifications', () => {
  it('pages the caller’s own undismissed inbox', async () => {
    mockList.mockResolvedValue(page([NOTIFICATION], { total: 1, page: 0, limit: 20 }));
    const { result } = renderHook(() => useNotifications(), { wrapper });
    await waitFor(() => expect(result.current.data).toBeDefined());
    expect(result.current.data?.pages[0].data).toEqual([NOTIFICATION]);
    expect(mockList).toHaveBeenCalledWith({ page: 0 });
  });
});

describe('useUnreadNotificationCount', () => {
  it('fetches the count', async () => {
    mockUnread.mockResolvedValue({ unread: 3 });
    const { result } = renderHook(() => useUnreadNotificationCount(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual({ unread: 3 }));
  });

  it('refetches when the app returns to the foreground — not a push notification', async () => {
    mockUnread.mockResolvedValue({ unread: 1 });
    const { invalidate, provider } = withClient();
    renderHook(() => useUnreadNotificationCount(), { wrapper: provider });
    await waitFor(() => expect(mockUnread).toHaveBeenCalledTimes(1));
    invalidate.mockClear();

    emitAppState('active');

    await waitFor(() =>
      expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual([
        'notifications',
        'unread-count',
      ])
    );
  });

  it('does nothing on a background/inactive transition', async () => {
    mockUnread.mockResolvedValue({ unread: 1 });
    const { invalidate, provider } = withClient();
    renderHook(() => useUnreadNotificationCount(), { wrapper: provider });
    await waitFor(() => expect(mockUnread).toHaveBeenCalledTimes(1));
    invalidate.mockClear();

    emitAppState('background');

    expect(invalidate).not.toHaveBeenCalled();
  });
});

describe('useMarkNotificationRead', () => {
  it('invalidates both the inbox and the unread count', async () => {
    mockMarkRead.mockResolvedValue({ ...NOTIFICATION, read: true });
    const { invalidate, provider } = withClient();
    const { result } = renderHook(() => useMarkNotificationRead(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('n1');
    });
    const invalidated = invalidate.mock.calls.map(([arg]) => arg?.queryKey);
    expect(invalidated).toContainEqual(['notifications', 'list']);
    expect(invalidated).toContainEqual(['notifications', 'unread-count']);
  });
});

describe('useMarkAllNotificationsRead', () => {
  it('zeroes the unread-count cache directly from the response', async () => {
    mockMarkAll.mockResolvedValue({ marked: 4 });
    const { setData, provider } = withClient();
    const { result } = renderHook(() => useMarkAllNotificationsRead(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync();
    });
    expect(setData).toHaveBeenCalledWith(['notifications', 'unread-count'], { unread: 0 });
  });
});

describe('useDismissNotification', () => {
  it('invalidates both the inbox and the unread count', async () => {
    mockDismiss.mockResolvedValue(undefined);
    const { invalidate, provider } = withClient();
    const { result } = renderHook(() => useDismissNotification(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('n1');
    });
    const invalidated = invalidate.mock.calls.map(([arg]) => arg?.queryKey);
    expect(invalidated).toContainEqual(['notifications', 'list']);
    expect(invalidated).toContainEqual(['notifications', 'unread-count']);
  });
});

describe('useNotificationPreferences / useUpdateNotificationPreferences', () => {
  it('loads the created-on-first-access defaults', async () => {
    mockPreferences.mockResolvedValue(PREFERENCES);
    const { result } = renderHook(() => useNotificationPreferences(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual(PREFERENCES));
  });

  it('writes the whole row back from the response, not a refetch', async () => {
    mockUpdatePreferences.mockResolvedValue({ ...PREFERENCES, budgetAlertThresholdPercent: 80 });
    const { setData, provider } = withClient();
    const { result } = renderHook(() => useUpdateNotificationPreferences(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({ budgetAlertThresholdPercent: 80 });
    });
    expect(setData).toHaveBeenCalledWith(
      ['notifications', 'preferences'],
      expect.objectContaining({ budgetAlertThresholdPercent: 80 })
    );
  });
});

describe('notificationKeys', () => {
  it('one root per resource', () => {
    expect(notificationKeys.list()).toEqual(['notifications', 'list']);
    expect(notificationKeys.unreadCount()).toEqual(['notifications', 'unread-count']);
    expect(notificationKeys.preferences()).toEqual(['notifications', 'preferences']);
  });
});

describe('no push notification, no device token, ever', () => {
  it('package.json declares no push-notification dependency', () => {
    const pkg = JSON.parse(
      fs.readFileSync(path.resolve(__dirname, '../../package.json'), 'utf8')
    ) as { dependencies?: Record<string, string>; devDependencies?: Record<string, string> };
    const deps = Object.keys({ ...pkg.dependencies, ...pkg.devDependencies });
    expect(deps).not.toEqual(expect.arrayContaining(['expo-notifications']));
  });

  it('the notifications api module exports no push-registration or device-token function', () => {
    // jest.requireActual, not the top-of-file jest.mock stub, so this reads
    // the module's real export list rather than the mocked jest.fn() map.
    const notifications = jest.requireActual('@api/notifications') as Record<string, unknown>;
    const pushLike = Object.keys(notifications).filter((name) =>
      /push|device|apns|fcm/i.test(name)
    );
    expect(pushLike).toEqual([]);
  });
});
