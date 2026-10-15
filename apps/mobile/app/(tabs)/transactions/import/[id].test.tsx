import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { useLocalSearchParams } from 'expo-router';
import { createTestQueryClient } from '@/test/queryClient';
import ImportBatchScreen from './[id]';
import { commitImport, fetchImportPreview, undoImport } from '@api/imports';
import { useAuthStore } from '@stores/authStore';
import type { ImportPreviewResponse, MeResponse, SyncLogResponse } from '@api/types';

jest.mock('@api/imports', () => ({
  fetchImportPreview: jest.fn(),
  commitImport: jest.fn(),
  undoImport: jest.fn(),
}));

const mockPreview = fetchImportPreview as jest.MockedFunction<typeof fetchImportPreview>;
const mockCommit = commitImport as jest.MockedFunction<typeof commitImport>;
const mockUndo = undoImport as jest.MockedFunction<typeof undoImport>;
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

function preview(overrides: Partial<ImportPreviewResponse> = {}): ImportPreviewResponse {
  return {
    id: '33333333-3333-4333-8333-333333333333',
    householdId: 'h1',
    accountId: 'a1',
    sourceKind: 'CSV',
    status: 'PREVIEW',
    originalFilename: 'statement-2026-09.csv',
    rowCount: 42,
    newCount: 39,
    duplicateCount: 2,
    fuzzyDuplicateCount: 0,
    invalidCount: 1,
    mapping: {
      dateColumn: 'Date',
      descriptionColumn: 'Description',
      amountColumn: 'Amount',
      debitColumn: null,
      creditColumn: null,
      currencyColumn: null,
      balanceColumn: null,
      dateFormat: null,
      delimiter: ',',
      charset: 'UTF-8',
      hasHeader: true,
    },
    dateFormatCandidates: [],
    rows: [],
    warnings: [],
    createdAt: '2026-09-05T03:20:00Z',
    ...overrides,
  };
}

const LOG: SyncLogResponse = {
  id: 'l1',
  householdId: 'h1',
  accountId: 'a1',
  sourceKind: 'CSV',
  status: 'SUCCESS',
  transactionsFetched: 42,
  transactionsNew: 39,
  transactionsUpdated: 0,
  transactionsSkipped: 2,
  transactionsFailed: 0,
  errorMessage: null,
  startedAt: '2026-09-05T03:21:00Z',
  completedAt: '2026-09-05T03:21:04Z',
};

let clients: ReturnType<typeof createTestQueryClient>[] = [];

async function renderScreen(batch: ImportPreviewResponse = preview()) {
  mockPreview.mockResolvedValue(batch);
  const qc = createTestQueryClient();
  clients.push(qc);
  const utils = render(
    <QueryClientProvider client={qc}>
      <ImportBatchScreen />
    </QueryClientProvider>
  );
  await waitFor(() => expect(screen.queryByTestId('import-loading')).toBeNull());
  return utils;
}

beforeEach(() => {
  jest.clearAllMocks();
  [mockPreview, mockCommit, mockUndo].forEach((mock) => mock.mockReset());
  mockParams.mockReturnValue({ id: '33333333-3333-4333-8333-333333333333' });
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

describe('the preview, before anything is written', () => {
  it('shows the new, duplicate, possible-duplicate and rejected counts', async () => {
    await renderScreen();
    expect(screen.getByTestId('import-preview-summary-NEW')).toBeTruthy();
    expect(screen.getByTestId('import-preview-summary-DUPLICATE')).toBeTruthy();
    expect(screen.getByTestId('import-preview-summary-FUZZY_DUPLICATE')).toBeTruthy();
    expect(screen.getByTestId('import-preview-summary-INVALID')).toBeTruthy();
    expect(screen.getByText('39')).toBeTruthy();
  });

  it('shows why a row was rejected, before the commit button', async () => {
    await renderScreen(
      preview({
        rows: [
          {
            ordinal: 7,
            date: null,
            description: null,
            merchantName: null,
            amount: null,
            currency: 'CAD',
            type: null,
            verdict: 'INVALID',
            reason: 'No amount could be read from this row.',
          },
        ],
      })
    );
    expect(screen.getByTestId('import-preview-summary-reason-7')).toBeTruthy();
    expect(screen.getByTestId('import-row-7')).toBeTruthy();
  });

  it('shows the detected column mapping', async () => {
    await renderScreen();
    expect(screen.getByTestId('import-mapping-detected')).toBeTruthy();
  });
});

describe('the ambiguous date format', () => {
  const AMBIGUOUS = preview({
    dateFormatCandidates: [
      {
        format: 'dd/MM/yyyy',
        sampleValues: ['03/09/2026'],
        interpretedDates: ['2026-09-03'],
      },
      {
        format: 'MM/dd/yyyy',
        sampleValues: ['03/09/2026'],
        interpretedDates: ['2026-03-09'],
      },
    ],
  });

  it('offers each candidate with the evidence that makes it ambiguous', async () => {
    await renderScreen(AMBIGUOUS);
    expect(screen.getByTestId('import-date-format-dd/MM/yyyy')).toBeTruthy();
    expect(screen.getByTestId('import-date-format-MM/dd/yyyy')).toBeTruthy();
  });

  it('sends the chosen format with the commit, and nothing the member did not change', async () => {
    mockCommit.mockResolvedValue(LOG);
    await renderScreen(AMBIGUOUS);
    await act(async () => {
      fireEvent.press(screen.getByTestId('import-date-format-dd/MM/yyyy'));
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('import-commit'));
    });
    expect(mockCommit).toHaveBeenCalledWith('33333333-3333-4333-8333-333333333333', {
      mapping: { dateFormat: 'dd/MM/yyyy' },
    });
    // Only the correction: the detected columns are not echoed back as if the
    // member had chosen them.
    expect(mockCommit.mock.calls[0][1]?.mapping).toEqual({ dateFormat: 'dd/MM/yyyy' });
  });

  it('shows no format question when the dates are unambiguous', async () => {
    await renderScreen();
    expect(screen.queryByTestId('import-date-format-dd/MM/yyyy')).toBeNull();
  });
});

describe('commit', () => {
  it('sends no body at all when the member corrected nothing', async () => {
    mockCommit.mockResolvedValue(LOG);
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId('import-commit'));
    });
    expect(mockCommit).toHaveBeenCalledWith('33333333-3333-4333-8333-333333333333', undefined);
  });

  it('sends a corrected column and leaves the untouched ones out', async () => {
    mockCommit.mockResolvedValue(LOG);
    await renderScreen();
    await act(async () => {
      fireEvent.changeText(screen.getByTestId('import-map-amount-column'), 'Debit');
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('import-commit'));
    });
    expect(mockCommit).toHaveBeenCalledWith('33333333-3333-4333-8333-333333333333', {
      mapping: { amountColumn: 'Debit' },
    });
  });

  it('reports what the commit wrote', async () => {
    mockCommit.mockResolvedValue(LOG);
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId('import-commit'));
    });
    await waitFor(() => expect(screen.getByTestId('import-commit-result')).toBeTruthy());
    const text = screen.getByTestId('import-commit-result').props.children.join('');
    expect(text).toContain('39');
    expect(text).toContain('2');
  });

  it('explains the 409 a committed batch answers', async () => {
    const { AxiosError } = jest.requireActual('axios') as typeof import('axios');
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const config = { headers: {} } as any;
    mockCommit.mockRejectedValue(
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
            detail: 'Already committed.',
          },
        },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
      } as any)
    );
    await renderScreen();
    await act(async () => {
      fireEvent.press(screen.getByTestId('import-commit'));
    });
    await waitFor(() => expect(screen.getByTestId('import-commit-error')).toBeTruthy());
    expect(screen.getByTestId('import-commit-error').props.children).toContain(
      'already been committed'
    );
  });

  it('offers no commit button on a batch that is no longer in PREVIEW', async () => {
    await renderScreen(preview({ status: 'COMMITTED', rows: [] }));
    expect(screen.queryByTestId('import-commit')).toBeNull();
    expect(screen.getByTestId('import-not-committable')).toBeTruthy();
  });
});

describe('undo', () => {
  it('is reachable on a COMMITTED batch, not only inside the wizard', async () => {
    await renderScreen(preview({ status: 'COMMITTED', rows: [] }));
    expect(screen.getByTestId('import-undo')).toBeTruthy();
  });

  it('asks first, and says edited rows are kept', async () => {
    await renderScreen(preview({ status: 'COMMITTED', rows: [] }));
    await act(async () => {
      fireEvent.press(screen.getByTestId('import-undo'));
    });
    expect(screen.getByTestId('import-undo-confirm-copy').props.children).toContain(
      'Rows you have changed by hand are kept.'
    );
    expect(mockUndo).not.toHaveBeenCalled();
  });

  it('undoes once confirmed', async () => {
    mockUndo.mockResolvedValue(undefined);
    await renderScreen(preview({ status: 'COMMITTED', rows: [] }));
    await act(async () => {
      fireEvent.press(screen.getByTestId('import-undo'));
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('import-undo-confirm'));
    });
    expect(mockUndo).toHaveBeenCalledWith('33333333-3333-4333-8333-333333333333');
  });

  it('is not offered at all on a PREVIEW batch, where it could only be refused', async () => {
    await renderScreen(preview({ status: 'PREVIEW', rows: [] }));
    // Every fresh upload lands in PREVIEW. A danger-styled control whose only
    // possible answer is 409 "never committed" is a dead end on the primary
    // path, so there is no control at all until the batch has written rows.
    expect(screen.queryByTestId('import-undo')).toBeNull();
    expect(screen.queryByTestId('import-undo-confirm')).toBeNull();
    expect(screen.getByTestId('import-commit')).toBeTruthy();
  });

  it('explains the 409 a batch that has already been undone answers', async () => {
    const { AxiosError } = jest.requireActual('axios') as typeof import('axios');
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const config = { headers: {} } as any;
    mockUndo.mockRejectedValue(
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
            detail: 'Never committed.',
          },
        },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
      } as any)
    );
    await renderScreen(preview({ status: 'COMMITTED', rows: [] }));
    await act(async () => {
      fireEvent.press(screen.getByTestId('import-undo'));
    });
    await act(async () => {
      fireEvent.press(screen.getByTestId('import-undo-confirm'));
    });
    await waitFor(() => expect(screen.getByTestId('import-undo-error')).toBeTruthy());
    expect(screen.getByTestId('import-undo-error').props.children).toContain('nothing to undo');
  });
});

describe('a batch that cannot be read', () => {
  it('shows the failure rather than an empty wizard', async () => {
    mockPreview.mockRejectedValue(new Error('boom'));
    const qc = createTestQueryClient();
    clients.push(qc);
    render(
      <QueryClientProvider client={qc}>
        <ImportBatchScreen />
      </QueryClientProvider>
    );
    await waitFor(() => expect(screen.getByTestId('import-error-message')).toBeTruthy());
  });
});
