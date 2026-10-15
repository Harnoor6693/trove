import React from 'react';
import { Platform } from 'react-native';
import { File } from 'expo-file-system';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { router } from 'expo-router';
import { createTestQueryClient } from '@/test/queryClient';
import NewImportScreen from './new';
import { createImport } from '@api/imports';
import { fetchAccounts } from '@api/accounts';
import { IMPORT_FILE_REFUSED_MESSAGE, pickImportFile } from '@lib/importFilePicker';
import type { AccountResponse, ImportPreviewResponse } from '@api/types';

jest.mock('@api/imports', () => ({ createImport: jest.fn() }));
jest.mock('@api/accounts', () => ({ fetchAccounts: jest.fn() }));
jest.mock('@lib/importFilePicker', () => ({
  // Only the picker itself is replaced: the refusal message the screen
  // renders is the real one, so this test cannot drift from what the app
  // actually tells the member.
  ...(jest.requireActual('@lib/importFilePicker') as object),
  pickImportFile: jest.fn(),
}));

const mockCreate = createImport as jest.MockedFunction<typeof createImport>;
const mockAccounts = fetchAccounts as jest.MockedFunction<typeof fetchAccounts>;
const mockPick = pickImportFile as jest.MockedFunction<typeof pickImportFile>;
const mockPush = router.push as jest.Mock;

const CSV_ACCOUNT_ID = '44444444-4444-4444-8444-444444444444';
const OFX_ACCOUNT_ID = '55555555-5555-4555-8555-555555555555';
const MANUAL_ACCOUNT_ID = '66666666-6666-4666-8666-666666666666';
const SIMPLEFIN_ACCOUNT_ID = '77777777-7777-4777-8777-777777777777';
const BATCH_ID = '33333333-3333-4333-8333-333333333333';

/** Typed from the generated schema: an account is never a shape invented here. */
function account(over: Partial<AccountResponse> = {}): AccountResponse {
  return {
    id: CSV_ACCOUNT_ID,
    householdId: '00000000-0000-4000-8000-000000000001',
    createdBy: null,
    sourceKind: 'CSV',
    displayName: 'Statements',
    institution: 'Example Credit Union',
    accountType: 'CHECKING',
    currency: 'CAD',
    syncStatus: 'PENDING',
    lastSyncedAt: null,
    errorMessage: null,
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z',
    ...over,
  };
}

function preview(): ImportPreviewResponse {
  return {
    id: BATCH_ID,
    householdId: '00000000-0000-4000-8000-000000000001',
    accountId: CSV_ACCOUNT_ID,
    sourceKind: 'CSV',
    status: 'PREVIEW',
    originalFilename: 'a.csv',
    rowCount: 1,
    newCount: 1,
    duplicateCount: 0,
    fuzzyDuplicateCount: 0,
    invalidCount: 0,
    dateFormatCandidates: [],
    rows: [],
    warnings: [],
    createdAt: '2026-09-06T00:00:00Z',
  };
}

let clients: ReturnType<typeof createTestQueryClient>[] = [];

async function renderScreen(accounts: AccountResponse[] = [account()]) {
  mockAccounts.mockResolvedValue(accounts);
  const qc = createTestQueryClient();
  clients.push(qc);
  const utils = render(
    <QueryClientProvider client={qc}>
      <NewImportScreen />
    </QueryClientProvider>
  );
  await waitFor(() => expect(screen.queryByTestId('new-import-accounts-loading')).toBeNull());
  return utils;
}

beforeEach(() => {
  jest.clearAllMocks();
  mockCreate.mockReset();
  mockAccounts.mockReset();
  mockPick.mockReset();
  mockPick.mockResolvedValue(null);
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('the account the rows go into', () => {
  it('offers only accounts a statement file can be imported into', async () => {
    await renderScreen([
      account(),
      account({ id: OFX_ACCOUNT_ID, sourceKind: 'OFX', displayName: 'OFX statements' }),
      account({ id: MANUAL_ACCOUNT_ID, sourceKind: 'MANUAL', displayName: 'Cash' }),
      account({ id: SIMPLEFIN_ACCOUNT_ID, sourceKind: 'SIMPLEFIN', displayName: 'Bank' }),
    ]);
    expect(screen.getByTestId(`new-import-account-${CSV_ACCOUNT_ID}`)).toBeTruthy();
    expect(screen.getByTestId(`new-import-account-${OFX_ACCOUNT_ID}`)).toBeTruthy();
    // The file and the account must be the same kind, so a MANUAL or a
    // SIMPLEFIN row could only ever answer 400.
    expect(screen.queryByTestId(`new-import-account-${MANUAL_ACCOUNT_ID}`)).toBeNull();
    expect(screen.queryByTestId(`new-import-account-${SIMPLEFIN_ACCOUNT_ID}`)).toBeNull();
  });

  it('will not upload until the member has said where the rows go', async () => {
    await renderScreen();
    expect(screen.getByTestId('new-import-choose').props.accessibilityState.disabled).toBe(true);
    expect(screen.getByTestId('new-import-account-required')).toBeTruthy();
  });

  it('sends the chosen accountId with the upload — a CSV without one is a 400', async () => {
    mockPick.mockResolvedValue({ uri: 'file://a.csv', name: 'a.csv', type: 'text/csv' });
    mockCreate.mockResolvedValue(preview());
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId(`new-import-account-${CSV_ACCOUNT_ID}`));
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('new-import-choose'));
    });
    expect(mockCreate).toHaveBeenCalledWith({
      file: { uri: 'file://a.csv', name: 'a.csv', type: 'text/csv' },
      accountId: CSV_ACCOUNT_ID,
    });
    await waitFor(() =>
      expect(mockPush).toHaveBeenCalledWith(`/(tabs)/transactions/import/${BATCH_ID}`)
    );
  });

  it('omits accountId entirely on the OFX escape rather than sending a blank one', async () => {
    mockPick.mockResolvedValue({ uri: 'file://a.ofx', name: 'a.ofx', type: 'application/x-ofx' });
    mockCreate.mockResolvedValue(preview());
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId('new-import-account-from-file'));
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('new-import-choose'));
    });
    expect(mockCreate).toHaveBeenCalledWith({
      file: { uri: 'file://a.ofx', name: 'a.ofx', type: 'application/x-ofx' },
    });
  });

  it('sends the member to make one when no account can take a file', async () => {
    await renderScreen([account({ id: MANUAL_ACCOUNT_ID, sourceKind: 'MANUAL' })]);
    expect(screen.getByTestId('new-import-accounts-empty')).toBeTruthy();
    await act(async () => {
      fireEvent.press(screen.getByTestId('new-import-to-accounts'));
    });
    expect(mockPush).toHaveBeenCalledWith('/(tabs)/transactions/accounts');
  });
});

describe('choosing a file', () => {
  it('a canceled pick shows nothing and uploads nothing', async () => {
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId(`new-import-account-${CSV_ACCOUNT_ID}`));
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('new-import-choose'));
    });
    expect(mockPick).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('new-import-picker-refused')).toBeNull();
    expect(screen.queryByTestId('new-import-error')).toBeNull();
    expect(mockCreate).not.toHaveBeenCalled();
  });

  it('a file that is not a CSV or OFX is refused on screen and never uploaded', async () => {
    mockPick.mockRejectedValue(new Error(IMPORT_FILE_REFUSED_MESSAGE));
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId(`new-import-account-${CSV_ACCOUNT_ID}`));
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('new-import-choose'));
    });
    const notice = screen.getByTestId('new-import-picker-refused');
    expect(notice.props.children).toBe(IMPORT_FILE_REFUSED_MESSAGE);
    expect(notice.props.accessibilityRole).toBe('alert');
    expect(mockCreate).not.toHaveBeenCalled();
    // The member can try again with the right file.
    expect(screen.getByTestId('new-import-choose').props.accessibilityState.disabled).toBe(false);
  });
});

describe('the picked file after the upload', () => {
  // iOS opens the picker with `asCopy: true`, so the statement is copied into
  // the app's tmp Inbox; that copy must not outlive the upload. Android hands
  // back the member's own document (content://), which must never be deleted.
  const remove = (File.prototype as unknown as { delete: jest.Mock }).delete;
  const IOS_COPY = {
    uri: 'file:///tmp/com.trove.app-Inbox/a.csv',
    name: 'a.csv',
    type: 'text/csv',
  };

  afterEach(() => {
    jest.restoreAllMocks();
  });

  async function upload() {
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId(`new-import-account-${CSV_ACCOUNT_ID}`));
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('new-import-choose'));
    });
  }

  it('deletes the iOS copy once the upload succeeds', async () => {
    jest.replaceProperty(Platform, 'OS', 'ios');
    mockPick.mockResolvedValue(IOS_COPY);
    mockCreate.mockResolvedValue(preview());
    await upload();
    await waitFor(() => expect(remove).toHaveBeenCalledTimes(1));
    expect((remove.mock.contexts[0] as { uri: string }).uri).toBe(IOS_COPY.uri);
    expect(remove.mock.invocationCallOrder[0]).toBeGreaterThan(
      mockCreate.mock.invocationCallOrder[0]
    );
  });

  it('deletes the iOS copy when the upload fails too', async () => {
    jest.replaceProperty(Platform, 'OS', 'ios');
    mockPick.mockResolvedValue(IOS_COPY);
    mockCreate.mockRejectedValue(new Error('network'));
    await upload();
    await waitFor(() => expect(screen.getByTestId('new-import-error')).toBeTruthy());
    expect(remove).toHaveBeenCalledTimes(1);
    expect((remove.mock.contexts[0] as { uri: string }).uri).toBe(IOS_COPY.uri);
  });

  it('never deletes a content:// document — that is the member’s original', async () => {
    jest.replaceProperty(Platform, 'OS', 'android');
    mockPick.mockResolvedValue({ uri: 'content://p/document/1', name: 'a.csv', type: 'text/csv' });
    mockCreate.mockResolvedValue(preview());
    await upload();
    await waitFor(() => expect(mockPush).toHaveBeenCalled());
    expect(remove).not.toHaveBeenCalled();
  });

  it('a picked x.constructor is refused on screen and never uploaded', async () => {
    const actual = jest.requireActual('@lib/importFilePicker') as {
      pickImportFile: typeof pickImportFile;
    };
    (File.pickFileAsync as unknown as jest.Mock).mockResolvedValueOnce({
      canceled: false,
      result: { uri: 'file:///tmp/x.constructor', name: 'x.constructor', type: '' },
    });
    mockPick.mockImplementation(actual.pickImportFile);
    await upload();
    expect(screen.getByTestId('new-import-picker-refused').props.children).toBe(
      IMPORT_FILE_REFUSED_MESSAGE
    );
    expect(mockCreate).not.toHaveBeenCalled();
  });
});

describe('picking up a batch started elsewhere', () => {
  it('is disabled until a batch id is entered', async () => {
    await renderScreen();
    expect(screen.getByTestId('new-import-open').props.accessibilityState.disabled).toBe(true);
  });

  it('refuses a pasted value that is not a batch id, so it never reaches a path', async () => {
    await renderScreen();
    await act(async () => {
      fireEvent.changeText(
        screen.getByTestId('new-import-batch-id'),
        `../accounts/${CSV_ACCOUNT_ID}`
      );
    });
    expect(screen.getByTestId('new-import-batch-id-error')).toBeTruthy();
    expect(screen.getByTestId('new-import-open').props.accessibilityState.disabled).toBe(true);
    await act(async () => {
      fireEvent.press(screen.getByTestId('new-import-open'));
    });
    expect(mockPush).not.toHaveBeenCalled();
  });

  it('navigates to the batch, which is where commit and undo live', async () => {
    await renderScreen();
    await act(async () => {
      fireEvent.changeText(screen.getByTestId('new-import-batch-id'), `  ${BATCH_ID}  `);
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('new-import-open'));
    });
    expect(mockPush).toHaveBeenCalledWith(`/(tabs)/transactions/import/${BATCH_ID}`);
  });
});
