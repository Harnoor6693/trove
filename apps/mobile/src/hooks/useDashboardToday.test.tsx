import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import { dashboardKeys, useDashboardToday } from './useDashboardToday';
import { fetchDashboardToday } from '@api/dashboard';
import type { DashboardTodayResponse } from '@api/types';

jest.mock('@api/dashboard', () => ({ fetchDashboardToday: jest.fn() }));

const mockToday = fetchDashboardToday as jest.MockedFunction<typeof fetchDashboardToday>;

const TODAY: DashboardTodayResponse = {
  date: '2026-09-04',
  timezone: 'America/Vancouver',
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

let clients: ReturnType<typeof createTestQueryClient>[] = [];

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = createTestQueryClient();
  clients.push(qc);
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  jest.clearAllMocks();
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('dashboardKeys', () => {
  it('keys today under the dashboard root a mutation invalidates', () => {
    expect(dashboardKeys.all()).toEqual(['dashboard']);
    expect(dashboardKeys.today()).toEqual(['dashboard', 'today']);
  });
});

describe('useDashboardToday', () => {
  it('returns the server-computed day, its zone and every currency subtotal', async () => {
    mockToday.mockResolvedValue(TODAY);
    const { result } = await renderHook(() => useDashboardToday(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual(TODAY));
    expect(result.current.data?.timezone).toBe('America/Vancouver');
    expect(result.current.data?.byCurrency).toHaveLength(2);
  });

  it('surfaces a failure rather than zeroed figures', async () => {
    mockToday.mockRejectedValue(new Error('boom'));
    const { result } = await renderHook(() => useDashboardToday(), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
  });
});
