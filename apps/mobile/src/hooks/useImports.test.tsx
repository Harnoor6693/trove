import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import {
  importKeys,
  useCommitImport,
  useCreateImport,
  useImportPreview,
  useUndoImport,
} from './useImports';
import { commitImport, createImport, fetchImportPreview, undoImport } from '@api/imports';
import type { ImportPreviewResponse, SyncLogResponse } from '@api/types';

jest.mock('@api/imports', () => ({
  createImport: jest.fn(),
  fetchImportPreview: jest.fn(),
  commitImport: jest.fn(),
  undoImport: jest.fn(),
}));

const mockCreate = createImport as jest.MockedFunction<typeof createImport>;
const mockPreview = fetchImportPreview as jest.MockedFunction<typeof fetchImportPreview>;
const mockCommit = commitImport as jest.MockedFunction<typeof commitImport>;
const mockUndo = undoImport as jest.MockedFunction<typeof undoImport>;

const PREVIEW: ImportPreviewResponse = {
  id: 'b1',
  householdId: 'h1',
  accountId: 'a1',
  sourceKind: 'CSV',
  status: 'PREVIEW',
  originalFilename: 'statement.csv',
  rowCount: 3,
  newCount: 2,
  duplicateCount: 1,
  fuzzyDuplicateCount: 0,
  invalidCount: 0,
  dateFormatCandidates: [],
  rows: [],
  warnings: [],
  createdAt: '2026-09-05T03:20:00Z',
};

const LOG: SyncLogResponse = {
  id: 'l1',
  householdId: 'h1',
  accountId: 'a1',
  sourceKind: 'CSV',
  status: 'SUCCESS',
  transactionsFetched: 3,
  transactionsNew: 2,
  transactionsUpdated: 0,
  transactionsSkipped: 1,
  transactionsFailed: 0,
  errorMessage: null,
  startedAt: '2026-09-05T03:21:00Z',
  completedAt: '2026-09-05T03:21:02Z',
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

describe('importKeys', () => {
  it('keys the preview under the batch', () => {
    expect(importKeys.all()).toEqual(['imports']);
    expect(importKeys.preview('b1')).toEqual(['imports', 'b1', 'preview']);
  });
});

describe('useImportPreview', () => {
  it('reads the batch and its verdict counts', async () => {
    mockPreview.mockResolvedValue(PREVIEW);
    const { result } = await renderHook(() => useImportPreview('b1'), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual(PREVIEW));
    expect(mockPreview).toHaveBeenCalledWith('b1');
  });

  it('stays idle without a batch id', async () => {
    const { result } = await renderHook(() => useImportPreview(''), { wrapper });
    expect(result.current.fetchStatus).toBe('idle');
    expect(mockPreview).not.toHaveBeenCalled();
  });

  it('surfaces a 404 rather than an empty preview', async () => {
    mockPreview.mockRejectedValue(new Error('not found'));
    const { result } = await renderHook(() => useImportPreview('b1'), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
  });
});

describe('useCreateImport', () => {
  it('seeds the preview cache from the upload response, so the wizard does not refetch', async () => {
    mockCreate.mockResolvedValue(PREVIEW);
    const { qc, provider } = withClient();
    const seed = jest.spyOn(qc, 'setQueryData');
    const { result } = await renderHook(() => useCreateImport(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({
        file: { uri: 'file://s.csv', name: 's.csv', type: 'text/csv' },
        accountId: 'a1',
      });
    });
    expect(seed).toHaveBeenCalledWith(importKeys.preview('b1'), PREVIEW);
  });
});

describe('useCommitImport', () => {
  it('sends the corrected mapping and invalidates the ledger it just wrote', async () => {
    mockCommit.mockResolvedValue(LOG);
    const { invalidate, provider } = withClient();
    const { result } = await renderHook(() => useCommitImport('b1'), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({ mapping: { dateFormat: 'dd/MM/yyyy' } });
    });
    expect(mockCommit).toHaveBeenCalledWith('b1', { mapping: { dateFormat: 'dd/MM/yyyy' } });
    const keys = invalidate.mock.calls.map(([arg]) => arg?.queryKey);
    expect(keys).toContainEqual(['imports', 'b1', 'preview']);
    expect(keys).toContainEqual(['transactions']);
    expect(keys).toContainEqual(['dashboard']);
  });

  it('sends no body when nothing was corrected', async () => {
    mockCommit.mockResolvedValue(LOG);
    const { provider } = withClient();
    const { result } = await renderHook(() => useCommitImport('b1'), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync(undefined);
    });
    expect(mockCommit).toHaveBeenCalledWith('b1', undefined);
  });
});

describe('useUndoImport', () => {
  it('undoes the batch and invalidates the ledger, accounts and dashboard', async () => {
    mockUndo.mockResolvedValue(undefined);
    const { invalidate, provider } = withClient();
    const { result } = await renderHook(() => useUndoImport(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('b1');
    });
    expect(mockUndo).toHaveBeenCalledWith('b1');
    const keys = invalidate.mock.calls.map(([arg]) => arg?.queryKey);
    expect(keys).toContainEqual(['imports', 'b1', 'preview']);
    expect(keys).toContainEqual(['transactions']);
    expect(keys).toContainEqual(['accounts']);
    expect(keys).toContainEqual(['dashboard']);
  });
});
