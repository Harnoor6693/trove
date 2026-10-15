import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import { router } from 'expo-router';
import BudgetsHomeScreen from './index';
import { fetchCurrentBudgetPeriod } from '@api/budgets';
import { fetchEnvelopes } from '@api/budgets';
import type { BudgetPeriodResponse } from '@api/types';

jest.mock('@api/budgets', () => ({
  fetchCurrentBudgetPeriod: jest.fn(),
  fetchEnvelopes: jest.fn(),
  updateBudgetPeriod: jest.fn(),
  deleteBudgetPeriod: jest.fn(),
}));

const mockCurrent = fetchCurrentBudgetPeriod as jest.MockedFunction<
  typeof fetchCurrentBudgetPeriod
>;
const mockEnvelopes = fetchEnvelopes as jest.MockedFunction<typeof fetchEnvelopes>;

const PERIOD: BudgetPeriodResponse = {
  id: 'p1',
  householdId: 'h1',
  createdBy: 'u1',
  month: '2026-09-01',
  totalIncome: '5000.0000',
  currency: 'CAD',
  budgetType: 'FLEXIBLE',
  rolledOverAt: null,
  createdAt: '2026-09-04T14:00:00Z',
  updatedAt: '2026-09-04T14:00:00Z',
};

function renderScreen() {
  const client = createTestQueryClient();
  render(
    <QueryClientProvider client={client}>
      <BudgetsHomeScreen />
    </QueryClientProvider>
  );
  return client;
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe('the current-month period', () => {
  it('renders the period when one exists for this month', async () => {
    mockCurrent.mockResolvedValue(PERIOD);
    mockEnvelopes.mockResolvedValue([]);
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('budgets-current-period')).toBeTruthy());
    expect(screen.queryByTestId('budgets-empty-month')).toBeNull();
  });
});

describe('no budget for this month — the 404 empty state', () => {
  it('renders "create one" rather than a generic error', async () => {
    mockCurrent.mockRejectedValue(
      Object.assign(new Error('not found'), {
        isAxiosError: true,
        response: {
          status: 404,
          data: {
            success: false,
            error: {
              type: 'https://trove.finance/errors/not-found',
              title: 'Not found',
              status: 404,
              detail: 'No budget period for this month.',
            },
          },
        },
      })
    );
    renderScreen();

    await waitFor(() => expect(screen.getByTestId('budgets-empty-month')).toBeTruthy());
    expect(screen.queryByTestId('budgets-current-error')).toBeNull();
    expect(screen.getByText('No budget for this month yet.')).toBeTruthy();

    fireEvent.press(screen.getByTestId('budgets-create-cta'));
    expect(router.push).toHaveBeenCalledWith('/(tabs)/budgets/new');
  });
});

describe('a 429 from the chain-wide limiter', () => {
  it('shows the wait-and-retry state rather than a generic failure', async () => {
    mockCurrent.mockRejectedValue(
      Object.assign(new Error('rate limited'), {
        isAxiosError: true,
        response: {
          status: 429,
          headers: { 'retry-after': '30' },
          data: {
            success: false,
            error: {
              type: 'https://trove.finance/errors/rate-limited',
              title: 'Too many requests',
              status: 429,
              detail: 'Slow down.',
            },
          },
        },
      })
    );
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('budgets-current-error')).toBeTruthy());
    expect(screen.getByText('Too many requests. Try again in 30 seconds.')).toBeTruthy();
    // Not the 404 empty state, and no navigation away — a 429 never clears the session.
    expect(screen.queryByTestId('budgets-empty-month')).toBeNull();
  });
});

describe('navigation', () => {
  it('links to periods, goals, categories and merchant rules', async () => {
    mockCurrent.mockResolvedValue(PERIOD);
    mockEnvelopes.mockResolvedValue([]);
    renderScreen();
    await waitFor(() => expect(screen.getByTestId('budgets-current-period')).toBeTruthy());

    fireEvent.press(screen.getByTestId('budgets-nav-periods'));
    expect(router.push).toHaveBeenCalledWith('/(tabs)/budgets/periods');
    fireEvent.press(screen.getByTestId('budgets-nav-goals'));
    expect(router.push).toHaveBeenCalledWith('/(tabs)/budgets/goals');
    fireEvent.press(screen.getByTestId('budgets-nav-categories'));
    expect(router.push).toHaveBeenCalledWith('/(tabs)/budgets/categories');
    fireEvent.press(screen.getByTestId('budgets-nav-rules'));
    expect(router.push).toHaveBeenCalledWith('/(tabs)/budgets/rules');
  });
});
