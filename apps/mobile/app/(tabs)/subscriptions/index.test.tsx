import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import SubscriptionsScreen from './index';
import {
  fetchSubscriptionAlerts,
  fetchSubscriptionSummary,
  fetchSubscriptions,
  scanSubscriptions,
} from '@api/subscriptions';
import type { SubscriptionResponse, SubscriptionSummaryResponse } from '@api/types';

jest.mock('@api/subscriptions', () => ({
  fetchSubscriptions: jest.fn(),
  fetchSubscriptionSummary: jest.fn(),
  fetchSubscriptionAlerts: jest.fn(),
  markSubscriptionAlertRead: jest.fn(),
  scanSubscriptions: jest.fn(),
  fetchSubscription: jest.fn(),
  cancelSubscription: jest.fn(),
}));

const mockList = fetchSubscriptions as jest.MockedFunction<typeof fetchSubscriptions>;
const mockSummary = fetchSubscriptionSummary as jest.MockedFunction<
  typeof fetchSubscriptionSummary
>;
const mockAlerts = fetchSubscriptionAlerts as jest.MockedFunction<typeof fetchSubscriptionAlerts>;
const mockScan = scanSubscriptions as jest.MockedFunction<typeof scanSubscriptions>;

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

const SUMMARY: SubscriptionSummaryResponse = {
  currency: 'CAD',
  activeCount: 2,
  flaggedCount: 1,
  byCurrency: [
    { currency: 'CAD', monthlyRunRate: '11.9900', activeCount: 1, flaggedCount: 1 },
    { currency: 'USD', monthlyRunRate: '9.9900', activeCount: 1, flaggedCount: 0 },
  ],
  next14Days: [],
};

async function renderScreen() {
  const client = createTestQueryClient();
  await render(
    <QueryClientProvider client={client}>
      <SubscriptionsScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

it('renders the summary with each currency in its own card — never one blended figure', async () => {
  mockList.mockResolvedValue([SUBSCRIPTION]);
  mockSummary.mockResolvedValue(SUMMARY);
  mockAlerts.mockResolvedValue([]);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('subscriptions-summary-CAD')).toBeTruthy());
  expect(screen.getByTestId('subscriptions-summary-USD')).toBeTruthy();
  expect(screen.queryByText(/total/i)).toBeNull();
});

it('lists detected subscriptions with signal and amount', async () => {
  mockList.mockResolvedValue([SUBSCRIPTION]);
  mockSummary.mockResolvedValue(SUMMARY);
  mockAlerts.mockResolvedValue([]);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('subscription-row-s1')).toBeTruthy());
});

it('triggers a manual scan', async () => {
  mockList.mockResolvedValue([]);
  mockSummary.mockResolvedValue({ ...SUMMARY, byCurrency: [] });
  mockAlerts.mockResolvedValue([]);
  mockScan.mockResolvedValue([SUBSCRIPTION]);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('subscriptions-scan-btn')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('subscriptions-scan-btn'));

  await waitFor(() => expect(mockScan).toHaveBeenCalled());
});

it('renders next14Days upcoming charges, grouped by date with per-currency totals', async () => {
  mockList.mockResolvedValue([SUBSCRIPTION]);
  mockAlerts.mockResolvedValue([]);
  mockSummary.mockResolvedValue({
    ...SUMMARY,
    next14Days: [
      {
        date: '2026-09-12',
        subscriptions: [SUBSCRIPTION, { ...SUBSCRIPTION, id: 's2', currency: 'USD' }],
        totals: [
          { currency: 'CAD', amount: '11.9900' },
          { currency: 'USD', amount: '5.0000' },
        ],
      },
    ],
  });
  await renderScreen();

  await waitFor(() => expect(screen.getByTestId('subscriptions-upcoming-2026-09-12')).toBeTruthy());
  expect(screen.getByTestId('subscriptions-upcoming-2026-09-12-sub-s1')).toBeTruthy();
  expect(screen.getByTestId('subscriptions-upcoming-2026-09-12-sub-s2')).toBeTruthy();
  // Both currencies' totals for that day are rendered — never blended into one figure.
  expect(screen.getByTestId('subscriptions-upcoming-2026-09-12-total-CAD')).toBeTruthy();
  expect(screen.getByTestId('subscriptions-upcoming-2026-09-12-total-USD')).toBeTruthy();
});

it('renders no upcoming-charges section when next14Days is empty', async () => {
  mockList.mockResolvedValue([]);
  mockAlerts.mockResolvedValue([]);
  mockSummary.mockResolvedValue({ ...SUMMARY, byCurrency: [], next14Days: [] });
  await renderScreen();

  await waitFor(() => expect(screen.getByTestId('subscriptions-list-empty')).toBeTruthy());
  expect(screen.queryByTestId('subscriptions-upcoming')).toBeNull();
});

it('shows the unread alert count as a badge', async () => {
  mockList.mockResolvedValue([]);
  mockSummary.mockResolvedValue({ ...SUMMARY, byCurrency: [] });
  mockAlerts.mockResolvedValue([
    {
      id: 'al1',
      householdId: 'h1',
      createdBy: 'u1',
      subscriptionId: 's1',
      alertType: 'PRICE_INCREASE',
      alertMessage: 'x',
      isRead: false,
      createdAt: '2026-09-05T02:00:00Z',
    },
  ]);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('subscriptions-alert-badge')).toBeTruthy());
  expect(screen.getByText('1')).toBeTruthy();
});
