import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import {
  nextTransactionPage,
  transactionKeys,
  useCreateManualTransaction,
  useDeleteTransaction,
  usePatchTransaction,
  useTransaction,
  useTransactions,
} from './useTransactions';
import {
  createManualTransaction,
  deleteTransaction,
  fetchTransaction,
  fetchTransactions,
  patchTransaction,
} from '@api/transactions';
import type { Page, TransactionResponse } from '@api/types';

// The endpoint module is stubbed; the query client and every store stay real.
jest.mock('@api/transactions', () => ({
  fetchTransactions: jest.fn(),
  fetchTransaction: jest.fn(),
  createManualTransaction: jest.fn(),
  patchTransaction: jest.fn(),
  deleteTransaction: jest.fn(),
}));

const mockList = fetchTransactions as jest.MockedFunction<typeof fetchTransactions>;
const mockOne = fetchTransaction as jest.MockedFunction<typeof fetchTransaction>;
const mockCreate = createManualTransaction as jest.MockedFunction<typeof createManualTransaction>;
const mockPatch = patchTransaction as jest.MockedFunction<typeof patchTransaction>;
const mockDelete = deleteTransaction as jest.MockedFunction<typeof deleteTransaction>;

function transaction(
  id: string,
  overrides: Partial<TransactionResponse> = {}
): TransactionResponse {
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
    manual: true,
    categoryId: null,
    transferGroupId: null,
    importBatchId: null,
    tags: null,
    notes: null,
    createdAt: '2026-09-04T18:00:00Z',
    updatedAt: '2026-09-04T18:00:00Z',
    ...overrides,
  };
}

function page(rows: TransactionResponse[], meta: Page<TransactionResponse>['meta']) {
  return { data: rows, meta };
}

let clients: ReturnType<typeof createTestQueryClient>[] = [];

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = createTestQueryClient();
  clients.push(qc);
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  jest.clearAllMocks();
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('nextTransactionPage', () => {
  it('asks for page one while the first page does not cover the total', () => {
    expect(nextTransactionPage(page([], { total: 137, page: 0, limit: 50 }))).toBe(1);
  });

  it('stops at the end of the ledger rather than fetching empty pages forever', () => {
    expect(nextTransactionPage(page([], { total: 137, page: 2, limit: 50 }))).toBeUndefined();
  });

  it('stops when the page exactly covers the total', () => {
    expect(nextTransactionPage(page([], { total: 100, page: 1, limit: 50 }))).toBeUndefined();
  });

  it('refuses to divide by a zero limit', () => {
    expect(nextTransactionPage(page([], { total: 10, page: 0, limit: 0 }))).toBeUndefined();
  });
});

describe('useTransactions', () => {
  it('consumes data AND meta from the first page', async () => {
    mockList.mockResolvedValue(page([transaction('t1')], { total: 1, page: 0, limit: 50 }));
    const { result } = await renderHook(() => useTransactions(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.pages[0].data).toHaveLength(1);
    expect(result.current.data?.pages[0].meta.total).toBe(1);
    expect(mockList).toHaveBeenCalledWith({ page: 0 });
  });

  it('reaches page two with the right page parameter, and merges both pages', async () => {
    mockList
      .mockResolvedValueOnce(page([transaction('t1')], { total: 2, page: 0, limit: 1 }))
      .mockResolvedValueOnce(page([transaction('t2')], { total: 2, page: 1, limit: 1 }));

    const { result } = await renderHook(() => useTransactions({ sort: 'date,desc' }), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.hasNextPage).toBe(true);

    await act(async () => {
      await result.current.fetchNextPage();
    });

    await waitFor(() => expect(result.current.data?.pages).toHaveLength(2));
    expect(mockList).toHaveBeenNthCalledWith(1, { sort: 'date,desc', page: 0 });
    expect(mockList).toHaveBeenNthCalledWith(2, { sort: 'date,desc', page: 1 });
    expect(result.current.data?.pages.flatMap((p) => p.data).map((row) => row.id)).toEqual([
      't1',
      't2',
    ]);
    expect(result.current.hasNextPage).toBe(false);
  });

  it('passes the declared filters straight through and keys the query by them', async () => {
    mockList.mockResolvedValue(page([], { total: 0, page: 0, limit: 50 }));
    const { result } = await renderHook(() => useTransactions({ q: 'coffee', type: 'DEBIT' }), {
      wrapper,
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockList).toHaveBeenCalledWith({ q: 'coffee', type: 'DEBIT', page: 0 });
    expect(transactionKeys.list({ q: 'coffee', type: 'DEBIT' })).toEqual([
      'transactions',
      'list',
      { q: 'coffee', type: 'DEBIT' },
    ]);
  });

  it('surfaces a failure rather than an empty page', async () => {
    mockList.mockRejectedValue(new Error('boom'));
    const { result } = await renderHook(() => useTransactions(), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
  });
});

describe('useTransaction', () => {
  it('fetches one row by id', async () => {
    mockOne.mockResolvedValue(transaction('t1'));
    const { result } = await renderHook(() => useTransaction('t1'), { wrapper });
    await waitFor(() => expect(result.current.data?.id).toBe('t1'));
    expect(mockOne).toHaveBeenCalledWith('t1');
  });

  it('stays idle without an id', async () => {
    const { result } = await renderHook(() => useTransaction(''), { wrapper });
    expect(result.current.fetchStatus).toBe('idle');
    expect(mockOne).not.toHaveBeenCalled();
  });
});

describe('usePatchTransaction', () => {
  it('sends the patch verbatim — only the changed field reaches the wire', async () => {
    mockPatch.mockResolvedValue(transaction('t1', { notes: 'Reimbursable' }));
    const { result } = await renderHook(() => usePatchTransaction('t1'), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ notes: 'Reimbursable' });
    });
    expect(mockPatch).toHaveBeenCalledWith('t1', { notes: 'Reimbursable' });
  });

  it('preserves an explicit null so a transfer can actually be unlinked', async () => {
    mockPatch.mockResolvedValue(transaction('t1', { transferGroupId: null }));
    const { result } = await renderHook(() => usePatchTransaction('t1'), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ transferGroupId: null });
    });
    const [, body] = mockPatch.mock.calls[0];
    expect(body).toEqual({ transferGroupId: null });
    expect('transferGroupId' in body).toBe(true);
  });

  it('writes the response into the detail cache instead of refetching it', async () => {
    const updated = transaction('t1', { notes: 'Saved' });
    mockOne.mockResolvedValue(transaction('t1', { notes: 'Old note' }));
    mockPatch.mockResolvedValue(updated);

    // The detail query is mounted alongside the mutation on purpose: a
    // `setQueryData` on a key nothing is observing is collected immediately
    // under `createTestQueryClient`'s `gcTime: 0`, so reading the cache
    // directly would be asserting on the garbage collector rather than on the
    // hook. An observer is also what the real screen has.
    const { result } = await renderHook(
      () => ({ detail: useTransaction('t1'), patch: usePatchTransaction('t1') }),
      { wrapper }
    );
    await waitFor(() => expect(result.current.detail.data?.notes).toBe('Old note'));

    await act(async () => {
      await result.current.patch.mutateAsync({ notes: 'Saved' });
    });

    await waitFor(() => expect(result.current.detail.data).toEqual(updated));
    // One fetch, from the mount: the response *is* the new state of the row,
    // so seeing your own edit costs no round trip.
    expect(mockOne).toHaveBeenCalledTimes(1);
  });
});

describe('useCreateManualTransaction', () => {
  it('posts the body and invalidates the ledger', async () => {
    mockCreate.mockResolvedValue(transaction('t9'));
    const qc = createTestQueryClient();
    clients.push(qc);
    const invalidate = jest.spyOn(qc, 'invalidateQueries');
    const { result } = await renderHook(() => useCreateManualTransaction(), {
      wrapper: ({ children }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>,
    });
    await act(async () => {
      await result.current.mutateAsync({ accountId: 'a1', amount: '4.75', date: '2026-09-04' });
    });
    expect(mockCreate).toHaveBeenCalledWith({
      accountId: 'a1',
      amount: '4.75',
      date: '2026-09-04',
    });
    const keys = invalidate.mock.calls.map(([arg]) => arg?.queryKey);
    expect(keys).toContainEqual(['transactions']);
    expect(keys).toContainEqual(['accounts']);
    expect(keys).toContainEqual(['dashboard']);
  });
});

describe('useDeleteTransaction', () => {
  it('deletes and invalidates the ledger, the accounts and the dashboard', async () => {
    mockDelete.mockResolvedValue(undefined);
    const qc = createTestQueryClient();
    clients.push(qc);
    const invalidate = jest.spyOn(qc, 'invalidateQueries');
    const { result } = await renderHook(() => useDeleteTransaction(), {
      wrapper: ({ children }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>,
    });
    await act(async () => {
      await result.current.mutateAsync('t1');
    });
    expect(mockDelete).toHaveBeenCalledWith('t1');
    const keys = invalidate.mock.calls.map(([arg]) => arg?.queryKey);
    expect(keys).toContainEqual(['transactions']);
    expect(keys).toContainEqual(['dashboard']);
  });
});
