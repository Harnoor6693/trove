import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import NewTransactionScreen from './new';
import { fetchAccounts } from '@api/accounts';
import { createManualTransaction } from '@api/transactions';
import { useAuthStore } from '@stores/authStore';
import type { AccountResponse, MeResponse, TransactionResponse } from '@api/types';

jest.mock('@api/accounts', () => ({ fetchAccounts: jest.fn() }));
jest.mock('@api/transactions', () => ({ createManualTransaction: jest.fn() }));

const mockAccounts = fetchAccounts as jest.MockedFunction<typeof fetchAccounts>;
const mockCreate = createManualTransaction as jest.MockedFunction<typeof createManualTransaction>;

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

function account(id: string, overrides: Partial<AccountResponse> = {}): AccountResponse {
  return {
    id,
    householdId: 'h1',
    createdBy: 'u1',
    sourceKind: 'MANUAL',
    displayName: `Account ${id}`,
    institution: null,
    accountType: 'OTHER',
    currency: 'CAD',
    syncStatus: 'PENDING',
    lastSyncedAt: null,
    errorMessage: null,
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-01T10:00:00Z',
    ...overrides,
  };
}

const CREATED: TransactionResponse = {
  id: 't9',
  householdId: 'h1',
  accountId: 'manual-1',
  createdBy: 'u1',
  providerTransactionId: null,
  amount: '4.75',
  currency: 'CAD',
  type: 'DEBIT',
  description: null,
  merchantName: 'Blue Door Coffee',
  date: '2026-09-04',
  postedAt: null,
  pending: false,
  manual: true,
  categoryId: null,
  transferGroupId: null,
  importBatchId: null,
  tags: null,
  notes: null,
  createdAt: '2026-09-04T18:00:00Z',
  updatedAt: '2026-09-04T18:00:00Z',
};

let clients: ReturnType<typeof createTestQueryClient>[] = [];

async function renderScreen() {
  const qc = createTestQueryClient();
  clients.push(qc);
  const utils = await render(
    <QueryClientProvider client={qc}>
      <NewTransactionScreen />
    </QueryClientProvider>
  );
  await waitFor(() => expect(screen.queryByTestId('new-transaction-accounts-loading')).toBeNull());
  return utils;
}

beforeEach(async () => {
  jest.clearAllMocks();
  mockAccounts.mockReset();
  mockCreate.mockReset();
  await act(() => {
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

describe('the account picker', () => {
  it('offers ONLY accounts whose sourceKind is MANUAL', async () => {
    mockAccounts.mockResolvedValue([
      account('manual-1', { sourceKind: 'MANUAL', displayName: 'Cash' }),
      account('bank-1', { sourceKind: 'SIMPLEFIN', displayName: 'Chequing' }),
      account('csv-1', { sourceKind: 'CSV', displayName: 'Imported' }),
      account('ofx-1', { sourceKind: 'OFX', displayName: 'Statement' }),
    ]);
    await renderScreen();

    expect(screen.getByTestId('new-transaction-account-manual-1')).toBeTruthy();
    // The operation answers 409 for any of these, so the member never sees them.
    expect(screen.queryByTestId('new-transaction-account-bank-1')).toBeNull();
    expect(screen.queryByTestId('new-transaction-account-csv-1')).toBeNull();
    expect(screen.queryByTestId('new-transaction-account-ofx-1')).toBeNull();
  });

  it('sends the member to add a manual account when the household has none', async () => {
    mockAccounts.mockResolvedValue([account('bank-1', { sourceKind: 'SIMPLEFIN' })]);
    await renderScreen();
    expect(screen.getByTestId('new-transaction-accounts-empty')).toBeTruthy();
  });

  it('shows a failure instead of an empty picker', async () => {
    mockAccounts.mockRejectedValue(new Error('boom'));
    await renderScreen();
    expect(screen.getByTestId('new-transaction-accounts-error-message')).toBeTruthy();
  });
});

describe('recording a transaction', () => {
  async function fillForm() {
    mockAccounts.mockResolvedValue([account('manual-1', { displayName: 'Cash' })]);
    await renderScreen();
    await act(async () => {
      await fireEvent.press(screen.getByTestId('new-transaction-account-manual-1'));
      await fireEvent.changeText(screen.getByTestId('new-transaction-amount'), '4.75');
      await fireEvent.changeText(screen.getByTestId('new-transaction-date'), '2026-09-04');
      await fireEvent.changeText(
        screen.getByTestId('new-transaction-merchant'),
        'Blue Door Coffee'
      );
    });
  }

  it('sends the absolute amount as a decimal string with the direction in `type`', async () => {
    mockCreate.mockResolvedValue(CREATED);
    await fillForm();
    await act(async () => {
      await fireEvent.press(screen.getByTestId('new-transaction-submit'));
    });

    expect(mockCreate).toHaveBeenCalledWith({
      accountId: 'manual-1',
      amount: '4.75',
      date: '2026-09-04',
      type: 'DEBIT',
      merchant: 'Blue Door Coffee',
      notes: undefined,
    });
    // `currency` is the account's and the operation does not accept it.
    expect(mockCreate.mock.calls[0][0]).not.toHaveProperty('currency');
  });

  it('carries the chosen type rather than assuming a debit', async () => {
    mockCreate.mockResolvedValue({ ...CREATED, type: 'CREDIT' });
    await fillForm();
    await act(async () => {
      await fireEvent.press(screen.getByTestId('new-transaction-type-CREDIT'));
    });
    await act(async () => {
      await fireEvent.press(screen.getByTestId('new-transaction-submit'));
    });
    expect(mockCreate.mock.calls[0][0].type).toBe('CREDIT');
  });

  it('stays disabled until an account, an amount and a date are present', async () => {
    mockAccounts.mockResolvedValue([account('manual-1')]);
    await renderScreen();
    expect(screen.getByTestId('new-transaction-submit').props.accessibilityState.disabled).toBe(
      true
    );
  });

  it('shows the 400 fieldErrors against the fields they name', async () => {
    const { AxiosError } = jest.requireActual('axios') as typeof import('axios');
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const config = { headers: {} } as any;
    mockCreate.mockRejectedValue(
      new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, {
        status: 400,
        statusText: '',
        headers: {},
        config,
        data: {
          success: false,
          error: {
            type: 'https://trove.finance/errors/validation-error',
            title: 'validation-error',
            status: 400,
            detail: 'Check the form.',
            fieldErrors: [{ field: 'amount', message: 'must be greater than zero' }],
          },
        },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
      } as any)
    );
    await fillForm();
    await act(async () => {
      await fireEvent.press(screen.getByTestId('new-transaction-submit'));
    });
    await waitFor(() => expect(screen.getByTestId('new-transaction-amount-error')).toBeTruthy());
    expect(screen.getByTestId('new-transaction-amount-error').props.children).toBe(
      'must be greater than zero'
    );
  });
});
