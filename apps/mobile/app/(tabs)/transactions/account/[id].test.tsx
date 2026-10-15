import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { createTestQueryClient } from '@/test/queryClient';
import AccountDetailScreen from './[id]';
import {
  deleteAccount,
  fetchAccount,
  fetchAccountSyncLogs,
  syncAccount,
  updateAccount,
} from '@api/accounts';
import { useAuthStore } from '@stores/authStore';
import type { AccountResponse, MeResponse, SyncLogResponse } from '@api/types';

jest.mock('@api/accounts', () => ({
  fetchAccount: jest.fn(),
  fetchAccountSyncLogs: jest.fn(),
  updateAccount: jest.fn(),
  deleteAccount: jest.fn(),
  syncAccount: jest.fn(),
}));

const mockAccount = fetchAccount as jest.MockedFunction<typeof fetchAccount>;
const mockLogs = fetchAccountSyncLogs as jest.MockedFunction<typeof fetchAccountSyncLogs>;
const mockUpdate = updateAccount as jest.MockedFunction<typeof updateAccount>;
const mockDelete = deleteAccount as jest.MockedFunction<typeof deleteAccount>;
const mockSync = syncAccount as jest.MockedFunction<typeof syncAccount>;
const mockParams = useLocalSearchParams as jest.MockedFunction<typeof useLocalSearchParams>;
const mockPush = router.push as jest.Mock;

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

function account(overrides: Partial<AccountResponse> = {}): AccountResponse {
  return {
    id: '22222222-2222-4222-8222-222222222222',
    householdId: 'h1',
    createdBy: 'u1',
    sourceKind: 'SIMPLEFIN',
    displayName: 'Everyday Chequing',
    institution: 'Example Credit Union',
    accountType: 'CHECKING',
    currency: 'CAD',
    syncStatus: 'OK',
    lastSyncedAt: '2026-09-04T14:00:00Z',
    errorMessage: null,
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-04T14:00:00Z',
    ...overrides,
  };
}

function log(overrides: Partial<SyncLogResponse> = {}): SyncLogResponse {
  return {
    id: 'l1',
    householdId: 'h1',
    accountId: '22222222-2222-4222-8222-222222222222',
    sourceKind: 'SIMPLEFIN',
    status: 'SUCCESS',
    transactionsFetched: 12,
    transactionsNew: 12,
    transactionsUpdated: 0,
    transactionsSkipped: 0,
    transactionsFailed: 0,
    errorMessage: null,
    startedAt: '2026-09-04T14:00:00Z',
    completedAt: '2026-09-04T14:00:03Z',
    ...overrides,
  };
}

let clients: ReturnType<typeof createTestQueryClient>[] = [];

async function renderScreen(row: AccountResponse = account(), logs: SyncLogResponse[] = [log()]) {
  mockAccount.mockResolvedValue(row);
  mockLogs.mockResolvedValue(logs);
  const qc = createTestQueryClient();
  clients.push(qc);
  const utils = render(
    <QueryClientProvider client={qc}>
      <AccountDetailScreen />
    </QueryClientProvider>
  );
  await waitFor(() => expect(screen.queryByTestId('account-detail-loading')).toBeNull());
  return utils;
}

beforeEach(() => {
  jest.clearAllMocks();
  [mockAccount, mockLogs, mockUpdate, mockDelete, mockSync].forEach((mock) => mock.mockReset());
  mockParams.mockReturnValue({ id: '22222222-2222-4222-8222-222222222222' });
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

describe('the account header', () => {
  it('shows the type, the account’s own currency and its sync state in words', async () => {
    await renderScreen(account({ currency: 'USD' }));
    expect(screen.getByText('Chequing · USD')).toBeTruthy();
    expect(screen.getByText('Up to date')).toBeTruthy();
  });

  it('renders the last failure’s reason', async () => {
    await renderScreen(
      account({ syncStatus: 'ERROR', errorMessage: 'The bridge did not answer.' })
    );
    expect(screen.getByTestId('account-detail-error-message').props.children).toBe(
      'The bridge did not answer.'
    );
  });
});

describe('sync', () => {
  it('is disabled while the account is already SYNCING, which is the 409 case', async () => {
    await renderScreen(account({ syncStatus: 'SYNCING' }));
    expect(screen.getByTestId('account-sync').props.accessibilityState.disabled).toBe(true);
  });

  it('reads the outcome off the returned run — a FAILED run is a 200, not a rejection', async () => {
    mockSync.mockResolvedValue(
      log({ status: 'FAILED', transactionsNew: 0, errorMessage: 'Bridge unreachable.' })
    );
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId('account-sync'));
    });
    await waitFor(() => expect(screen.getByTestId('account-sync-outcome')).toBeTruthy());
    const outcome = screen.getByTestId('account-sync-outcome').props.children.join('');
    expect(outcome).toContain('Failed');
    expect(outcome).toContain('Bridge unreachable.');
    // Not an error state: the request succeeded in recording what happened.
    expect(screen.queryByTestId('account-sync-error')).toBeNull();
  });

  it('reports a 409 as "cannot sync right now" with the server’s own detail', async () => {
    const { AxiosError } = jest.requireActual('axios') as typeof import('axios');
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const config = { headers: {} } as any;
    mockSync.mockRejectedValue(
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
            detail: 'No source is registered for this account yet.',
          },
        },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
      } as any)
    );
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId('account-sync'));
    });
    await waitFor(() => expect(screen.getByTestId('account-sync-error')).toBeTruthy());
    expect(screen.getByTestId('account-sync-error').props.children).toContain(
      'No source is registered for this account yet.'
    );
  });
});

describe('sync history', () => {
  it('lists the runs, with a failed one’s reason', async () => {
    await renderScreen(account(), [
      log({ id: 'l1' }),
      log({ id: 'l2', status: 'FAILED', errorMessage: 'Timed out.' }),
    ]);
    expect(screen.getByTestId('sync-log-l1')).toBeTruthy();
    expect(screen.getByTestId('sync-log-error-l2').props.children).toBe('Timed out.');
  });

  it('says so when the account has never been synced', async () => {
    await renderScreen(account(), []);
    expect(screen.getByTestId('account-sync-logs-empty')).toBeTruthy();
  });
});

describe('rename', () => {
  it('PUTs the three writable fields and never the source or the currency', async () => {
    mockUpdate.mockResolvedValue(account({ displayName: 'Renamed' }));
    await renderScreen();
    await act(async () => {
      fireEvent.changeText(screen.getByTestId('account-edit-name'), 'Renamed');
      fireEvent.press(screen.getByTestId('account-edit-type-SAVINGS'));
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('account-edit-submit'));
    });
    expect(mockUpdate).toHaveBeenCalledWith('22222222-2222-4222-8222-222222222222', {
      displayName: 'Renamed',
      institution: 'Example Credit Union',
      accountType: 'SAVINGS',
    });
    expect(mockUpdate.mock.calls[0][1]).not.toHaveProperty('sourceKind');
    expect(mockUpdate.mock.calls[0][1]).not.toHaveProperty('currency');
  });

  it('clears the institution with a null when the member blanks the field', async () => {
    mockUpdate.mockResolvedValue(account({ institution: null }));
    await renderScreen();
    await act(async () => {
      fireEvent.changeText(screen.getByTestId('account-edit-institution'), '   ');
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('account-edit-submit'));
    });
    expect(mockUpdate.mock.calls[0][1].institution).toBeNull();
  });
});

describe('disconnect', () => {
  it('asks first, and the confirmation says the transactions go too', async () => {
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId('account-delete'));
    });
    expect(screen.getByTestId('account-delete-confirm-copy').props.children).toContain(
      'every transaction stored against it'
    );
    expect(mockDelete).not.toHaveBeenCalled();
  });

  it('deletes once confirmed', async () => {
    mockDelete.mockResolvedValue(undefined);
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId('account-delete'));
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('account-delete-confirm'));
    });
    expect(mockDelete).toHaveBeenCalledWith('22222222-2222-4222-8222-222222222222');
  });

  it('backs out without deleting', async () => {
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId('account-delete'));
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('account-delete-cancel'));
    });
    expect(screen.queryByTestId('account-delete-confirm')).toBeNull();
    expect(mockDelete).not.toHaveBeenCalled();
  });
});

describe('imports', () => {
  it('offers the way in to the import flow', async () => {
    await renderScreen();
    expect(screen.getByTestId('account-to-import')).toBeTruthy();
  });
});

describe('this account\u2019s own transactions', () => {
  it('opens the ledger filtered to this account, which is the affordance the filter needs', async () => {
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId('account-to-transactions'));
    });
    // `listTransactions` declares an `accountId` filter and this is the only
    // way a member reaches it; the ledger reads the parameter off its route.
    expect(mockPush).toHaveBeenCalledWith({
      pathname: '/(tabs)/transactions',
      params: { accountId: '22222222-2222-4222-8222-222222222222' },
    });
  });
});

describe('a route that does not name an account', () => {
  it('says so instead of putting the value into a request path', async () => {
    mockParams.mockReturnValue({ id: '../transactions/22222222-2222-4222-8222-222222222222' });
    const qc = createTestQueryClient();
    clients.push(qc);
    render(
      <QueryClientProvider client={qc}>
        <AccountDetailScreen />
      </QueryClientProvider>
    );
    expect(screen.getByTestId('account-detail-invalid-id')).toBeTruthy();
    expect(mockAccount).not.toHaveBeenCalled();
    expect(mockLogs).not.toHaveBeenCalled();
  });
});
