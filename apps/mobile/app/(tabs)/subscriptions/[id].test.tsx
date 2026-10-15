import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { useLocalSearchParams } from 'expo-router';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import SubscriptionDetailScreen from './[id]';
import { cancelSubscription, fetchSubscription } from '@api/subscriptions';
import type { SubscriptionResponse } from '@api/types';

jest.mock('@api/subscriptions', () => ({
  fetchSubscriptions: jest.fn(),
  fetchSubscriptionSummary: jest.fn(),
  fetchSubscriptionAlerts: jest.fn(),
  markSubscriptionAlertRead: jest.fn(),
  scanSubscriptions: jest.fn(),
  fetchSubscription: jest.fn(),
  cancelSubscription: jest.fn(),
}));

const mockOne = fetchSubscription as jest.MockedFunction<typeof fetchSubscription>;
const mockCancel = cancelSubscription as jest.MockedFunction<typeof cancelSubscription>;
const mockParams = useLocalSearchParams as jest.MockedFunction<typeof useLocalSearchParams>;

const SUBSCRIPTION: SubscriptionResponse = {
  id: '00000000-0000-4000-8000-000000000001',
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

async function renderScreen() {
  const client = createTestQueryClient();
  await render(
    <QueryClientProvider client={client}>
      <SubscriptionDetailScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  mockParams.mockReturnValue({ id: '00000000-0000-4000-8000-000000000001' });
});

it('renders the previous price when a change is on record', async () => {
  mockOne.mockResolvedValue(SUBSCRIPTION);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('subscription-previous-amount')).toBeTruthy());
});

it('the cancel confirmation states plainly that the merchant is not contacted', async () => {
  mockOne.mockResolvedValue(SUBSCRIPTION);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('subscription-cancel-start')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('subscription-cancel-start'));

  const explainer = screen.getByTestId('subscription-cancel-explainer');
  expect(explainer).toBeTruthy();
  const children = explainer.props.children as string | string[];
  const text = Array.isArray(children) ? children.join('') : children;
  expect(text).toMatch(/never contacts the merchant/i);
  expect(text).toMatch(/nothing is cancelled with them/i);
  expect(mockCancel).not.toHaveBeenCalled();
});

it('cancels only after the second, explicit confirmation', async () => {
  mockOne.mockResolvedValue(SUBSCRIPTION);
  mockCancel.mockResolvedValue(undefined);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('subscription-cancel-start')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('subscription-cancel-start'));
  await fireEvent.press(screen.getByTestId('subscription-cancel-confirm'));

  await waitFor(() =>
    expect(mockCancel).toHaveBeenCalledWith('00000000-0000-4000-8000-000000000001')
  );
});

it('a cancelled subscription is shown as cancelled and offers no further cancel action', async () => {
  mockOne.mockResolvedValue({ ...SUBSCRIPTION, cancelled: true });
  await renderScreen();
  await waitFor(() => expect(screen.getByText('Cancelled')).toBeTruthy());
  expect(screen.queryByTestId('subscription-cancel-start')).toBeNull();
});

it('never issues a request for a pasted or deep-linked value that is not a uuid', async () => {
  mockParams.mockReturnValue({ id: '../../v1/webhooks' });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('subscription-detail-screen')).toBeTruthy());
  expect(mockOne).not.toHaveBeenCalled();
});
