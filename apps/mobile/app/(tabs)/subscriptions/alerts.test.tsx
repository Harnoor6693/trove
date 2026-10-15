import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import SubscriptionAlertsScreen from './alerts';
import { fetchSubscriptionAlerts, markSubscriptionAlertRead } from '@api/subscriptions';
import type { SubscriptionAlertResponse } from '@api/types';

jest.mock('@api/subscriptions', () => ({
  fetchSubscriptions: jest.fn(),
  fetchSubscriptionSummary: jest.fn(),
  fetchSubscriptionAlerts: jest.fn(),
  markSubscriptionAlertRead: jest.fn(),
  scanSubscriptions: jest.fn(),
  fetchSubscription: jest.fn(),
  cancelSubscription: jest.fn(),
}));

const mockAlerts = fetchSubscriptionAlerts as jest.MockedFunction<typeof fetchSubscriptionAlerts>;
const mockMarkRead = markSubscriptionAlertRead as jest.MockedFunction<
  typeof markSubscriptionAlertRead
>;

const ALERT: SubscriptionAlertResponse = {
  id: 'al1',
  householdId: 'h1',
  createdBy: 'u1',
  subscriptionId: 's1',
  alertType: 'PRICE_INCREASE',
  alertMessage: 'SPOTIFY went up from 9.99 CAD to 11.99 CAD a month.',
  isRead: false,
  createdAt: '2026-09-05T02:00:00Z',
};

function renderScreen() {
  const client = createTestQueryClient();
  render(
    <QueryClientProvider client={client}>
      <SubscriptionAlertsScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

it('lists unread alerts with the server’s own wording', async () => {
  mockAlerts.mockResolvedValue([ALERT]);
  renderScreen();
  await waitFor(() => expect(screen.getByTestId('alert-row-al1')).toBeTruthy());
  expect(screen.getByText(ALERT.alertMessage)).toBeTruthy();
});

it('marks an alert read', async () => {
  mockAlerts.mockResolvedValue([ALERT]);
  mockMarkRead.mockResolvedValue({ ...ALERT, isRead: true });
  renderScreen();
  await waitFor(() => expect(screen.getByTestId('alert-row-al1-mark-read')).toBeTruthy());

  fireEvent.press(screen.getByTestId('alert-row-al1-mark-read'));

  await waitFor(() => expect(mockMarkRead).toHaveBeenCalledWith('al1'));
});
