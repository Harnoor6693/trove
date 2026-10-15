import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { useLocalSearchParams, router } from 'expo-router';
import { createTestQueryClient } from '@/test/queryClient';
import BudgetPeriodScreen from './[id]';
import { fetchBudgetPeriod, fetchEnvelopes, deleteBudgetPeriod } from '@api/budgets';
import type { BudgetPeriodResponse } from '@api/types';

jest.mock('@api/budgets', () => ({
  fetchBudgetPeriod: jest.fn(),
  fetchEnvelopes: jest.fn(),
  updateBudgetPeriod: jest.fn(),
  deleteBudgetPeriod: jest.fn(),
}));

const mockFetch = fetchBudgetPeriod as jest.MockedFunction<typeof fetchBudgetPeriod>;
const mockEnvelopes = fetchEnvelopes as jest.MockedFunction<typeof fetchEnvelopes>;
const mockDelete = deleteBudgetPeriod as jest.MockedFunction<typeof deleteBudgetPeriod>;
const mockParams = useLocalSearchParams as jest.MockedFunction<typeof useLocalSearchParams>;

const ID = '11111111-1111-4111-8111-111111111111';

const PERIOD: BudgetPeriodResponse = {
  id: ID,
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
      <BudgetPeriodScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  mockParams.mockReturnValue({ id: ID });
});

it('fetches the period by id and renders it', async () => {
  mockFetch.mockResolvedValue(PERIOD);
  mockEnvelopes.mockResolvedValue([]);
  renderScreen();
  await waitFor(() => expect(screen.getByTestId('budget-period-detail')).toBeTruthy());
  expect(mockFetch).toHaveBeenCalledWith(ID);
});

it('navigates back after a confirmed delete', async () => {
  mockFetch.mockResolvedValue(PERIOD);
  mockEnvelopes.mockResolvedValue([]);
  mockDelete.mockResolvedValue(undefined);
  renderScreen();
  await waitFor(() => expect(screen.getByTestId('budget-period-detail-delete')).toBeTruthy());

  fireEvent.press(screen.getByTestId('budget-period-detail-delete'));
  fireEvent.press(screen.getByTestId('budget-period-detail-delete-confirm'));

  await waitFor(() => expect(router.back).toHaveBeenCalled());
});

it('treats a non-uuid param as not-found rather than querying the server', () => {
  mockParams.mockReturnValue({ id: '../accounts/1' });
  renderScreen();
  expect(screen.getByText('That budget period could not be found.')).toBeTruthy();
  expect(mockFetch).not.toHaveBeenCalled();
});
