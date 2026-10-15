import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import { router } from 'expo-router';
import BudgetPeriodsScreen from './periods';
import { fetchBudgetPeriods } from '@api/budgets';
import type { BudgetPeriodResponse } from '@api/types';

jest.mock('@api/budgets', () => ({ fetchBudgetPeriods: jest.fn() }));
const mockList = fetchBudgetPeriods as jest.MockedFunction<typeof fetchBudgetPeriods>;

function period(id: string, month: string): BudgetPeriodResponse {
  return {
    id,
    householdId: 'h1',
    createdBy: 'u1',
    month,
    totalIncome: '5000.0000',
    currency: 'CAD',
    budgetType: 'FLEXIBLE',
    rolledOverAt: null,
    createdAt: '2026-09-04T14:00:00Z',
    updatedAt: '2026-09-04T14:00:00Z',
  };
}

async function renderScreen() {
  const client = createTestQueryClient();
  await render(
    <QueryClientProvider client={client}>
      <BudgetPeriodsScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

it('lists every period and navigates to one on tap', async () => {
  mockList.mockResolvedValue([period('p1', '2026-09-01'), period('p2', '2026-08-01')]);
  await renderScreen();

  await waitFor(() => expect(screen.getByTestId('budget-period-row-p1')).toBeTruthy());
  expect(screen.getByTestId('budget-period-row-p2')).toBeTruthy();

  await fireEvent.press(screen.getByTestId('budget-period-row-p1'));
  expect(router.push).toHaveBeenCalledWith('/(tabs)/budgets/period/p1');
});

it('shows an empty message when the household has no periods yet', async () => {
  mockList.mockResolvedValue([]);
  await renderScreen();
  await waitFor(() => expect(screen.getByText('No budget periods yet.')).toBeTruthy());
});
