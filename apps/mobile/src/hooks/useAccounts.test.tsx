import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import {
  accountKeys,
  useAccount,
  useAccountSyncLogs,
  useAccounts,
  useCreateAccount,
  useDeleteAccount,
  useExchangeSetupToken,
  useSyncAccount,
  useUpdateAccount,
} from './useAccounts';
import {
  createAccount,
  deleteAccount,
  exchangeSetupToken,
  fetchAccount,
  fetchAccountSyncLogs,
  fetchAccounts,
  syncAccount,
  updateAccount,
} from '@api/accounts';
import type { AccountResponse, SyncLogResponse } from '@api/types';

jest.mock('@api/accounts', () => ({
  fetchAccounts: jest.fn(),
  fetchAccount: jest.fn(),
  fetchAccountSyncLogs: jest.fn(),
  createAccount: jest.fn(),
  updateAccount: jest.fn(),
  deleteAccount: jest.fn(),
  syncAccount: jest.fn(),
  exchangeSetupToken: jest.fn(),
}));

const mockList = fetchAccounts as jest.MockedFunction<typeof fetchAccounts>;
const mockOne = fetchAccount as jest.MockedFunction<typeof fetchAccount>;
const mockLogs = fetchAccountSyncLogs as jest.MockedFunction<typeof fetchAccountSyncLogs>;
const mockCreate = createAccount as jest.MockedFunction<typeof createAccount>;
const mockUpdate = updateAccount as jest.MockedFunction<typeof updateAccount>;
const mockDelete = deleteAccount as jest.MockedFunction<typeof deleteAccount>;
const mockSync = syncAccount as jest.MockedFunction<typeof syncAccount>;
const mockExchange = exchangeSetupToken as jest.MockedFunction<typeof exchangeSetupToken>;

const ACCOUNT: AccountResponse = {
  id: 'a1',
  householdId: 'h1',
  createdBy: 'u1',
  sourceKind: 'MANUAL',
  displayName: 'Cash',
  institution: null,
  accountType: 'OTHER',
  currency: 'CAD',
  syncStatus: 'PENDING',
  lastSyncedAt: null,
  errorMessage: null,
  createdAt: '2026-09-01T10:00:00Z',
  updatedAt: '2026-09-01T10:00:00Z',
};

const LOG: SyncLogResponse = {
  id: 'l1',
  householdId: 'h1',
  accountId: 'a1',
  sourceKind: 'SIMPLEFIN',
  status: 'PARTIAL_FAILURE',
  transactionsFetched: 10,
  transactionsNew: 8,
  transactionsUpdated: 0,
  transactionsSkipped: 0,
  transactionsFailed: 2,
  errorMessage: 'Two rows had no amount.',
  startedAt: '2026-09-05T10:00:00Z',
  completedAt: '2026-09-05T10:00:05Z',
};

let clients: ReturnType<typeof createTestQueryClient>[] = [];

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = createTestQueryClient();
  clients.push(qc);
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

function withClient() {
  const qc = createTestQueryClient();
  clients.push(qc);
  const invalidate = jest.spyOn(qc, 'invalidateQueries');
  const provider = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  return { qc, invalidate, provider };
}

beforeEach(() => {
  jest.clearAllMocks();
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('accountKeys', () => {
  it('hangs sub-resources off the member so invalidating the root reaches them all', () => {
    expect(accountKeys.all()).toEqual(['accounts']);
    expect(accountKeys.list()).toEqual(['accounts', 'list']);
    expect(accountKeys.detail('a1')).toEqual(['accounts', 'a1']);
    expect(accountKeys.syncLogs('a1')).toEqual(['accounts', 'a1', 'sync-logs']);
  });
});

describe('useAccounts', () => {
  it('returns the bare array the operation sends', async () => {
    mockList.mockResolvedValue([ACCOUNT]);
    const { result } = renderHook(() => useAccounts(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([ACCOUNT]));
  });

  it('surfaces a failure rather than an empty list', async () => {
    mockList.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useAccounts(), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
  });
});

describe('useAccount and useAccountSyncLogs', () => {
  it('fetch by id and stay idle without one', async () => {
    mockOne.mockResolvedValue(ACCOUNT);
    mockLogs.mockResolvedValue([LOG]);

    const { result } = renderHook(() => useAccount('a1'), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual(ACCOUNT));

    const logs = renderHook(() => useAccountSyncLogs('a1'), { wrapper });
    await waitFor(() => expect(logs.result.current.data).toEqual([LOG]));

    const idleAccount = renderHook(() => useAccount(''), { wrapper });
    const idleLogs = renderHook(() => useAccountSyncLogs(''), { wrapper });
    expect(idleAccount.result.current.fetchStatus).toBe('idle');
    expect(idleLogs.result.current.fetchStatus).toBe('idle');
  });

  it('keeps a failed run resolvable, reason and all', async () => {
    mockLogs.mockResolvedValue([LOG]);
    const { result } = renderHook(() => useAccountSyncLogs('a1'), { wrapper });
    await waitFor(() => expect(result.current.data).toBeDefined());
    expect(result.current.data?.[0].status).toBe('PARTIAL_FAILURE');
    expect(result.current.data?.[0].errorMessage).toBe('Two rows had no amount.');
  });
});

describe('useCreateAccount', () => {
  it('creates and invalidates the accounts root', async () => {
    mockCreate.mockResolvedValue(ACCOUNT);
    const { invalidate, provider } = withClient();
    const { result } = renderHook(() => useCreateAccount(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({
        sourceKind: 'MANUAL',
        displayName: 'Cash',
        accountType: 'OTHER',
      });
    });
    expect(mockCreate).toHaveBeenCalledWith({
      sourceKind: 'MANUAL',
      displayName: 'Cash',
      accountType: 'OTHER',
    });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual(['accounts']);
  });
});

describe('useUpdateAccount', () => {
  it('PUTs against the id it was built for', async () => {
    mockUpdate.mockResolvedValue(ACCOUNT);
    const { provider } = withClient();
    const { result } = renderHook(() => useUpdateAccount('a1'), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({ displayName: 'Renamed', accountType: 'SAVINGS' });
    });
    expect(mockUpdate).toHaveBeenCalledWith('a1', {
      displayName: 'Renamed',
      accountType: 'SAVINGS',
    });
  });
});

describe('useDeleteAccount', () => {
  it('invalidates the ledger too, because the delete cascades to it', async () => {
    mockDelete.mockResolvedValue(undefined);
    const { invalidate, provider } = withClient();
    const { result } = renderHook(() => useDeleteAccount(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('a1');
    });
    const keys = invalidate.mock.calls.map(([arg]) => arg?.queryKey);
    expect(keys).toContainEqual(['accounts']);
    expect(keys).toContainEqual(['transactions']);
    expect(keys).toContainEqual(['dashboard']);
  });
});

describe('useSyncAccount', () => {
  it('resolves with the run even when the run itself failed', async () => {
    mockSync.mockResolvedValue({ ...LOG, status: 'FAILED', errorMessage: 'Bridge unreachable.' });
    const { provider } = withClient();
    const { result } = renderHook(() => useSyncAccount(), { wrapper: provider });
    let log: SyncLogResponse | undefined;
    await act(async () => {
      log = await result.current.mutateAsync('a1');
    });
    expect(result.current.isError).toBe(false);
    expect(log?.status).toBe('FAILED');
    expect(log?.errorMessage).toBe('Bridge unreachable.');
  });

  it('invalidates this account, its history, the ledger and the dashboard', async () => {
    mockSync.mockResolvedValue(LOG);
    const { invalidate, provider } = withClient();
    const { result } = renderHook(() => useSyncAccount(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('a1');
    });
    const keys = invalidate.mock.calls.map(([arg]) => arg?.queryKey);
    expect(keys).toContainEqual(['accounts']);
    expect(keys).toContainEqual(['accounts', 'a1', 'sync-logs']);
    expect(keys).toContainEqual(['transactions']);
    expect(keys).toContainEqual(['dashboard']);
  });

  it('reports a 409 as a mutation error rather than a run', async () => {
    mockSync.mockRejectedValue(new Error('conflict'));
    const { provider } = withClient();
    const { result } = renderHook(() => useSyncAccount(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('a1').catch(() => undefined);
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe('useExchangeSetupToken', () => {
  it('passes the credential in the body and invalidates what the exchange created', async () => {
    mockExchange.mockResolvedValue({ accountIds: ['a1', 'a2'], syncLogs: [LOG] });
    const { invalidate, provider } = withClient();
    const { result } = renderHook(() => useExchangeSetupToken(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({ sourceKind: 'SIMPLEFIN', setupToken: 'token' });
    });
    expect(mockExchange).toHaveBeenCalledWith({ sourceKind: 'SIMPLEFIN', setupToken: 'token' });
    const keys = invalidate.mock.calls.map(([arg]) => arg?.queryKey);
    expect(keys).toContainEqual(['accounts']);
    expect(keys).toContainEqual(['transactions']);
  });
});
