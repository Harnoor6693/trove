import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { useLocalSearchParams, router } from 'expo-router';
import { createTestQueryClient } from '@/test/queryClient';
import NewEnvelopeScreen from './new';
import { fetchCategories } from '@api/categories';
import { createEnvelope } from '@api/budgets';
import type { CategoryResponse, EnvelopeResponse } from '@api/types';

jest.mock('@api/categories', () => ({ fetchCategories: jest.fn() }));
jest.mock('@api/budgets', () => ({ createEnvelope: jest.fn() }));

const mockCategories = fetchCategories as jest.MockedFunction<typeof fetchCategories>;
const mockCreate = createEnvelope as jest.MockedFunction<typeof createEnvelope>;
const mockParams = useLocalSearchParams as jest.MockedFunction<typeof useLocalSearchParams>;

const PERIOD_ID = '11111111-1111-4111-8111-111111111111';

const CATEGORY: CategoryResponse = {
  id: 'c1',
  householdId: 'h1',
  slug: 'food.groceries',
  name: 'Groceries',
  icon: null,
  parentId: null,
  isSystem: false,
  createdAt: '2026-09-04T14:00:00Z',
  updatedAt: '2026-09-04T14:00:00Z',
};

const CREATED: EnvelopeResponse = {
  id: 'e1',
  householdId: 'h1',
  periodId: PERIOD_ID,
  categoryId: 'c1',
  categoryName: 'Groceries',
  allocated: '400.0000',
  spent: '0.0000',
  rolloverAmount: '0.0000',
  currency: 'CAD',
  health: {
    healthPercentage: 0,
    paceMarkerPercentage: 50,
    overPace: false,
    remainingAmount: '400.0000',
    expectedSpend: '200.0000',
  },
  createdAt: '2026-09-04T14:00:00Z',
  updatedAt: '2026-09-04T14:00:00Z',
};

function renderScreen() {
  const client = createTestQueryClient();
  render(
    <QueryClientProvider client={client}>
      <NewEnvelopeScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  mockParams.mockReturnValue({ id: PERIOD_ID });
});

it('submits categoryId/allocated against the period from the path', async () => {
  mockCategories.mockResolvedValue([CATEGORY]);
  mockCreate.mockResolvedValue(CREATED);
  renderScreen();

  await waitFor(() => expect(screen.getByTestId('new-envelope-category-c1')).toBeTruthy());
  fireEvent.press(screen.getByTestId('new-envelope-category-c1'));
  fireEvent.changeText(screen.getByTestId('new-envelope-allocated'), '400.00');
  fireEvent.press(screen.getByTestId('new-envelope-submit'));

  await waitFor(() => expect(router.back).toHaveBeenCalled());
  expect(mockCreate).toHaveBeenCalledWith(PERIOD_ID, {
    categoryId: 'c1',
    allocated: '400.00',
    currency: undefined,
  });
});

it('reads the duplicate-envelope 409 in plain words', async () => {
  mockCategories.mockResolvedValue([CATEGORY]);
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
            detail: 'Already budgeted.',
          },
        },
      },
    })
  );
  renderScreen();
  await waitFor(() => expect(screen.getByTestId('new-envelope-category-c1')).toBeTruthy());
  fireEvent.press(screen.getByTestId('new-envelope-category-c1'));
  fireEvent.changeText(screen.getByTestId('new-envelope-allocated'), '1.00');
  fireEvent.press(screen.getByTestId('new-envelope-submit'));

  await waitFor(() =>
    expect(
      screen.getByText('This period already has an envelope for that category. Edit it instead.')
    ).toBeTruthy()
  );
});

it('treats a non-uuid periodId as not-found rather than rendering the create form', () => {
  mockParams.mockReturnValue({ id: '../accounts/1' });
  mockCategories.mockResolvedValue([CATEGORY]);
  renderScreen();

  expect(screen.getByText('That budget period could not be found.')).toBeTruthy();
  expect(screen.queryByTestId('new-envelope-submit')).toBeNull();
  expect(screen.queryByTestId('new-envelope-category-c1')).toBeNull();
  expect(mockCreate).not.toHaveBeenCalled();
});

it('surfaces the zero-based invariant as a field error on allocated', async () => {
  mockCategories.mockResolvedValue([CATEGORY]);
  mockCreate.mockRejectedValue(
    Object.assign(new Error('refused'), {
      isAxiosError: true,
      response: {
        status: 400,
        data: {
          success: false,
          error: {
            type: 'https://trove.finance/errors/validation-error',
            title: 'Bad request',
            status: 400,
            detail: 'Would break the sum invariant.',
            fieldErrors: [
              { field: 'allocated', message: 'Envelopes must sum to your total income.' },
            ],
          },
        },
      },
    })
  );
  renderScreen();
  await waitFor(() => expect(screen.getByTestId('new-envelope-category-c1')).toBeTruthy());
  fireEvent.press(screen.getByTestId('new-envelope-category-c1'));
  fireEvent.changeText(screen.getByTestId('new-envelope-allocated'), '999999.00');
  fireEvent.press(screen.getByTestId('new-envelope-submit'));

  await waitFor(() =>
    expect(screen.getByText('Envelopes must sum to your total income.')).toBeTruthy()
  );
});
