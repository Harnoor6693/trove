import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import NotificationsInboxScreen from './notifications';
import {
  dismissNotification,
  fetchNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from '@api/notifications';
import type { NotificationResponse } from '@api/types';

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
const mockMarkRead = markNotificationRead as jest.MockedFunction<typeof markNotificationRead>;
const mockMarkAll = markAllNotificationsRead as jest.MockedFunction<
  typeof markAllNotificationsRead
>;
const mockDismiss = dismissNotification as jest.MockedFunction<typeof dismissNotification>;

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

async function renderScreen() {
  const client = createTestQueryClient();
  await render(
    <QueryClientProvider client={client}>
      <NotificationsInboxScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

it('pages the inbox — the fetch carries page and the totals show', async () => {
  mockList.mockResolvedValue({ data: [NOTIFICATION], meta: { total: 1, page: 0, limit: 20 } });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('notification-row-n1')).toBeTruthy());
  expect(mockList).toHaveBeenCalledWith({ page: 0 });
  expect(screen.getByTestId('notifications-count')).toBeTruthy();
});

it('marks a notification read on press', async () => {
  mockList.mockResolvedValue({ data: [NOTIFICATION], meta: { total: 1, page: 0, limit: 20 } });
  mockMarkRead.mockResolvedValue({ ...NOTIFICATION, read: true });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('notification-row-n1-open')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('notification-row-n1-open'));

  await waitFor(() => expect(mockMarkRead).toHaveBeenCalledWith('n1'));
});

it('dismisses a notification', async () => {
  mockList.mockResolvedValue({ data: [NOTIFICATION], meta: { total: 1, page: 0, limit: 20 } });
  mockDismiss.mockResolvedValue(undefined);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('notification-row-n1-dismiss')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('notification-row-n1-dismiss'));

  await waitFor(() => expect(mockDismiss).toHaveBeenCalledWith('n1'));
});

it('marks every notification read from the header button', async () => {
  mockList.mockResolvedValue({ data: [NOTIFICATION], meta: { total: 1, page: 0, limit: 20 } });
  mockMarkAll.mockResolvedValue({ marked: 1 });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('notifications-mark-all-read')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('notifications-mark-all-read'));

  await waitFor(() => expect(mockMarkAll).toHaveBeenCalled());
});
