import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import TodayScreen from './index';
import { fetchDashboardToday } from '@api/dashboard';
import { useAuthStore } from '@stores/authStore';
import type { DashboardTodayResponse, MeResponse } from '@api/types';

/**
 * The endpoint module is stubbed; the query client and both zustand stores are
 * real (mobile inventory §4 — the over-mocked store tests are exactly what
 * this app removed).
 */
jest.mock('@api/dashboard', () => ({ fetchDashboardToday: jest.fn() }));

const mockToday = fetchDashboardToday as jest.MockedFunction<typeof fetchDashboardToday>;

const ME: MeResponse = {
  userId: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada',
  householdId: 'h1',
  role: 'ADMIN',
  totpEnabled: false,
  currency: 'CAD',
  country: 'CA',
  timezone: 'America/Toronto',
  locale: 'en-CA',
  onboardingCompleted: true,
};

const TODAY: DashboardTodayResponse = {
  date: '2026-09-04',
  // Deliberately NOT the member's `me.timezone` above: the figure boundaries
  // are the server's own computation and the screen must render what it sent.
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

/**
 * Settle the screen's queries inside `act()` before asserting.
 *
 * React Query batches its notifications through a `setTimeout(0)`, so a bare
 * `await Promise.resolve()` returns before the store update lands and React
 * warns that state changed outside `act`. Draining the macrotask queue inside
 * `act` makes every assertion below a synchronous fact rather than a race on
 * `waitFor`'s one-second budget — the same reason `settings.test.tsx` flushes
 * its four queries up front.
 */
async function flush(): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => setImmediate(resolve));
  });
}

async function renderToday() {
  const qc = createTestQueryClient();
  clients.push(qc);
  const utils = render(
    <QueryClientProvider client={qc}>
      <TodayScreen />
    </QueryClientProvider>
  );
  await flush();
  return utils;
}

beforeEach(() => {
  jest.clearAllMocks();
  act(() => {
    useAuthStore.getState().setMe(ME);
  });
});

afterEach(async () => {
  // Drain the updates a settled mutation schedules — an invalidation, and the
  // refetch it triggers — inside `act`, before the client is torn down. Without
  // this the render that lands after the test body warns about an update
  // outside `act`, which is a real "the test finished before the screen did"
  // signal and not just noise.
  await act(async () => {
    await new Promise((resolve) => setImmediate(resolve));
  });
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('Today', () => {
  it('renders one card per byCurrency entry, each labelled with its own code', async () => {
    mockToday.mockResolvedValue(TODAY);
    await renderToday();
    await waitFor(() => expect(screen.getByTestId('today-currency-CAD')).toBeTruthy());
    expect(screen.getByTestId('today-currency-USD')).toBeTruthy();
    expect(screen.getByTestId('today-currency-CAD-code').props.children).toBe('CAD');
    expect(screen.getByTestId('today-currency-USD-code').props.children).toBe('USD');
  });

  it('NEVER blends the two currencies into one total', async () => {
    mockToday.mockResolvedValue(TODAY);
    await renderToday();
    await waitFor(() => expect(screen.getByTestId('today-currency-USD')).toBeTruthy());

    // 12.34 + 40.00 = 52.34, and 480.00 + 90.00 = 570.00. Neither sum exists
    // anywhere on this screen, and there is no exchange rate in this product
    // that could justify one.
    expect(screen.queryByText(/52\.34/)).toBeNull();
    expect(screen.queryByText(/570\.00/)).toBeNull();
    expect(screen.queryByText(/2410\.00/)).toBeNull();

    // Each subtotal is rendered under its own currency and nowhere else.
    const cad = screen.getByTestId('today-currency-CAD-spent-today').props.children as string;
    const usd = screen.getByTestId('today-currency-USD-spent-today').props.children as string;
    expect(cad).toContain('12.34');
    expect(usd).toContain('40.00');
  });

  it('shows the day and the zone the SERVER computed, not the device’s', async () => {
    mockToday.mockResolvedValue(TODAY);
    await renderToday();
    await waitFor(() => expect(screen.getByTestId('today-timezone')).toBeTruthy());
    expect(screen.getByTestId('today-timezone').props.children).toBe('America/Vancouver');
    // September 4th 2026, rendered from `date` rather than from `new Date()`.
    expect(screen.getByTestId('today-date').props.children).toContain('2026');
  });

  it('shows the empty state when the household holds nothing yet', async () => {
    mockToday.mockResolvedValue({ ...TODAY, byCurrency: [] });
    await renderToday();
    await waitFor(() => expect(screen.getByTestId('today-empty')).toBeTruthy());
  });

  it('shows a 429 as a wait-and-retry state carrying the real number of seconds', async () => {
    const { AxiosError } = jest.requireActual('axios') as typeof import('axios');
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const config = { headers: {} } as any;
    mockToday.mockRejectedValue(
      new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, {
        status: 429,
        statusText: '',
        headers: { 'retry-after': '30' },
        config,
        data: {
          success: false,
          error: {
            type: 'https://trove.finance/errors/rate-limited',
            title: 'rate-limited',
            status: 429,
            detail: 'Too many requests.',
          },
        },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
      } as any)
    );

    await renderToday();
    await waitFor(() => expect(screen.getByTestId('today-error-message')).toBeTruthy());
    expect(screen.getByTestId('today-error-message').props.children).toBe(
      'Too many requests. Try again in 30 seconds.'
    );
    // A 429 is not a logout: the session is untouched.
    expect(useAuthStore.getState().me).toEqual(ME);
  });

  it('offers the way through to transactions and accounts', async () => {
    mockToday.mockResolvedValue(TODAY);
    await renderToday();
    expect(screen.getByTestId('today-to-transactions')).toBeTruthy();
    expect(screen.getByTestId('today-to-accounts')).toBeTruthy();
  });
});
