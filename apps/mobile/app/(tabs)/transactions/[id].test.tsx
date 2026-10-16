import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { useLocalSearchParams } from 'expo-router';
import { createTestQueryClient } from '@/test/queryClient';
import TransactionDetailScreen from './[id]';
import { fetchCategories } from '@api/categories';
import {
  deleteTransaction,
  fetchTransaction,
  fetchTransactions,
  patchTransaction,
} from '@api/transactions';
import { useAuthStore } from '@stores/authStore';
import { transactionKeys } from '@hooks/useTransactions';
import type { MeResponse, TransactionPatchRequest, TransactionResponse } from '@api/types';

jest.mock('@api/categories', () => ({
  fetchCategories: jest.fn(),
}));

jest.mock('@api/transactions', () => ({
  fetchTransactions: jest.fn(),
  fetchTransaction: jest.fn(),
  patchTransaction: jest.fn(),
  deleteTransaction: jest.fn(),
}));

const mockFetch = fetchTransaction as jest.MockedFunction<typeof fetchTransaction>;
const mockPatch = patchTransaction as jest.MockedFunction<typeof patchTransaction>;
const mockCategories = fetchCategories as jest.MockedFunction<typeof fetchCategories>;
const mockList = fetchTransactions as jest.MockedFunction<typeof fetchTransactions>;
const mockDelete = deleteTransaction as jest.MockedFunction<typeof deleteTransaction>;
const mockParams = useLocalSearchParams as jest.MockedFunction<typeof useLocalSearchParams>;

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

function transaction(overrides: Partial<TransactionResponse> = {}): TransactionResponse {
  return {
    id: '11111111-1111-4111-8111-111111111111',
    householdId: 'h1',
    accountId: 'a1',
    createdBy: 'u1',
    providerTransactionId: null,
    amount: '4.75',
    currency: 'CAD',
    type: 'DEBIT',
    description: 'SQ *BLUE DOOR COFFEE',
    merchantName: 'Blue Door Coffee',
    date: '2026-09-04',
    postedAt: null,
    pending: false,
    manual: true,
    categoryId: 'c1',
    transferGroupId: null,
    importBatchId: null,
    tags: null,
    notes: 'Old note',
    createdAt: '2026-09-04T18:00:00Z',
    updatedAt: '2026-09-04T18:00:00Z',
    ...overrides,
  };
}

/** The body the screen actually sent on the nth patch. */
function patchBody(index = 0): TransactionPatchRequest {
  return mockPatch.mock.calls[index][1];
}

let clients: ReturnType<typeof createTestQueryClient>[] = [];

async function renderScreen(row: TransactionResponse = transaction()) {
  mockFetch.mockResolvedValue(row);
  const qc = createTestQueryClient();
  clients.push(qc);
  const utils = await render(
    <QueryClientProvider client={qc}>
      <TransactionDetailScreen />
    </QueryClientProvider>
  );
  await waitFor(() => expect(screen.queryByTestId('transaction-detail-loading')).toBeNull());
  return utils;
}

beforeEach(async () => {
  jest.clearAllMocks();
  mockFetch.mockReset();
  mockPatch.mockReset();
  mockDelete.mockReset();
  mockCategories.mockReset();
  mockList.mockReset();
  mockCategories.mockResolvedValue([
    { id: 'c1', name: 'Coffee' },
    { id: 'c2', name: 'Groceries' },
  ] as Awaited<ReturnType<typeof fetchCategories>>);
  mockList.mockResolvedValue({ data: [], meta: { total: 0, page: 0, limit: 100 } });
  mockParams.mockReturnValue({ id: '11111111-1111-4111-8111-111111111111' });
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

describe('Transaction detail', () => {
  it('shows the row in its own currency with the sign taken from `type`', async () => {
    await renderScreen();
    expect(screen.getByTestId('transaction-detail-amount').props.children).toMatch(/4\.75/);
    expect(screen.getByTestId('transaction-detail-amount').props.children).toMatch(/-/);
  });
});

describe('the patch body carries ONLY the changed field', () => {
  it('saving a note sends { notes } and nothing else', async () => {
    mockPatch.mockResolvedValue(transaction({ notes: 'Reimbursable' }));
    await renderScreen();

    await act(async () => {
      await fireEvent.changeText(screen.getByTestId('transaction-notes'), 'Reimbursable');
    });
    await act(async () => {
      await fireEvent.press(screen.getByTestId('transaction-save-notes'));
    });

    expect(mockPatch).toHaveBeenCalledTimes(1);
    expect(patchBody()).toEqual({ notes: 'Reimbursable' });
    // The category the member never touched is not in the body, so the server
    // leaves it alone rather than being handed it back.
    expect(patchBody()).not.toHaveProperty('categoryId');
    expect(patchBody()).not.toHaveProperty('merchantName');
    expect(patchBody()).not.toHaveProperty('transferGroupId');
  });

  it('saving a merchant sends { merchantName } and nothing else', async () => {
    mockPatch.mockResolvedValue(transaction({ merchantName: 'Blue Door' }));
    await renderScreen();
    await act(async () => {
      await fireEvent.changeText(screen.getByTestId('transaction-merchant'), 'Blue Door');
    });
    await act(async () => {
      await fireEvent.press(screen.getByTestId('transaction-save-merchant'));
    });
    expect(patchBody()).toEqual({ merchantName: 'Blue Door' });
  });

  it('assigning a category sends { categoryId } and nothing else', async () => {
    mockPatch.mockResolvedValue(transaction({ categoryId: 'c2' }));
    await renderScreen();
    await act(async () => {
      await fireEvent.press(await screen.findByTestId('transaction-category-c2'));
    });
    await act(async () => {
      await fireEvent.press(screen.getByTestId('transaction-save-category'));
    });
    expect(patchBody()).toEqual({ categoryId: 'c2' });
  });

  it('clearing a category sends an explicit null, not an omitted field', async () => {
    mockPatch.mockResolvedValue(transaction({ categoryId: null }));
    await renderScreen();
    await act(async () => {
      await fireEvent.press(screen.getByTestId('transaction-clear-category'));
    });
    expect(patchBody()).toEqual({ categoryId: null });
    expect('categoryId' in patchBody()).toBe(true);
  });
});

describe('a control that has nothing to send does not send anything', () => {
  /**
   * Both of these buttons used to build their body as
   * `<local state> ?? <server value>`, which sends `null` on a row that never
   * had the field — an *unlink* request under a "Link transfer" button — and
   * `''` once the member clears the box, which is a 400 against two fields the
   * contract declares `format: uuid`. Clearing has its own button and its own
   * explicit `null`.
   */
  it('has no free-text id field for category or transfer group', async () => {
    await renderScreen();
    expect(screen.queryByTestId('transaction-transfer-group')).toBeNull();
    expect(screen.queryByTestId('transaction-category')).toBeNull();
    expect(await screen.findByTestId('transaction-category-c1')).toBeTruthy();
  });

  it('refuses assign category until a different one is picked', async () => {
    await renderScreen(transaction({ categoryId: 'c1' }));
    await screen.findByTestId('transaction-category-c1');
    expect(screen.getByTestId('transaction-save-category').props.accessibilityState.disabled).toBe(
      true
    );
    await act(async () => {
      await fireEvent.press(screen.getByTestId('transaction-save-category'));
    });
    expect(mockPatch).not.toHaveBeenCalled();
  });
});

describe('tags — the fifth writable field on the patch', () => {
  it('sends the list it parsed and nothing else', async () => {
    mockPatch.mockResolvedValue(transaction({ tags: ['travel', 'business'] }));
    await renderScreen();
    await act(async () => {
      await fireEvent.changeText(screen.getByTestId('transaction-tags'), ' travel , business ,, ');
    });
    await act(async () => {
      await fireEvent.press(screen.getByTestId('transaction-save-tags'));
    });
    expect(patchBody()).toEqual({ tags: ['travel', 'business'] });
    expect(patchBody()).not.toHaveProperty('notes');
  });

  it('clears them with an explicit null rather than an empty list', async () => {
    mockPatch.mockResolvedValue(transaction({ tags: null }));
    await renderScreen(transaction({ tags: ['travel'] }));
    await act(async () => {
      await fireEvent.press(screen.getByTestId('transaction-clear-tags'));
    });
    expect(patchBody()).toEqual({ tags: null });
    expect('tags' in patchBody()).toBe(true);
  });

  it('shows the tags the row already carries', async () => {
    await renderScreen(transaction({ tags: ['travel', 'business'] }));
    expect(screen.getByTestId('transaction-tags').props.value).toBe('travel, business');
  });
});

describe('transfers — pick the other leg', () => {
  const OTHER = '22222222-2222-4222-8222-222222222222';
  function leg(overrides: Partial<TransactionResponse>): TransactionResponse {
    return transaction({
      id: OTHER,
      accountId: 'a2',
      type: 'CREDIT',
      amount: '4.75',
      date: '2026-09-05',
      merchantName: 'Savings in',
      transferGroupId: null,
      ...overrides,
    });
  }

  it('lists only opposite-sign, same-amount, other-account rows near the date', async () => {
    mockList.mockResolvedValue({
      data: [
        leg({}),
        leg({ id: '33333333-3333-4333-8333-333333333333', type: 'DEBIT' }),
        leg({ id: '44444444-4444-4444-8444-444444444444', amount: '9.00' }),
        leg({ id: '55555555-5555-4555-8555-555555555555', accountId: 'a1' }),
        leg({ id: '66666666-6666-4666-8666-666666666666', date: '2026-08-01' }),
        // Already one leg of a pair: linking to it would be refused (409), so it is not offered.
        leg({ id: '77777777-7777-4777-8777-777777777777', transferGroupId: 'g9' }),
      ],
      meta: { total: 6, page: 0, limit: 500 },
    });
    await renderScreen();
    expect(await screen.findByTestId(`transfer-candidate-${OTHER}`)).toBeTruthy();
    expect(screen.queryAllByTestId(/^transfer-candidate-/)).toHaveLength(1);
    expect(mockList.mock.calls[0][0]).toMatchObject({ from: '2026-08-28', to: '2026-09-11' });
  });

  it('searches up to the contract maximum of 500 rows in the window', async () => {
    await renderScreen();
    await waitFor(() => expect(mockList).toHaveBeenCalled());
    expect(mockList.mock.calls[0][0]).toMatchObject({ limit: 500 });
  });

  async function pickAndLink() {
    await act(async () => {
      await fireEvent.press(await screen.findByTestId(`transfer-candidate-${OTHER}`));
    });
    await act(async () => {
      await fireEvent.press(screen.getByTestId('transaction-link-transfer'));
    });
  }

  it('links both legs with ONE patch naming the partner; the server writes both', async () => {
    mockList.mockResolvedValue({
      data: [leg({})],
      meta: { total: 1, page: 0, limit: 500 },
    });
    mockPatch.mockResolvedValue(transaction({ transferGroupId: 'g' }));
    await renderScreen();
    expect(screen.getByTestId('transaction-link-transfer').props.accessibilityState.disabled).toBe(
      true
    );
    await pickAndLink();
    await waitFor(() => expect(mockPatch).toHaveBeenCalledTimes(1));
    expect(mockPatch.mock.calls[0][0]).toBe('11111111-1111-4111-8111-111111111111');
    expect(patchBody(0)).toEqual({ transferPartnerId: OTHER });
  });

  it('shows a refused link and sends no second request', async () => {
    const { AxiosError } = jest.requireActual('axios') as typeof import('axios');
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const config = { headers: {} } as any;
    mockList.mockResolvedValue({
      data: [leg({})],
      meta: { total: 1, page: 0, limit: 500 },
    });
    mockPatch.mockRejectedValue(
      new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, {
        status: 409,
        statusText: '',
        headers: {},
        config,
        data: {
          success: false,
          error: {
            type: 'https://trove.finance/errors/conflict',
            title: 'conflict',
            status: 409,
            detail: 'One of these transactions is already part of a transfer.',
          },
        },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
      } as any)
    );
    await renderScreen();
    await pickAndLink();
    await waitFor(() => expect(screen.getByTestId('transaction-patch-error')).toBeTruthy());
    expect(screen.getByTestId('transaction-patch-error').props.children).toContain(
      'already part of a transfer'
    );
    expect(mockPatch).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('transaction-unlink-transfer')).toBeNull();
  });

  it("marks the partner's cached detail stale, since the server changed it too", async () => {
    mockList.mockResolvedValue({
      data: [leg({})],
      meta: { total: 1, page: 0, limit: 500 },
    });
    mockPatch.mockResolvedValue(transaction({ transferGroupId: 'g' }));
    await renderScreen();
    // The test client collects an unobserved query at once (`gcTime: 0`), so
    // the request to refresh it is what is asserted, not a cache entry.
    const invalidate = jest.spyOn(clients[clients.length - 1], 'invalidateQueries');
    await pickAndLink();
    await waitFor(() => expect(mockPatch).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith({ queryKey: transactionKeys.detail(OTHER) })
    );
  });

  it('says so when there is no candidate', async () => {
    await renderScreen();
    expect(await screen.findByTestId('transfer-no-candidates')).toBeTruthy();
  });

  it('a linked row offers only unlink', async () => {
    mockPatch.mockResolvedValue(transaction({ transferGroupId: null }));
    await renderScreen(transaction({ transferGroupId: 'g1' }));
    expect(screen.queryByTestId('transaction-link-transfer')).toBeNull();
    await act(async () => {
      await fireEvent.press(screen.getByTestId('transaction-unlink-transfer'));
    });
    expect(patchBody()).toEqual({ transferGroupId: null });
  });
});

describe('delete', () => {
  it('asks first, and says only a hand-entered row can go', async () => {
    await renderScreen();
    await act(async () => {
      await fireEvent.press(screen.getByTestId('transaction-delete'));
    });
    expect(screen.getByTestId('transaction-delete-confirm-copy')).toBeTruthy();
    expect(mockDelete).not.toHaveBeenCalled();
  });

  it('deletes once confirmed', async () => {
    mockDelete.mockResolvedValue(undefined);
    await renderScreen();
    await act(async () => {
      await fireEvent.press(screen.getByTestId('transaction-delete'));
    });
    await act(async () => {
      await fireEvent.press(screen.getByTestId('transaction-delete-confirm'));
    });
    expect(mockDelete).toHaveBeenCalledWith('11111111-1111-4111-8111-111111111111');
  });

  it('explains the 409 a source-fetched row answers instead of a generic failure', async () => {
    const { AxiosError } = jest.requireActual('axios') as typeof import('axios');
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const config = { headers: {} } as any;
    mockDelete.mockRejectedValue(
      new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, {
        status: 409,
        statusText: '',
        headers: {},
        config,
        data: {
          success: false,
          error: {
            type: 'https://trove.finance/errors/conflict',
            title: 'conflict',
            status: 409,
            detail: 'Only a manual transaction can be deleted.',
          },
        },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
      } as any)
    );
    await renderScreen(transaction({ manual: false }));
    await act(async () => {
      await fireEvent.press(screen.getByTestId('transaction-delete'));
    });
    await act(async () => {
      await fireEvent.press(screen.getByTestId('transaction-delete-confirm'));
    });
    await waitFor(() => expect(screen.getByTestId('transaction-delete-error')).toBeTruthy());
    expect(screen.getByTestId('transaction-delete-error').props.children).toContain(
      'Disconnect the account instead.'
    );
  });
});

describe('an imported row', () => {
  it('links to the batch it came from, so undo is reachable from the ledger', async () => {
    await renderScreen(transaction({ importBatchId: 'b1' }));
    expect(screen.getByTestId('transaction-to-import')).toBeTruthy();
  });

  it('offers no such link on a row that came from a sync', async () => {
    await renderScreen(transaction({ importBatchId: null }));
    expect(screen.queryByTestId('transaction-to-import')).toBeNull();
  });
});

describe('a route that does not name a transaction', () => {
  it('says so instead of putting the value into a request path', async () => {
    mockParams.mockReturnValue({ id: '../accounts/22222222-2222-4222-8222-222222222222' });
    const qc = createTestQueryClient();
    clients.push(qc);
    await render(
      <QueryClientProvider client={qc}>
        <TransactionDetailScreen />
      </QueryClientProvider>
    );
    expect(screen.getByTestId('transaction-detail-invalid-id')).toBeTruthy();
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
