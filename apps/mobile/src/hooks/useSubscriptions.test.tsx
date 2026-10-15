import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import {
  subscriptionKeys,
  useCancelSubscription,
  useMarkSubscriptionAlertRead,
  useScanSubscriptions,
  useSubscription,
  useSubscriptionAlerts,
  useSubscriptionSummary,
  useSubscriptions,
} from './useSubscriptions';
import {
  cancelSubscription,
  fetchSubscription,
  fetchSubscriptionAlerts,
  fetchSubscriptionSummary,
  fetchSubscriptions,
  markSubscriptionAlertRead,
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
const mockMarkAlertRead = markSubscriptionAlertRead as jest.MockedFunction<
  typeof markSubscriptionAlertRead
>;
const mockScan = scanSubscriptions as jest.MockedFunction<typeof scanSubscriptions>;
const mockOne = fetchSubscription as jest.MockedFunction<typeof fetchSubscription>;
const mockCancel = cancelSubscription as jest.MockedFunction<typeof cancelSubscription>;

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
  activeCount: 1,
  flaggedCount: 1,
  byCurrency: [{ currency: 'CAD', monthlyRunRate: '11.9900', activeCount: 1, flaggedCount: 1 }],
  next14Days: [],
};

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
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('subscriptionKeys', () => {
  it('one root per resource, a detail key per subscription', () => {
    expect(subscriptionKeys.list()).toEqual(['subscriptions', 'list']);
    expect(subscriptionKeys.summary()).toEqual(['subscriptions', 'summary']);
    expect(subscriptionKeys.alerts()).toEqual(['subscriptions', 'alerts']);
    expect(subscriptionKeys.detail('s1')).toEqual(['subscriptions', 's1']);
  });
});

describe('useSubscriptions', () => {
  it('returns the bare array — not paged', async () => {
    mockList.mockResolvedValue([SUBSCRIPTION]);
    const { result } = renderHook(() => useSubscriptions(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([SUBSCRIPTION]));
  });
});

describe('useSubscriptionSummary', () => {
  it('renders each currency separately — byCurrency carries the whole figure', async () => {
    mockSummary.mockResolvedValue(SUMMARY);
    const { result } = renderHook(() => useSubscriptionSummary(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual(SUMMARY));
    expect(result.current.data?.byCurrency).toHaveLength(1);
    expect(result.current.data).not.toHaveProperty('totalMonthlyRunRate');
  });
});

describe('useSubscriptionAlerts / useMarkSubscriptionAlertRead', () => {
  it('lists unread alerts', async () => {
    mockAlerts.mockResolvedValue([]);
    const { result } = renderHook(() => useSubscriptionAlerts(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([]));
  });

  it('marking one read invalidates the alert list', async () => {
    mockMarkAlertRead.mockResolvedValue({
      id: 'al1',
      householdId: 'h1',
      createdBy: 'u1',
      subscriptionId: 's1',
      alertType: 'PRICE_INCREASE',
      alertMessage: 'x',
      isRead: true,
      createdAt: '2026-09-05T02:00:00Z',
    });
    const { invalidate, provider } = withClient();
    const { result } = renderHook(() => useMarkSubscriptionAlertRead(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('al1');
    });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual([
      'subscriptions',
      'alerts',
    ]);
  });
});

describe('useScanSubscriptions', () => {
  it('seeds the list cache with the fresh result and invalidates summary+alerts', async () => {
    mockScan.mockResolvedValue([SUBSCRIPTION]);
    const { invalidate, setData, provider } = withClient();
    const { result } = renderHook(() => useScanSubscriptions(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync();
    });
    expect(setData).toHaveBeenCalledWith(['subscriptions', 'list'], [SUBSCRIPTION]);
    const invalidated = invalidate.mock.calls.map(([arg]) => arg?.queryKey);
    expect(invalidated).toContainEqual(['subscriptions', 'summary']);
    expect(invalidated).toContainEqual(['subscriptions', 'alerts']);
  });
});

describe('useSubscription', () => {
  it('is idle until an id is known', () => {
    const { result } = renderHook(() => useSubscription(''), { wrapper });
    expect(result.current.fetchStatus).toBe('idle');
    expect(mockOne).not.toHaveBeenCalled();
  });

  it('a cancelled subscription is still readable', async () => {
    mockOne.mockResolvedValue({ ...SUBSCRIPTION, cancelled: true });
    const { result } = renderHook(() => useSubscription('s1'), { wrapper });
    await waitFor(() => expect(result.current.data?.cancelled).toBe(true));
  });
});

describe('useCancelSubscription', () => {
  it('invalidates the list, the summary and this subscription’s own detail', async () => {
    mockCancel.mockResolvedValue(undefined);
    const { invalidate, provider } = withClient();
    const { result } = renderHook(() => useCancelSubscription(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('s1');
    });
    const invalidated = invalidate.mock.calls.map(([arg]) => arg?.queryKey);
    expect(invalidated).toContainEqual(['subscriptions', 'list']);
    expect(invalidated).toContainEqual(['subscriptions', 'summary']);
    expect(invalidated).toContainEqual(['subscriptions', 's1']);
  });
});
