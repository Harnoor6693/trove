import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { router } from 'expo-router';
import { createTestQueryClient } from '@/test/queryClient';
import { NotificationsCard } from './NotificationsCard';
import {
  fetchNotificationPreferences,
  fetchUnreadNotificationCount,
  updateNotificationPreferences,
} from '@api/notifications';
import type { NotificationPreferencesResponse } from '@api/types';

jest.mock('@api/notifications', () => ({
  fetchNotifications: jest.fn(),
  fetchUnreadNotificationCount: jest.fn(),
  markNotificationRead: jest.fn(),
  markAllNotificationsRead: jest.fn(),
  dismissNotification: jest.fn(),
  fetchNotificationPreferences: jest.fn(),
  updateNotificationPreferences: jest.fn(),
}));

const mockUnread = fetchUnreadNotificationCount as jest.MockedFunction<
  typeof fetchUnreadNotificationCount
>;
const mockPreferences = fetchNotificationPreferences as jest.MockedFunction<
  typeof fetchNotificationPreferences
>;
const mockUpdate = updateNotificationPreferences as jest.MockedFunction<
  typeof updateNotificationPreferences
>;

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

async function renderCard() {
  const client = createTestQueryClient();
  await render(
    <QueryClientProvider client={client}>
      <NotificationsCard />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

it('shows the unread count', async () => {
  mockUnread.mockResolvedValue({ unread: 3 });
  mockPreferences.mockResolvedValue(PREFERENCES);
  await renderCard();
  await waitFor(() => expect(screen.getByTestId('settings-notifications-unread')).toBeTruthy());
  expect(screen.getByText('3 unread')).toBeTruthy();
});

it('opens the inbox', async () => {
  mockUnread.mockResolvedValue({ unread: 0 });
  mockPreferences.mockResolvedValue(PREFERENCES);
  await renderCard();
  await waitFor(() => expect(screen.getByTestId('settings-notifications-open-inbox')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('settings-notifications-open-inbox'));

  expect(router.push).toHaveBeenCalledWith('/(tabs)/net-worth/notifications');
});

it('saves preferences, clearing the email address with an explicit null when left blank', async () => {
  mockUnread.mockResolvedValue({ unread: 0 });
  mockPreferences.mockResolvedValue({ ...PREFERENCES, emailAddress: 'old@example.test' });
  mockUpdate.mockResolvedValue(PREFERENCES);
  await renderCard();
  await waitFor(() => expect(screen.getByTestId('settings-notifications-save')).toBeTruthy());

  await fireEvent.changeText(screen.getByTestId('settings-notifications-email-address'), '');
  await fireEvent.press(screen.getByTestId('settings-notifications-save'));

  await waitFor(() =>
    expect(mockUpdate).toHaveBeenCalledWith(
      expect.objectContaining({ emailAddress: null, budgetAlertThresholdPercent: 90 })
    )
  );
});

it('does not render the form before the preferences row has loaded', async () => {
  mockUnread.mockResolvedValue({ unread: 0 });
  mockPreferences.mockImplementation(() => new Promise(() => undefined));
  await renderCard();
  expect(screen.getByTestId('settings-notifications-loading')).toBeTruthy();
  expect(screen.queryByTestId('settings-notifications-save')).toBeNull();
});
