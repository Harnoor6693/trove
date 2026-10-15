import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import { router } from 'expo-router';
import NewBudgetPeriodScreen from './new';
import { createBudgetPeriod } from '@api/budgets';
import type { BudgetPeriodResponse } from '@api/types';

jest.mock('@api/budgets', () => ({ createBudgetPeriod: jest.fn() }));
const mockCreate = createBudgetPeriod as jest.MockedFunction<typeof createBudgetPeriod>;

const CREATED: BudgetPeriodResponse = {
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

async function renderScreen() {
  const client = createTestQueryClient();
  await render(
    <QueryClientProvider client={client}>
      <NewBudgetPeriodScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

it('defaults the period start to the local calendar day, not the UTC one (B5-10)', async () => {
  jest.useFakeTimers({ doNotFake: ['nextTick', 'setImmediate', 'queueMicrotask'] });
  jest.setSystemTime(new Date(2026, 9, 31, 0, 30));
  try {
    await renderScreen();
    expect(screen.getByTestId('new-budget-month').props.value).toBe('2026-10-31');
  } finally {
    jest.useRealTimers();
  }
});

it('submits month/totalIncome and leaves currency/budgetType unset by default', async () => {
  mockCreate.mockResolvedValue(CREATED);
  await renderScreen();

  await fireEvent.changeText(screen.getByTestId('new-budget-income'), '5000.00');
  await fireEvent.press(screen.getByTestId('new-budget-submit'));

  await waitFor(() => expect(router.back).toHaveBeenCalled());
  expect(mockCreate).toHaveBeenCalledWith(
    expect.objectContaining({ totalIncome: '5000.00', currency: undefined, budgetType: undefined })
  );
});

it('lets the member pick a currency and a budget type, and toggle them back off', async () => {
  mockCreate.mockResolvedValue(CREATED);
  await renderScreen();

  await fireEvent.press(screen.getByTestId('new-budget-currency-USD'));
  await fireEvent.press(screen.getByTestId('new-budget-type-ZERO_BASED'));
  await fireEvent.changeText(screen.getByTestId('new-budget-income'), '1000.00');
  await fireEvent.press(screen.getByTestId('new-budget-submit'));

  await waitFor(() =>
    expect(mockCreate).toHaveBeenCalledWith(
      expect.objectContaining({ currency: 'USD', budgetType: 'ZERO_BASED' })
    )
  );
});

it('reads the household-already-has-a-period 409 in plain words', async () => {
  mockCreate.mockRejectedValue(
    Object.assign(new Error('conflict'), {
      isAxiosError: true,
      response: {
        status: 409,
        data: {
          success: false,
          error: {
            type: 'https://trove.finance/errors/conflict',
            title: 'Conflict',
            status: 409,
            detail: 'A period for that month already exists.',
          },
        },
      },
    })
  );
  await renderScreen();
  await fireEvent.changeText(screen.getByTestId('new-budget-income'), '5000.00');
  await fireEvent.press(screen.getByTestId('new-budget-submit'));

  await waitFor(() =>
    expect(
      screen.getByText(
        'Your household already has a budget for that month. Open it from All periods instead.'
      )
    ).toBeTruthy()
  );
});
