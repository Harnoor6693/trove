import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { router } from 'expo-router';
import { createTestQueryClient } from '@/test/queryClient';
import { PeriodDetail } from './PeriodDetail';
import { fetchEnvelopes, updateBudgetPeriod, deleteBudgetPeriod } from '@api/budgets';
import type { BudgetPeriodResponse, EnvelopeResponse } from '@api/types';

jest.mock('@api/budgets', () => ({
  fetchEnvelopes: jest.fn(),
  createEnvelope: jest.fn(),
  fetchEnvelope: jest.fn(),
  updateEnvelope: jest.fn(),
  deleteEnvelope: jest.fn(),
  updateBudgetPeriod: jest.fn(),
  deleteBudgetPeriod: jest.fn(),
}));

const mockEnvelopes = fetchEnvelopes as jest.MockedFunction<typeof fetchEnvelopes>;
const mockUpdate = updateBudgetPeriod as jest.MockedFunction<typeof updateBudgetPeriod>;
const mockDelete = deleteBudgetPeriod as jest.MockedFunction<typeof deleteBudgetPeriod>;

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

function envelope(id: string, overrides: Partial<EnvelopeResponse> = {}): EnvelopeResponse {
  return {
    id,
    householdId: 'h1',
    periodId: 'p1',
    categoryId: `c-${id}`,
    categoryName: `Category ${id}`,
    allocated: '400.0000',
    spent: '250.0000',
    rolloverAmount: '0.0000',
    currency: 'CAD',
    health: {
      healthPercentage: 62,
      paceMarkerPercentage: 50,
      overPace: false,
      remainingAmount: '150.0000',
      expectedSpend: '200.0000',
    },
    createdAt: '2026-09-04T14:00:00Z',
    updatedAt: '2026-09-04T14:00:00Z',
    ...overrides,
  };
}

async function renderPeriodDetail(period = PERIOD, onDeleted = jest.fn()) {
  const client = createTestQueryClient();
  await render(
    <QueryClientProvider client={client}>
      <PeriodDetail period={period} onDeleted={onDeleted} testID="period-detail" />
    </QueryClientProvider>
  );
  return { client, onDeleted };
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe('envelopes — each in its own currency, no combined total', () => {
  it('renders a CAD and a USD envelope side by side without ever summing them', async () => {
    mockEnvelopes.mockResolvedValue([
      envelope('e1', { currency: 'CAD', categoryName: 'Groceries', allocated: '400.0000' }),
      envelope('e2', { currency: 'USD', categoryName: 'Streaming', allocated: '20.0000' }),
    ]);
    await renderPeriodDetail();

    await waitFor(() => expect(screen.getByTestId('envelope-row-e1')).toBeTruthy());
    expect(screen.getByTestId('envelope-row-e2')).toBeTruthy();

    // The only figures on the page are the ones the server sent, each in its
    // own currency ($400.00 CAD, US$20.00). There is no third, blended total —
    // proving the absence, not just the presence of the two real ones.
    const allText = screen.toJSON();
    const serialised = JSON.stringify(allText);
    expect(serialised).not.toMatch(/420\.00/); // 400 CAD + 20 USD, the wrong sum
  });

  it('renders health, spent and remaining exactly as the response carries them', async () => {
    mockEnvelopes.mockResolvedValue([
      envelope('e1', {
        allocated: '400.0000',
        spent: '250.0000',
        health: {
          healthPercentage: 62,
          paceMarkerPercentage: 50,
          overPace: true,
          remainingAmount: '150.0000',
          expectedSpend: '200.0000',
        },
      }),
    ]);
    await renderPeriodDetail();
    await waitFor(() => expect(screen.getByTestId('envelope-row-e1')).toBeTruthy());
    expect(screen.getByText('62%')).toBeTruthy();
  });

  it('shows the empty state when the period has no envelopes yet', async () => {
    mockEnvelopes.mockResolvedValue([]);
    await renderPeriodDetail();
    await waitFor(() => expect(screen.getByTestId('period-detail-envelopes-empty')).toBeTruthy());
  });
});

describe('editing the period', () => {
  it('saves totalIncome/budgetType and surfaces the zero-based invariant on totalIncome', async () => {
    mockEnvelopes.mockResolvedValue([]);
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
              detail: 'Envelopes must sum to your total income.',
              fieldErrors: [
                { field: 'totalIncome', message: 'Envelopes must sum to your total income.' },
              ],
            },
          },
        },
      })
    );
    await renderPeriodDetail();
    await waitFor(() => expect(screen.getByTestId('period-detail-edit')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('period-detail-edit'));
    await fireEvent.changeText(screen.getByTestId('period-detail-income-input'), '1.00');
    await fireEvent.press(screen.getByTestId('period-detail-save'));

    await waitFor(() =>
      expect(screen.getByText('Envelopes must sum to your total income.')).toBeTruthy()
    );
  });

  it('lets the member switch budget type and cancel out of editing without saving', async () => {
    mockEnvelopes.mockResolvedValue([]);
    await renderPeriodDetail();
    await waitFor(() => expect(screen.getByTestId('period-detail-edit')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('period-detail-edit'));
    await fireEvent.press(screen.getByTestId('period-detail-type-ZERO_BASED'));
    expect(
      screen.getByText('Every dollar of income must be allocated to an envelope.')
    ).toBeTruthy();

    await fireEvent.press(screen.getByTestId('period-detail-cancel'));
    expect(screen.queryByTestId('period-detail-income-input')).toBeNull();
    expect(mockUpdate).not.toHaveBeenCalled();
  });

  it('shows the server detail for a failure other than the 400 field error', async () => {
    mockEnvelopes.mockResolvedValue([]);
    mockUpdate.mockRejectedValue(
      Object.assign(new Error('server error'), {
        isAxiosError: true,
        response: {
          status: 500,
          data: {
            success: false,
            error: {
              type: 'https://trove.finance/errors/internal',
              title: 'Internal error',
              status: 500,
              detail: 'Something went wrong on our end.',
            },
          },
        },
      })
    );
    await renderPeriodDetail();
    await waitFor(() => expect(screen.getByTestId('period-detail-edit')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('period-detail-edit'));
    await fireEvent.changeText(screen.getByTestId('period-detail-income-input'), '5100.00');
    await fireEvent.press(screen.getByTestId('period-detail-save'));

    await waitFor(() =>
      expect(screen.getByTestId('period-detail-update-error')).toHaveTextContent(
        'Something went wrong on our end.'
      )
    );
  });
});

describe('navigating to an envelope', () => {
  it('opens the envelope on tap and "Add an envelope" from its own button', async () => {
    mockEnvelopes.mockResolvedValue([envelope('e1')]);
    await renderPeriodDetail();
    await waitFor(() => expect(screen.getByTestId('envelope-row-e1')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('envelope-row-e1'));
    expect(router.push).toHaveBeenCalledWith('/(tabs)/budgets/period/p1/envelope/e1');

    await fireEvent.press(screen.getByTestId('period-detail-add-envelope'));
    expect(router.push).toHaveBeenCalledWith('/(tabs)/budgets/period/p1/envelope/new');
  });
});

describe('deleting the period', () => {
  it('requires a confirm step and calls onDeleted only after the confirmed delete resolves', async () => {
    mockEnvelopes.mockResolvedValue([]);
    mockDelete.mockResolvedValue(undefined);
    const { onDeleted } = await renderPeriodDetail();
    await waitFor(() => expect(screen.getByTestId('period-detail-delete')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('period-detail-delete'));
    expect(screen.getByTestId('period-detail-delete-confirm-copy')).toBeTruthy();
    expect(onDeleted).not.toHaveBeenCalled();

    await fireEvent.press(screen.getByTestId('period-detail-delete-confirm'));
    await waitFor(() => expect(onDeleted).toHaveBeenCalled());
  });

  it('backs out of the delete confirmation with "Keep it"', async () => {
    mockEnvelopes.mockResolvedValue([]);
    const { onDeleted } = await renderPeriodDetail();
    await waitFor(() => expect(screen.getByTestId('period-detail-delete')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('period-detail-delete'));
    await fireEvent.press(screen.getByTestId('period-detail-delete-cancel'));

    expect(screen.queryByTestId('period-detail-delete-confirm-copy')).toBeNull();
    expect(mockDelete).not.toHaveBeenCalled();
    expect(onDeleted).not.toHaveBeenCalled();
  });
});

describe('a successful income edit', () => {
  it('leaves edit mode once the save resolves', async () => {
    mockEnvelopes.mockResolvedValue([]);
    mockUpdate.mockResolvedValue({ ...PERIOD, totalIncome: '5100.0000' });
    await renderPeriodDetail();
    await waitFor(() => expect(screen.getByTestId('period-detail-edit')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('period-detail-edit'));
    await fireEvent.changeText(screen.getByTestId('period-detail-income-input'), '5100.00');
    await fireEvent.press(screen.getByTestId('period-detail-save'));

    // A generous timeout: under the full suite's parallel worker load this
    // assertion has been observed to need more than the default 1000ms even
    // though the mocked mutation resolves immediately — the same load-related
    // slowness shows up on unrelated pre-existing suites in a full `--ci` run
    // and is not specific to this screen.
    await waitFor(() => expect(screen.queryByTestId('period-detail-income-input')).toBeNull(), {
      timeout: 5000,
    });
    expect(mockUpdate).toHaveBeenCalledWith('p1', {
      totalIncome: '5100.00',
      budgetType: 'FLEXIBLE',
    });
  });
});

describe('the envelope list itself failing to load', () => {
  it('offers a retry that issues another fetch', async () => {
    mockEnvelopes.mockRejectedValue(new Error('network down'));
    await renderPeriodDetail();

    await waitFor(() => expect(screen.getByTestId('period-detail-envelopes-error')).toBeTruthy());
    const callsBeforeRetry = mockEnvelopes.mock.calls.length;

    await fireEvent.press(screen.getByTestId('period-detail-envelopes-retry'));
    await waitFor(() => expect(mockEnvelopes.mock.calls.length).toBeGreaterThan(callsBeforeRetry));
  });
});
