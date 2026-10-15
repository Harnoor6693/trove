import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import AccountsScreen from './accounts';
import { createAccount, exchangeSetupToken, fetchAccounts } from '@api/accounts';
import { useAuthStore } from '@stores/authStore';
import type { AccountResponse, MeResponse } from '@api/types';

jest.mock('@api/accounts', () => ({
  fetchAccounts: jest.fn(),
  createAccount: jest.fn(),
  exchangeSetupToken: jest.fn(),
}));

const mockAccounts = fetchAccounts as jest.MockedFunction<typeof fetchAccounts>;
const mockCreate = createAccount as jest.MockedFunction<typeof createAccount>;
const mockExchange = exchangeSetupToken as jest.MockedFunction<typeof exchangeSetupToken>;

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

let clients: ReturnType<typeof createTestQueryClient>[] = [];

async function renderScreen() {
  const qc = createTestQueryClient();
  clients.push(qc);
  const utils = render(
    <QueryClientProvider client={qc}>
      <AccountsScreen />
    </QueryClientProvider>
  );
  await waitFor(() => expect(screen.queryByTestId('accounts-loading')).toBeNull());
  return utils;
}

beforeEach(() => {
  jest.clearAllMocks();
  mockAccounts.mockReset();
  mockCreate.mockReset();
  mockExchange.mockReset();
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

describe('the account list', () => {
  it('shows each account in ITS OWN currency, with its type and sync state', async () => {
    mockAccounts.mockResolvedValue([
      account('a1', { currency: 'CAD' }),
      account('a2', { currency: 'USD', displayName: 'US Savings', accountType: 'SAVINGS' }),
    ]);
    await renderScreen();
    expect(screen.getByTestId('account-row-a1')).toBeTruthy();
    expect(screen.getByTestId('account-row-a2')).toBeTruthy();
    expect(screen.getByText('Example Credit Union · Savings · USD · Bank connection')).toBeTruthy();
  });

  it('states a failed sync in words with its reason', async () => {
    mockAccounts.mockResolvedValue([
      account('a1', { syncStatus: 'ERROR', errorMessage: 'The bridge did not answer.' }),
    ]);
    await renderScreen();
    expect(screen.getByText('Sync failed')).toBeTruthy();
    expect(screen.getByTestId('account-error-a1').props.children).toBe(
      'The bridge did not answer.'
    );
  });

  it('shows the empty state before any account exists', async () => {
    mockAccounts.mockResolvedValue([]);
    await renderScreen();
    expect(screen.getByTestId('accounts-empty')).toBeTruthy();
  });
});

describe('adding a manual account', () => {
  it('creates it as MANUAL and never sends a currency literal of its own', async () => {
    mockAccounts.mockResolvedValue([]);
    mockCreate.mockResolvedValue(account('a9', { sourceKind: 'MANUAL' }));
    await renderScreen();

    await act(async () => {
      fireEvent.press(screen.getByTestId('accounts-add-toggle'));
    });
    await act(async () => {
      fireEvent.changeText(screen.getByTestId('accounts-add-name'), 'Cash');
      fireEvent.press(screen.getByTestId('accounts-add-type-OTHER'));
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('accounts-add-submit'));
    });

    expect(mockCreate).toHaveBeenCalledWith({
      sourceKind: 'MANUAL',
      displayName: 'Cash',
      institution: null,
      accountType: 'OTHER',
    });
    // No currency in the body: the server takes the member's own.
    expect(mockCreate.mock.calls[0][0]).not.toHaveProperty('currency');
  });

  it('creates a CSV account, which is the only way the import surface gets a target', async () => {
    mockAccounts.mockResolvedValue([]);
    mockCreate.mockResolvedValue(account('a9', { sourceKind: 'CSV' }));
    await renderScreen();

    await act(async () => {
      fireEvent.press(screen.getByTestId('accounts-add-toggle'));
    });
    await act(async () => {
      fireEvent.changeText(screen.getByTestId('accounts-add-name'), 'Statements');
      fireEvent.press(screen.getByTestId('accounts-add-source-CSV'));
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('accounts-add-submit'));
    });

    expect(mockCreate).toHaveBeenCalledWith({
      sourceKind: 'CSV',
      displayName: 'Statements',
      institution: null,
      accountType: 'CHECKING',
    });
  });

  it('never offers SIMPLEFIN by hand — that row comes from the credential exchange', async () => {
    mockAccounts.mockResolvedValue([]);
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId('accounts-add-toggle'));
    });
    expect(screen.getByTestId('accounts-add-source-MANUAL')).toBeTruthy();
    expect(screen.getByTestId('accounts-add-source-CSV')).toBeTruthy();
    expect(screen.getByTestId('accounts-add-source-OFX')).toBeTruthy();
    expect(screen.queryByTestId('accounts-add-source-SIMPLEFIN')).toBeNull();
  });

  it('shows the 409 in the household’s terms', async () => {
    const { AxiosError } = jest.requireActual('axios') as typeof import('axios');
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const config = { headers: {} } as any;
    mockAccounts.mockResolvedValue([]);
    mockCreate.mockRejectedValue(
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
            detail: 'Already there.',
          },
        },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
      } as any)
    );
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId('accounts-add-toggle'));
    });
    await act(async () => {
      fireEvent.changeText(screen.getByTestId('accounts-add-name'), 'Cash');
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('accounts-add-submit'));
    });
    await waitFor(() => expect(screen.getByTestId('accounts-add-error')).toBeTruthy());
    expect(screen.getByTestId('accounts-add-error').props.children).toContain('already exists');
  });
});

describe('connecting a bank — the setup token is a credential', () => {
  async function openConnectAndSubmit(token = 'c2VjcmV0LXRva2Vu') {
    await act(async () => {
      fireEvent.press(screen.getByTestId('accounts-connect-toggle'));
    });
    await act(async () => {
      fireEvent.changeText(screen.getByTestId('accounts-connect-token'), token);
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('accounts-connect-submit'));
    });
  }

  it('sends the token once, in the body', async () => {
    mockAccounts.mockResolvedValue([]);
    mockExchange.mockResolvedValue({ accountIds: ['a1', 'a2'], syncLogs: [] });
    await renderScreen();
    await openConnectAndSubmit();
    expect(mockExchange).toHaveBeenCalledTimes(1);
    expect(mockExchange).toHaveBeenCalledWith({
      sourceKind: 'SIMPLEFIN',
      setupToken: 'c2VjcmV0LXRva2Vu',
    });
  });

  it('masks the field so the credential is not shoulder-readable', async () => {
    mockAccounts.mockResolvedValue([]);
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId('accounts-connect-toggle'));
    });
    expect(screen.getByTestId('accounts-connect-token').props.secureTextEntry).toBe(true);
  });

  it('clears the token from state even when the exchange FAILED', async () => {
    const { AxiosError } = jest.requireActual('axios') as typeof import('axios');
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const config = { headers: {} } as any;
    mockAccounts.mockResolvedValue([]);
    mockExchange.mockRejectedValue(
      new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, {
        status: 400,
        statusText: '',
        headers: {},
        config,
        data: {
          success: false,
          error: {
            type: 'https://trove.finance/errors/outbound-url-rejected',
            title: 'outbound-url-rejected',
            status: 400,
            detail: 'The claim URL was not https.',
          },
        },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
      } as any)
    );
    await renderScreen();
    await openConnectAndSubmit();

    await waitFor(() => expect(screen.getByTestId('accounts-connect-error')).toBeTruthy());
    // Cleared on every path: a credential left in state after a failure sits in
    // memory for as long as the screen is mounted.
    expect(screen.getByTestId('accounts-connect-token').props.value).toBe('');
    expect(screen.getByTestId('accounts-connect-error').props.children).toContain(
      'The claim URL was not https.'
    );
  });

  it('tells the member how many accounts the exchange connected', async () => {
    mockAccounts.mockResolvedValue([]);
    mockExchange.mockResolvedValue({ accountIds: ['a1', 'a2', 'a3'], syncLogs: [] });
    await renderScreen();
    await openConnectAndSubmit();
    await waitFor(() => expect(screen.getByTestId('accounts-connect-result')).toBeTruthy());
    expect(screen.getByTestId('accounts-connect-result').props.children).toEqual([
      'Connected ',
      3,
      ' accounts.',
    ]);
  });
});

describe('the setup token does not outlive the call', () => {
  const TOKEN = 'c2VjcmV0LXRva2Vu';

  it('leaves no copy of it in the mutation cache once the exchange settles', async () => {
    mockAccounts.mockResolvedValue([]);
    mockExchange.mockResolvedValue({ accountIds: ['a1'], syncLogs: [] });
    const qc = createTestQueryClient();
    clients.push(qc);
    render(
      <QueryClientProvider client={qc}>
        <AccountsScreen />
      </QueryClientProvider>
    );
    await waitFor(() => expect(screen.queryByTestId('accounts-loading')).toBeNull());

    await act(async () => {
      fireEvent.press(screen.getByTestId('accounts-connect-toggle'));
    });
    await act(async () => {
      fireEvent.changeText(screen.getByTestId('accounts-connect-token'), TOKEN);
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('accounts-connect-submit'));
    });
    await waitFor(() => expect(screen.getByTestId('accounts-connect-result')).toBeTruthy());

    // `mutate` hands the token to React Query, which keeps it as
    // `state.variables` on a Mutation the app-level cache holds for `gcTime`
    // (five minutes on the shipped client, which overrides the query branch
    // only). Clearing the TextInput does nothing about that copy; `reset()`
    // does, and this is the assertion that it happened.
    const cached = JSON.stringify(
      qc
        .getMutationCache()
        .getAll()
        .map((m) => m.state)
    );
    expect(cached).not.toContain(TOKEN);
    // The member still sees what the exchange did, which is why the screen
    // copies the outcome out before it resets.
    expect(screen.getByTestId('accounts-connect-result')).toBeTruthy();
  });

  it('still shows the refusal after the reset, because the error is copied out too', async () => {
    const { AxiosError } = jest.requireActual('axios') as typeof import('axios');
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const config = { headers: {} } as any;
    mockAccounts.mockResolvedValue([]);
    mockExchange.mockRejectedValue(
      new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, {
        status: 400,
        statusText: '',
        headers: {},
        config,
        data: {
          success: false,
          error: {
            type: 'https://trove.finance/errors/outbound-url-rejected',
            title: 'outbound-url-rejected',
            status: 400,
            detail: 'The claim URL was not https.',
          },
        },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
      } as any)
    );
    const qc = createTestQueryClient();
    clients.push(qc);
    render(
      <QueryClientProvider client={qc}>
        <AccountsScreen />
      </QueryClientProvider>
    );
    await waitFor(() => expect(screen.queryByTestId('accounts-loading')).toBeNull());
    await act(async () => {
      fireEvent.press(screen.getByTestId('accounts-connect-toggle'));
    });
    await act(async () => {
      fireEvent.changeText(screen.getByTestId('accounts-connect-token'), TOKEN);
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('accounts-connect-submit'));
    });
    await waitFor(() => expect(screen.getByTestId('accounts-connect-error')).toBeTruthy());
    expect(screen.getByTestId('accounts-connect-error').props.children).toContain(
      'The claim URL was not https.'
    );
    const cached = JSON.stringify(
      qc
        .getMutationCache()
        .getAll()
        .map((m) => m.state)
    );
    expect(cached).not.toContain(TOKEN);
  });
});
