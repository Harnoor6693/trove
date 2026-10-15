import React from 'react';
import { AxiosError } from 'axios';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import { createTestQueryClient } from '@/test/queryClient';
import DataExportScreen from './export';
import {
  createDataExport,
  fetchDataExport,
  fetchDataExportArchive,
  fetchDataExports,
} from '@api/dataexport';
import { useAuthStore } from '@stores/authStore';
import { File } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import type { DataExportRunResponse, MeResponse } from '@api/types';

jest.mock('@api/dataexport', () => ({
  createDataExport: jest.fn(),
  fetchDataExports: jest.fn(),
  fetchDataExport: jest.fn(),
  fetchDataExportArchive: jest.fn(),
}));

const mockCreate = createDataExport as jest.MockedFunction<typeof createDataExport>;
const mockList = fetchDataExports as jest.MockedFunction<typeof fetchDataExports>;
const mockGet = fetchDataExport as jest.MockedFunction<typeof fetchDataExport>;
const mockArchive = fetchDataExportArchive as jest.MockedFunction<typeof fetchDataExportArchive>;

const ME: MeResponse = {
  userId: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada',
  householdId: 'h1',
  role: 'MEMBER',
  totpEnabled: false,
  currency: 'CAD',
  country: 'CA',
  timezone: 'America/Halifax',
  locale: 'en-CA',
  onboardingCompleted: true,
};

const RUN: DataExportRunResponse = {
  id: 'e1',
  userId: 'u1',
  status: 'PENDING',
  fileSizeBytes: null,
  errorMessage: null,
  expiresAt: null,
  createdAt: '2026-09-18T14:00:00Z',
  updatedAt: '2026-09-18T14:00:00Z',
};

const COMPLETE: DataExportRunResponse = {
  ...RUN,
  status: 'COMPLETE',
  fileSizeBytes: 20480,
  expiresAt: '2026-09-19T14:00:02Z',
};

function refused(status: number, slug: string, detail: string) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const config = { headers: {} } as any;
  return new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, {
    data: {
      success: false,
      error: { type: `https://trove.finance/errors/${slug}`, title: slug, status, detail },
    },
    status,
    statusText: '',
    headers: {},
    config,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any);
}

/** Flatten a `<Text>`'s children, which JSX splits into an array around an entity. */
function textOf(testID: string): string {
  const { children } = screen.getByTestId(testID).props as { children: unknown };
  return (Array.isArray(children) ? children : [children]).join('');
}

let client: ReturnType<typeof createTestQueryClient>;

async function renderScreen() {
  client = createTestQueryClient();
  return render(
    <QueryClientProvider client={client}>
      <DataExportScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  useAuthStore.setState({ me: ME });
  mockList.mockResolvedValue({ data: [], meta: { page: 0, limit: 20, total: 0 } });
});

afterEach(() => {
  client.clear();
});

describe('what the screen says before it asks for anything', () => {
  it('states that the archive holds the household’s ledger, not only this member’s rows', async () => {
    await renderScreen();
    expect(textOf('export-household-note')).toMatch(/household/);
  });

  it('states that receipts are metadata only — the photographs are not in the archive', async () => {
    await renderScreen();
    expect(textOf('export-receipts-note')).toMatch(/photographs themselves are not in the archive/);
  });

  it('states the one-a-day cap and the 24-hour link', async () => {
    await renderScreen();
    const limits = textOf('export-limits-note');
    expect(limits).toMatch(/One export a day/);
    expect(limits).toMatch(/24 hours/);
  });
});

describe('starting a run', () => {
  it('sends the step-up body and then polls the returned run to completion', async () => {
    mockCreate.mockResolvedValue(RUN);
    mockGet.mockResolvedValueOnce({ ...RUN, status: 'GENERATING' }).mockResolvedValue(COMPLETE);
    await renderScreen();

    await fireEvent.changeText(
      screen.getByTestId('export-step-up-password'),
      'correct-horse-battery'
    );
    await fireEvent.press(screen.getByTestId('export-step-up-submit'));

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith({ password: 'correct-horse-battery' })
    );
    await waitFor(() => expect(screen.getByTestId('export-generating')).toBeTruthy());
    await waitFor(() => expect(screen.getByTestId('export-ready')).toBeTruthy(), {
      timeout: 5000,
    });
    expect(textOf('export-ready')).toMatch(/20 KB/);
  });

  it('never writes the step-up credential to the secure store, and never leaves it in the tree', async () => {
    mockCreate.mockResolvedValue(RUN);
    mockGet.mockResolvedValue(RUN);
    await renderScreen();

    await fireEvent.changeText(
      screen.getByTestId('export-step-up-password'),
      'correct-horse-battery'
    );
    await fireEvent.press(screen.getByTestId('export-step-up-submit'));
    await waitFor(() => expect(mockCreate).toHaveBeenCalled());

    expect(SecureStore.setItemAsync as jest.Mock).not.toHaveBeenCalled();
    expect(JSON.stringify(screen.toJSON())).not.toContain('correct-horse-battery');
    // Nor in the query cache: the run is keyed by id, and the credential is a
    // mutation variable that the screen resets on settle.
    expect(
      JSON.stringify(
        client
          .getQueryCache()
          .getAll()
          .map((q) => q.state.data)
      )
    ).not.toContain('correct-horse-battery');
  });

  it('shows a wrong password as the step-up refusal, not a generic failure', async () => {
    mockCreate.mockRejectedValue(
      refused(403, 'step-up-required', 'Step-up authentication required.')
    );
    await renderScreen();
    await fireEvent.changeText(screen.getByTestId('export-step-up-password'), 'wrong');
    await fireEvent.press(screen.getByTestId('export-step-up-submit'));
    await waitFor(() =>
      expect(screen.getByTestId('export-step-up-error').props.children).toBe(
        'That password or code was not accepted.'
      )
    );
  });

  it('shows the second-run-today 409 with the server’s own timing rather than a client timer', async () => {
    mockCreate.mockRejectedValue(
      refused(409, 'conflict', 'You can ask for another export after 2026-09-19.')
    );
    await renderScreen();
    await fireEvent.changeText(
      screen.getByTestId('export-step-up-password'),
      'correct-horse-battery'
    );
    await fireEvent.press(screen.getByTestId('export-step-up-submit'));
    await waitFor(() =>
      expect(screen.getByTestId('export-create-conflict').props.children).toBe(
        'You can ask for another export after 2026-09-19.'
      )
    );
  });

  // `me` is fetched best-effort and both `login.tsx` and `AppLock` swallow a
  // failed fetch, so `null` here is a real state for an enrolled member. Hiding
  // the code field from them would send a password-only body, earn a 403, and
  // leave the screen with nowhere to type the code — a dead end on a
  // security-critical action.
  it('still offers the code field when the profile has not loaded', async () => {
    useAuthStore.setState({ me: null });
    mockCreate.mockResolvedValue(RUN);
    mockGet.mockResolvedValue(RUN);
    await renderScreen();

    expect(screen.getByTestId('export-step-up-code')).toBeTruthy();
    await fireEvent.changeText(
      screen.getByTestId('export-step-up-password'),
      'correct-horse-battery'
    );
    await fireEvent.changeText(screen.getByTestId('export-step-up-code'), '000000');
    await fireEvent.press(screen.getByTestId('export-step-up-submit'));
    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith({
        password: 'correct-horse-battery',
        code: '000000',
      })
    );
  });

  it('asks for a code as well when the account has a second factor', async () => {
    useAuthStore.setState({ me: { ...ME, totpEnabled: true } });
    mockCreate.mockResolvedValue(RUN);
    mockGet.mockResolvedValue(RUN);
    await renderScreen();

    await fireEvent.changeText(
      screen.getByTestId('export-step-up-password'),
      'correct-horse-battery'
    );
    await fireEvent.changeText(screen.getByTestId('export-step-up-code'), '000000');
    await fireEvent.press(screen.getByTestId('export-step-up-submit'));
    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith({
        password: 'correct-horse-battery',
        code: '000000',
      })
    );
  });
});

describe('the run list and the download', () => {
  it('shows an empty state when there are no runs', async () => {
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('export-runs-empty')).toBeTruthy());
  });

  it('shows a FAILED run’s reason rather than a spinner that never stops', async () => {
    mockList.mockResolvedValue({
      data: [{ ...RUN, status: 'FAILED', errorMessage: 'The export could not be queued' }],
      meta: { page: 0, limit: 20, total: 1 },
    });
    await renderScreen();
    await waitFor(() =>
      expect(screen.getByTestId('export-failed').props.children).toBe(
        'The export could not be queued'
      )
    );
  });

  // The size shown next to a COMPLETE run comes from the run row, not from
  // downloading the archive — pulling the household's whole ledger into memory
  // just to read its length would be a costly no-op on a mobile connection.
  // The archive is only fetched when the member taps "Save or share" (see the
  // next test).
  it('never downloads the archive to report a size the run row already carries', async () => {
    mockList.mockResolvedValue({ data: [COMPLETE], meta: { page: 0, limit: 20, total: 1 } });
    await renderScreen();

    await waitFor(() => expect(screen.getByTestId('export-ready')).toBeTruthy());
    expect(screen.getByTestId('export-ready').props.children).toContain(' · 20 KB');
    expect(screen.queryByTestId('export-check-btn')).toBeNull();
    expect(mockArchive).not.toHaveBeenCalled();
    expect(textOf('export-archive-note')).toMatch(/Save or share/);
  });

  it('saves or shares a COMPLETE archive as trove-export.zip, then deletes the cache copy', async () => {
    mockList.mockResolvedValue({ data: [COMPLETE], meta: { page: 0, limit: 20, total: 1 } });
    mockArchive.mockResolvedValue({
      contentType: 'application/zip',
      byteLength: 4,
      bytes: new Uint8Array([80, 75, 3, 4]).buffer,
    });
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('data-export-share')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('data-export-share'));

    await waitFor(() => expect(Sharing.shareAsync).toHaveBeenCalledTimes(1));
    expect(mockArchive).toHaveBeenCalledWith('e1');
    const write = (File.prototype as unknown as { write: jest.Mock }).write;
    expect(Array.from(write.mock.calls[0][0] as Uint8Array)).toEqual([80, 75, 3, 4]);
    expect(Sharing.shareAsync).toHaveBeenCalledWith('file:///cache/trove-export.zip', {
      mimeType: 'application/zip',
      UTI: 'public.zip-archive',
      dialogTitle: 'trove-export.zip',
    });
    const remove = (File.prototype as unknown as { delete: jest.Mock }).delete;
    await waitFor(() => expect(remove).toHaveBeenCalledTimes(1));
  });

  it('offers no "Save or share" for a run that is not COMPLETE', async () => {
    mockList.mockResolvedValue({ data: [RUN], meta: { page: 0, limit: 20, total: 1 } });
    mockGet.mockResolvedValue(RUN);
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('export-generating')).toBeTruthy());
    expect(screen.queryByTestId('data-export-share')).toBeNull();
  });

  it('renders a 409 (not ready, or expired) through the API failure, not a crash', async () => {
    mockList.mockResolvedValue({ data: [COMPLETE], meta: { page: 0, limit: 20, total: 1 } });
    mockArchive.mockRejectedValue(
      refused(409, 'conflict', 'That export has expired. Start a new one.')
    );
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('data-export-share')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('data-export-share'));

    await waitFor(() =>
      expect(screen.getByTestId('data-export-share-error').props.children).toBe(
        'That export has expired. Start a new one.'
      )
    );
    expect(Sharing.shareAsync).not.toHaveBeenCalled();
  });
});
