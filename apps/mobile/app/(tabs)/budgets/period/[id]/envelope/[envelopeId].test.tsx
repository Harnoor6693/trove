import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { useLocalSearchParams, router } from 'expo-router';
import { createTestQueryClient } from '@/test/queryClient';
import EnvelopeDetailScreen from './[envelopeId]';
import { fetchEnvelope, updateEnvelope, deleteEnvelope } from '@api/budgets';
import type { EnvelopeResponse } from '@api/types';

jest.mock('@api/budgets', () => ({
  fetchEnvelope: jest.fn(),
  updateEnvelope: jest.fn(),
  deleteEnvelope: jest.fn(),
}));

const mockFetch = fetchEnvelope as jest.MockedFunction<typeof fetchEnvelope>;
const mockUpdate = updateEnvelope as jest.MockedFunction<typeof updateEnvelope>;
const mockDelete = deleteEnvelope as jest.MockedFunction<typeof deleteEnvelope>;
const mockParams = useLocalSearchParams as jest.MockedFunction<typeof useLocalSearchParams>;

const PERIOD_ID = '11111111-1111-4111-8111-111111111111';
const ENVELOPE_ID = '22222222-2222-4222-8222-222222222222';

const ENVELOPE: EnvelopeResponse = {
  id: ENVELOPE_ID,
  householdId: 'h1',
  periodId: PERIOD_ID,
  categoryId: 'c1',
  categoryName: 'Groceries',
  allocated: '400.0000',
  spent: '250.0000',
  rolloverAmount: '0.0000',
  currency: 'CAD',
  health: {
    healthPercentage: 62,
    paceMarkerPercentage: 50,
    overPace: true,
    remainingAmount: '150.0000',
    expectedSpend: '200.0000',
  },
  createdAt: '2026-09-04T14:00:00Z',
  updatedAt: '2026-09-04T14:00:00Z',
};

function renderScreen() {
  const client = createTestQueryClient();
  render(
    <QueryClientProvider client={client}>
      <EnvelopeDetailScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  mockParams.mockReturnValue({ id: PERIOD_ID, envelopeId: ENVELOPE_ID });
});

it('renders allocated/spent/remaining and health exactly as the response carries them', async () => {
  mockFetch.mockResolvedValue(ENVELOPE);
  renderScreen();
  await waitFor(() => expect(screen.getByTestId('envelope-category')).toBeTruthy());

  // The figures shown come only from the response — no client-computed
  // percentage other than the ones the server already sent.
  expect(screen.getByText('62% spent')).toBeTruthy();
  expect(screen.getByText(/50% of the month has passed/)).toBeTruthy();
  expect(mockFetch).toHaveBeenCalledWith(PERIOD_ID, ENVELOPE_ID);
});

it('edits allocated only, and surfaces the zero-based invariant on that field', async () => {
  mockFetch.mockResolvedValue(ENVELOPE);
  mockUpdate.mockRejectedValue(
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
  await waitFor(() => expect(screen.getByTestId('envelope-edit')).toBeTruthy());

  fireEvent.press(screen.getByTestId('envelope-edit'));
  fireEvent.changeText(screen.getByTestId('envelope-allocated-input'), '999999.00');
  fireEvent.press(screen.getByTestId('envelope-save'));

  await waitFor(() =>
    expect(screen.getByText('Envelopes must sum to your total income.')).toBeTruthy()
  );
  expect(mockUpdate).toHaveBeenCalledWith(PERIOD_ID, ENVELOPE_ID, { allocated: '999999.00' });
});

it('removes the envelope after a confirmed delete and goes back', async () => {
  mockFetch.mockResolvedValue(ENVELOPE);
  mockDelete.mockResolvedValue(undefined);
  renderScreen();
  await waitFor(() => expect(screen.getByTestId('envelope-delete')).toBeTruthy());

  fireEvent.press(screen.getByTestId('envelope-delete'));
  fireEvent.press(screen.getByTestId('envelope-delete-confirm'));

  await waitFor(() => expect(mockDelete).toHaveBeenCalledWith(PERIOD_ID, ENVELOPE_ID));
  await waitFor(() => expect(router.back).toHaveBeenCalled());
});

it('treats a non-uuid envelopeId as not-found rather than querying the server', () => {
  mockParams.mockReturnValue({ id: PERIOD_ID, envelopeId: '../accounts/1' });
  renderScreen();
  expect(screen.getByText('That envelope could not be found.')).toBeTruthy();
  expect(mockFetch).not.toHaveBeenCalled();
});
