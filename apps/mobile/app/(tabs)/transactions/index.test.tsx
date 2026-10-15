import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import TransactionsScreen from './index';
import { fetchTransactions } from '@api/transactions';
import { fetchAccount } from '@api/accounts';
import { useLocalSearchParams, router } from 'expo-router';
import { useAuthStore } from '@stores/authStore';
import type { MeResponse, Page, TransactionResponse } from '@api/types';

jest.mock('@api/transactions', () => ({ fetchTransactions: jest.fn() }));
jest.mock('@api/accounts', () => ({ fetchAccount: jest.fn() }));

const mockList = fetchTransactions as jest.MockedFunction<typeof fetchTransactions>;
const mockAccount = fetchAccount as jest.MockedFunction<typeof fetchAccount>;
const mockParams = useLocalSearchParams as jest.MockedFunction<typeof useLocalSearchParams>;
const mockReplace = router.replace as jest.Mock;

const ACCOUNT_ID = '22222222-2222-4222-8222-222222222222';

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

function transaction(id: string): TransactionResponse {
  return {
    id,
    householdId: 'h1',
    accountId: 'a1',
    createdBy: 'u1',
    providerTransactionId: null,
    amount: '4.75',
    currency: 'CAD',
    type: 'DEBIT',
    description: null,
    merchantName: `Merchant ${id}`,
    date: '2026-09-04',
    postedAt: null,
    pending: false,
    manual: false,
    categoryId: null,
    transferGroupId: null,
    importBatchId: null,
    tags: null,
    notes: null,
    createdAt: '2026-09-04T18:00:00Z',
    updatedAt: '2026-09-04T18:00:00Z',
  };
}

function page(ids: string[], meta: Page<TransactionResponse>['meta']) {
  return { data: ids.map(transaction), meta };
}

let clients: ReturnType<typeof createTestQueryClient>[] = [];

/**
 * Settle the screen's queries inside `act()`.
 *
 * Three passes rather than one: an infinite query settles through more than a
 * single macrotask (fetch → page assembly → React Query's own batched notify),
 * and `mockList.mockReset()` below keeps one test's `mockResolvedValueOnce`
 * queue from leaking into the next — `jest.clearAllMocks()` clears calls but
 * not queued implementations, which is how a later test ends up served a page
 * an earlier one queued.
 */
async function flush(passes = 3): Promise<void> {
  for (let pass = 0; pass < passes; pass += 1) {
    await act(async () => {
      await new Promise((resolve) => setImmediate(resolve));
    });
  }
}

async function renderScreen() {
  const qc = createTestQueryClient();
  clients.push(qc);
  const utils = render(
    <QueryClientProvider client={qc}>
      <TransactionsScreen />
    </QueryClientProvider>
  );
  // `waitFor` polls with real timers and wraps each attempt in `act`, so the
  // screen is settled — not merely one macrotask old — before any assertion.
  await waitFor(() => expect(screen.queryByTestId('transactions-loading')).toBeNull());
  return utils;
}

beforeEach(() => {
  jest.clearAllMocks();
  mockList.mockReset();
  mockAccount.mockReset();
  mockParams.mockReturnValue({});
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

describe('Transactions list', () => {
  it('renders the first page and the total from meta', async () => {
    mockList.mockResolvedValue(page(['t1', 't2'], { total: 137, page: 0, limit: 50 }));
    await renderScreen();
    expect(screen.getByTestId('transaction-row-t1')).toBeTruthy();
    expect(screen.getByTestId('transaction-row-t2')).toBeTruthy();
    expect(screen.getByTestId('transactions-total').props.children).toEqual([
      '137',
      ' matching transactions',
    ]);
  });

  it('reaches PAGE TWO on end-reached, with the page parameter the operation declares', async () => {
    // Keyed on the page parameter rather than on call order, so the test
    // proves the screen asked for page one — not merely that it called twice.
    mockList.mockImplementation(async (query = {}) =>
      query.page === 0
        ? page(['t1'], { total: 2, page: 0, limit: 1 })
        : page(['t2'], { total: 2, page: 1, limit: 1 })
    );

    await renderScreen();
    expect(screen.getByTestId('transaction-row-t1')).toBeTruthy();
    // Page one only, so far: a list that stopped here would silently truncate
    // the member's ledger.
    expect(screen.queryByTestId('transaction-row-t2')).toBeNull();

    await act(async () => {
      fireEvent(screen.getByTestId('transactions-list'), 'endReached');
    });
    await flush();

    expect(mockList).toHaveBeenNthCalledWith(1, { sort: 'date,desc', page: 0 });
    expect(mockList).toHaveBeenNthCalledWith(2, { sort: 'date,desc', page: 1 });
    await waitFor(() => expect(screen.getByTestId('transaction-row-t2')).toBeTruthy());
  });

  it('does not ask for a page past the end of the ledger', async () => {
    mockList.mockResolvedValue(page(['t1'], { total: 1, page: 0, limit: 50 }));
    await renderScreen();
    await act(async () => {
      fireEvent(screen.getByTestId('transactions-list'), 'endReached');
    });
    await flush();
    expect(mockList).toHaveBeenCalledTimes(1);
  });

  it('sends the search text as `q`, server-side, rather than filtering one page', async () => {
    mockList.mockResolvedValue(page([], { total: 0, page: 0, limit: 50 }));
    await renderScreen();
    await act(async () => {
      fireEvent.changeText(screen.getByTestId('transactions-search'), 'coffee');
    });
    await flush();
    expect(mockList).toHaveBeenLastCalledWith({ q: 'coffee', sort: 'date,desc', page: 0 });
  });

  it('sends the type filter as the declared `type` parameter', async () => {
    mockList.mockResolvedValue(page([], { total: 0, page: 0, limit: 50 }));
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId('transactions-type-CREDIT'));
    });
    await flush();
    expect(mockList).toHaveBeenLastCalledWith({ type: 'CREDIT', sort: 'date,desc', page: 0 });
  });

  it('shows the empty state when nothing matches', async () => {
    mockList.mockResolvedValue(page([], { total: 0, page: 0, limit: 50 }));
    await renderScreen();
    expect(screen.getByTestId('transactions-empty')).toBeTruthy();
  });

  it('shows the failure rather than an empty ledger', async () => {
    mockList.mockRejectedValue(new Error('boom'));
    await renderScreen();
    expect(screen.getByTestId('transactions-error-message')).toBeTruthy();
  });
});

describe('one account\u2019s own ledger', () => {
  /**
   * The per-account view is a route parameter, not a client-side filter over a
   * page: `listTransactions` declares `accountId`, so the server sends the
   * right rows and the pager still bounds the right total. "View this
   * account's transactions" on the account screen is what puts it there.
   */
  it('passes the route\u2019s accountId into the query the server sees', async () => {
    mockParams.mockReturnValue({ accountId: ACCOUNT_ID });
    mockAccount.mockResolvedValue({
      id: ACCOUNT_ID,
      householdId: 'h1',
      createdBy: 'u1',
      sourceKind: 'MANUAL',
      displayName: 'Everyday Chequing',
      institution: null,
      accountType: 'CHECKING',
      currency: 'CAD',
      syncStatus: 'PENDING',
      lastSyncedAt: null,
      errorMessage: null,
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-01T00:00:00Z',
    });
    mockList.mockResolvedValue(page(['t1'], { total: 1, page: 0, limit: 50 }));
    await renderScreen();
    await flush();
    expect(mockList).toHaveBeenCalledWith(
      expect.objectContaining({ accountId: ACCOUNT_ID, sort: 'date,desc', page: 0 })
    );
    expect(screen.getByTestId('transactions-account-filter')).toBeTruthy();
  });

  it('offers a way back to the whole household ledger', async () => {
    mockParams.mockReturnValue({ accountId: ACCOUNT_ID });
    mockAccount.mockRejectedValue(new Error('not needed for this assertion'));
    mockList.mockResolvedValue(page(['t1'], { total: 1, page: 0, limit: 50 }));
    await renderScreen();
    await flush();
    await act(async () => {
      fireEvent.press(screen.getByTestId('transactions-account-filter'));
    });
    expect(mockReplace).toHaveBeenCalledWith('/(tabs)/transactions');
  });

  it('ignores a route value that is not an account id rather than sending it as a filter', async () => {
    mockParams.mockReturnValue({ accountId: '../accounts/' + ACCOUNT_ID });
    mockList.mockResolvedValue(page(['t1'], { total: 1, page: 0, limit: 50 }));
    await renderScreen();
    await flush();
    expect(mockList).toHaveBeenCalledWith(
      expect.not.objectContaining({ accountId: expect.anything() })
    );
    expect(mockAccount).not.toHaveBeenCalled();
    expect(screen.queryByTestId('transactions-account-filter')).toBeNull();
  });
});
