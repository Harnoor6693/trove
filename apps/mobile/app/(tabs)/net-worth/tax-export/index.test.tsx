import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import TaxExportRunsScreen from './index';
import { taxExportKeys } from '@hooks/useTaxExport';
import {
  createTaxExportRun,
  deleteTaxExportRun,
  fetchTaxExportRun,
  fetchTaxExportRunFile,
  fetchTaxExportRuns,
} from '@api/taxexport';
import { fetchTaxExportRunBytes } from '@api/taxexport';
import { File } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import type { TaxExportRunResponse } from '@api/types';

jest.mock('@api/taxexport', () => ({
  fetchTaxLines: jest.fn(),
  setTaxLine: jest.fn(),
  fetchTaxExportRuns: jest.fn(),
  createTaxExportRun: jest.fn(),
  fetchTaxExportRun: jest.fn(),
  deleteTaxExportRun: jest.fn(),
  fetchTaxExportRunFile: jest.fn(),
  fetchTaxExportRunBytes: jest.fn(),
}));

const mockRuns = fetchTaxExportRuns as jest.MockedFunction<typeof fetchTaxExportRuns>;
const mockCreate = createTaxExportRun as jest.MockedFunction<typeof createTaxExportRun>;
const mockRun = fetchTaxExportRun as jest.MockedFunction<typeof fetchTaxExportRun>;
const mockDelete = deleteTaxExportRun as jest.MockedFunction<typeof deleteTaxExportRun>;
const mockFile = fetchTaxExportRunFile as jest.MockedFunction<typeof fetchTaxExportRunFile>;
const mockBytes = fetchTaxExportRunBytes as jest.MockedFunction<typeof fetchTaxExportRunBytes>;
const mockShare = Sharing.shareAsync as jest.Mock;
const fileWrite = (File.prototype as unknown as { write: jest.Mock }).write;
const fileDelete = (File.prototype as unknown as { delete: jest.Mock }).delete;

const PENDING_RUN: TaxExportRunResponse = {
  id: 'run1',
  userId: 'u1',
  taxYear: 2025,
  jurisdiction: 'CA',
  format: 'CSV',
  status: 'PENDING',
  fileSizeBytes: null,
  errorMessage: null,
  createdAt: '2026-09-05T14:00:00Z',
  updatedAt: '2026-09-05T14:00:00Z',
};

const COMPLETE_RUN: TaxExportRunResponse = {
  ...PENDING_RUN,
  id: 'run2',
  status: 'COMPLETE',
  fileSizeBytes: 4096,
};

async function renderScreen() {
  const client = createTestQueryClient();
  await render(
    <QueryClientProvider client={client}>
      <TaxExportRunsScreen />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

it('lists runs and shows "still generating" for a PENDING run, never an error', async () => {
  mockRuns.mockResolvedValue({ data: [PENDING_RUN], meta: { total: 1, page: 0, limit: 20 } });
  mockRun.mockResolvedValue(PENDING_RUN);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('tax-export-run-run1')).toBeTruthy());
  expect(screen.getByTestId('tax-export-run-run1-generating')).toBeTruthy();
  expect(screen.queryByTestId('tax-export-run-run1-view')).toBeNull();
});

it('starts an export with the chosen year and format', async () => {
  mockRuns.mockResolvedValue({ data: [], meta: { total: 0, page: 0, limit: 20 } });
  mockCreate.mockResolvedValue(PENDING_RUN);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('tax-export-new-toggle')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('tax-export-new-toggle'));
  await fireEvent.changeText(screen.getByTestId('tax-export-create-form-year'), '2025');
  await fireEvent.press(screen.getByTestId('tax-export-create-form-format-PDF'));
  await fireEvent.press(screen.getByTestId('tax-export-create-form-submit'));

  await waitFor(() => expect(mockCreate).toHaveBeenCalledWith({ taxYear: 2025, format: 'PDF' }));
});

it('offers "View" only once a run is COMPLETE, and previews CSV text in-app', async () => {
  mockRuns.mockResolvedValue({ data: [COMPLETE_RUN], meta: { total: 1, page: 0, limit: 20 } });
  mockRun.mockResolvedValue(COMPLETE_RUN);
  mockFile.mockResolvedValue({
    contentType: 'text/csv',
    text: 'date,amount,category\n2026-01-01,12.34,Software\n',
    byteLength: 42,
  });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('tax-export-run-run2-view')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('tax-export-run-run2-view'));

  await waitFor(() => expect(mockFile).toHaveBeenCalledWith('run2', 'CSV'));
  await waitFor(() => expect(screen.getByTestId('tax-export-run-run2-preview')).toBeTruthy());
  expect(screen.getByText(/Software/)).toBeTruthy();
});

it('a PDF run shows only its size — no PDF content is ever rendered', async () => {
  const pdfRun = { ...COMPLETE_RUN, id: 'run3', format: 'PDF' as const };
  mockRuns.mockResolvedValue({ data: [pdfRun], meta: { total: 1, page: 0, limit: 20 } });
  mockRun.mockResolvedValue(pdfRun);
  mockFile.mockResolvedValue({ contentType: 'application/pdf', text: null, byteLength: 2048 });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('tax-export-run-run3-view')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('tax-export-run-run3-view'));

  await waitFor(() => expect(screen.getByTestId('tax-export-run-run3-pdf-info')).toBeTruthy());
  expect(screen.queryByTestId('tax-export-run-run3-preview')).toBeNull();
});

it('a 409 mid-view shows "still generating", not a failure', async () => {
  mockRuns.mockResolvedValue({ data: [COMPLETE_RUN], meta: { total: 1, page: 0, limit: 20 } });
  mockRun.mockResolvedValue(COMPLETE_RUN);
  const conflict = Object.assign(new Error('not ready'), {
    isAxiosError: true,
    response: {
      status: 409,
      data: {
        success: false,
        error: {
          type: 'https://trove.finance/errors/conflict',
          status: 409,
          title: 'conflict',
          detail: 'Not ready.',
          instance: '/x',
        },
      },
    },
  });
  mockFile.mockRejectedValue(conflict);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('tax-export-run-run2-view')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('tax-export-run-run2-view'));

  await waitFor(() => expect(screen.getByTestId('tax-export-run-run2-not-ready')).toBeTruthy());
  expect(screen.queryByTestId('tax-export-run-run2-view-error')).toBeNull();
});

it('dismissing the preview clears it from the screen and out of the mutation cache', async () => {
  mockRuns.mockResolvedValue({ data: [COMPLETE_RUN], meta: { total: 1, page: 0, limit: 20 } });
  mockRun.mockResolvedValue(COMPLETE_RUN);
  mockFile.mockResolvedValue({
    contentType: 'text/csv',
    text: 'date,amount,category\n2026-01-01,12.34,Software\n',
    byteLength: 42,
  });
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('tax-export-run-run2-view')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('tax-export-run-run2-view'));
  await waitFor(() => expect(screen.getByTestId('tax-export-run-run2-preview')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('tax-export-run-run2-dismiss'));

  // The whole household's ledger for a tax year should not linger on screen,
  // or in the mutation's own cached result, once dismissed.
  expect(screen.queryByTestId('tax-export-run-run2-preview')).toBeNull();
  expect(screen.queryByTestId('tax-export-run-run2-dismiss')).toBeNull();
});

it('deletes a run after confirmation, safe in any status', async () => {
  mockRuns.mockResolvedValue({ data: [PENDING_RUN], meta: { total: 1, page: 0, limit: 20 } });
  mockRun.mockResolvedValue(PENDING_RUN);
  mockDelete.mockResolvedValue(undefined);
  await renderScreen();
  await waitFor(() => expect(screen.getByTestId('tax-export-run-run1-delete')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('tax-export-run-run1-delete'));
  expect(mockDelete).not.toHaveBeenCalled();
  await fireEvent.press(screen.getByTestId('tax-export-run-run1-delete-confirm'));
  await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('run1'));
});

describe('save or share', () => {
  const conflict = () =>
    Object.assign(new Error('not ready'), {
      isAxiosError: true,
      response: {
        status: 409,
        data: {
          success: false,
          error: {
            type: 'https://trove.finance/errors/conflict',
            status: 409,
            title: 'conflict',
            detail: 'Not ready.',
            instance: '/x',
          },
        },
      },
    });

  it('offers no "Save or share" until the run is COMPLETE', async () => {
    mockRuns.mockResolvedValue({ data: [PENDING_RUN], meta: { total: 1, page: 0, limit: 20 } });
    mockRun.mockResolvedValue(PENDING_RUN);
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('tax-export-run-run1')).toBeTruthy());
    expect(screen.queryByTestId('tax-export-share-run1')).toBeNull();
  });

  it('shares a CSV run as tax-export-<year>.csv from a file:// cache uri, then deletes it', async () => {
    mockRuns.mockResolvedValue({ data: [COMPLETE_RUN], meta: { total: 1, page: 0, limit: 20 } });
    mockRun.mockResolvedValue(COMPLETE_RUN);
    mockBytes.mockResolvedValue(new Uint8Array([0x61, 0x2c, 0x62]).buffer);
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('tax-export-share-run2')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('tax-export-share-run2'));

    await waitFor(() => expect(mockShare).toHaveBeenCalledTimes(1));
    expect(mockBytes).toHaveBeenCalledWith('run2', 'CSV');
    expect(Array.from(fileWrite.mock.calls[0][0] as Uint8Array)).toEqual([0x61, 0x2c, 0x62]);
    expect(mockShare).toHaveBeenCalledWith('file:///cache/tax-export-2025.csv', {
      mimeType: 'text/csv',
      UTI: 'public.comma-separated-values-text',
      dialogTitle: 'tax-export-2025.csv',
    });
    expect(String(mockShare.mock.calls[0][0])).not.toMatch(/token/i);
    await waitFor(() => expect(fileDelete).toHaveBeenCalledTimes(1));
    // The in-app CSV preview is still there alongside the share.
    expect(screen.getByTestId('tax-export-run-run2-view')).toBeTruthy();
  });

  it('shares a PDF run as tax-export-<year>.pdf', async () => {
    const pdfRun = { ...COMPLETE_RUN, id: 'run3', format: 'PDF' as const };
    mockRuns.mockResolvedValue({ data: [pdfRun], meta: { total: 1, page: 0, limit: 20 } });
    mockRun.mockResolvedValue(pdfRun);
    mockBytes.mockResolvedValue(new Uint8Array([0x25, 0x50, 0x44, 0x46]).buffer);
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('tax-export-share-run3')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('tax-export-share-run3'));

    await waitFor(() =>
      expect(mockShare).toHaveBeenCalledWith('file:///cache/tax-export-2025.pdf', {
        mimeType: 'application/pdf',
        UTI: 'com.adobe.pdf',
        dialogTitle: 'tax-export-2025.pdf',
      })
    );
    expect(mockBytes).toHaveBeenCalledWith('run3', 'PDF');
  });

  it('a 409 on share shows "still generating", not an error', async () => {
    mockRuns.mockResolvedValue({ data: [COMPLETE_RUN], meta: { total: 1, page: 0, limit: 20 } });
    mockRun.mockResolvedValue(COMPLETE_RUN);
    mockBytes.mockRejectedValue(conflict());
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('tax-export-share-run2')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('tax-export-share-run2'));

    await waitFor(() => expect(screen.getByTestId('tax-export-run-run2-not-ready')).toBeTruthy());
    expect(screen.queryByTestId('tax-export-run-run2-share-error')).toBeNull();
    expect(mockShare).not.toHaveBeenCalled();
  });

  it('any other share failure is an alert', async () => {
    mockRuns.mockResolvedValue({ data: [COMPLETE_RUN], meta: { total: 1, page: 0, limit: 20 } });
    mockRun.mockResolvedValue(COMPLETE_RUN);
    mockBytes.mockResolvedValue(new ArrayBuffer(1));
    (Sharing.isAvailableAsync as jest.Mock).mockResolvedValueOnce(false);
    await renderScreen();
    await waitFor(() => expect(screen.getByTestId('tax-export-share-run2')).toBeTruthy());

    await fireEvent.press(screen.getByTestId('tax-export-share-run2'));

    await waitFor(() =>
      expect(screen.getByTestId('tax-export-run-run2-share-error').props.accessibilityRole).toBe(
        'alert'
      )
    );
    expect(fileWrite).not.toHaveBeenCalled();
  });
});

// Bug found on the Android device run (step 29): a run created on this screen
// is seeded into its own query as PENDING; when the refreshed list already
// says COMPLETE, polling is switched off by the list row, and the stale
// seeded row used to win forever — the card said "Queued" with no "Save or
// share" for a finished PDF. A terminal list row is final and must win.
it('a run the list already reports COMPLETE is shown as Ready even if its own query still holds PENDING', async () => {
  mockRuns.mockResolvedValue({ data: [COMPLETE_RUN], meta: { total: 1, page: 0, limit: 20 } });
  mockRun.mockResolvedValue({ ...COMPLETE_RUN, status: 'PENDING', fileSizeBytes: null });
  // A real gcTime, as in the app: with the test default of 0 the seeded row
  // is collected before the card mounts and the bug cannot show.
  const client = createTestQueryClient({ gcTime: 60_000 });
  client.setQueryData(taxExportKeys.run('run2'), {
    ...COMPLETE_RUN,
    status: 'PENDING',
    fileSizeBytes: null,
  });
  await render(
    <QueryClientProvider client={client}>
      <TaxExportRunsScreen />
    </QueryClientProvider>
  );
  await waitFor(() => expect(screen.getByTestId('tax-export-run-run2')).toBeTruthy());
  expect(screen.getByTestId('tax-export-share-run2')).toBeTruthy();
  expect(screen.getByText('Ready')).toBeTruthy();
  expect(screen.queryByTestId('tax-export-run-run2-generating')).toBeNull();
  client.clear();
});
