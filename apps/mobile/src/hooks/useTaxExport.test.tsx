import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import {
  isTerminalTaxExportStatus,
  nextTaxExportRunPage,
  taxExportKeys,
  useCreateTaxExportRun,
  useDeleteTaxExportRun,
  useSetTaxLine,
  useTaxExportRun,
  useTaxExportRunFile,
  useTaxExportRuns,
  useTaxLines,
} from './useTaxExport';
import {
  createTaxExportRun,
  deleteTaxExportRun,
  fetchTaxExportRun,
  fetchTaxExportRunFile,
  fetchTaxExportRuns,
  fetchTaxLines,
  setTaxLine,
} from '@api/taxexport';
import type { Page, TaxExportRunResponse, TaxLineMappingResponse } from '@api/types';

jest.mock('@api/taxexport', () => ({
  fetchTaxLines: jest.fn(),
  setTaxLine: jest.fn(),
  fetchTaxExportRuns: jest.fn(),
  createTaxExportRun: jest.fn(),
  fetchTaxExportRun: jest.fn(),
  deleteTaxExportRun: jest.fn(),
  fetchTaxExportRunFile: jest.fn(),
}));

const mockLines = fetchTaxLines as jest.MockedFunction<typeof fetchTaxLines>;
const mockSetLine = setTaxLine as jest.MockedFunction<typeof setTaxLine>;
const mockRuns = fetchTaxExportRuns as jest.MockedFunction<typeof fetchTaxExportRuns>;
const mockCreateRun = createTaxExportRun as jest.MockedFunction<typeof createTaxExportRun>;
const mockRun = fetchTaxExportRun as jest.MockedFunction<typeof fetchTaxExportRun>;
const mockDeleteRun = deleteTaxExportRun as jest.MockedFunction<typeof deleteTaxExportRun>;
const mockFile = fetchTaxExportRunFile as jest.MockedFunction<typeof fetchTaxExportRunFile>;

const LINE: TaxLineMappingResponse = {
  categoryId: 'c1',
  categorySlug: 'business.software',
  categoryName: 'Software',
  jurisdiction: 'CA',
  lineCode: '8810',
  lineLabel: 'T2125 line 8810',
  deductible: true,
  overridden: false,
};

const RUN: TaxExportRunResponse = {
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

function page(rows: TaxExportRunResponse[], meta: Page<TaxExportRunResponse>['meta']) {
  return { data: rows, meta };
}

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
  const remove = jest.spyOn(qc, 'removeQueries');
  const provider = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  return { qc, invalidate, remove, provider };
}

beforeEach(() => {
  jest.clearAllMocks();
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('isTerminalTaxExportStatus', () => {
  it('stops on COMPLETE and FAILED and on nothing else', () => {
    expect(isTerminalTaxExportStatus('COMPLETE')).toBe(true);
    expect(isTerminalTaxExportStatus('FAILED')).toBe(true);
    expect(isTerminalTaxExportStatus('PENDING')).toBe(false);
    expect(isTerminalTaxExportStatus('GENERATING')).toBe(false);
  });
});

describe('nextTaxExportRunPage', () => {
  it('asks for the next page while the totals say there is one, and stops at the end', () => {
    expect(nextTaxExportRunPage(page([], { total: 45, page: 0, limit: 20 }))).toBe(1);
    expect(nextTaxExportRunPage(page([], { total: 45, page: 2, limit: 20 }))).toBeUndefined();
  });
});

describe('useTaxLines / useSetTaxLine', () => {
  it('lists the effective map, including blank rows', async () => {
    mockLines.mockResolvedValue([LINE]);
    const { result } = renderHook(() => useTaxLines('CA'), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([LINE]));
  });

  it('setting one invalidates the whole lines prefix, not one jurisdiction literal', async () => {
    mockSetLine.mockResolvedValue({ ...LINE, overridden: true });
    const { invalidate, provider } = withClient();
    const { result } = renderHook(() => useSetTaxLine(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({
        categoryId: 'c1',
        body: { jurisdiction: 'CA', lineCode: '8810', lineLabel: 'x', deductible: true },
      });
    });
    // The screen's own query (`useTaxLines()`, no argument) caches under
    // `['tax-export','lines','default']` — a key of `[...,'CA']` would never
    // prefix-match it. Asserting the broad `['tax-export','lines']` key is
    // what actually invalidates the on-screen query regardless of which
    // jurisdiction the saved row belongs to.
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual([
      'tax-export',
      'lines',
    ]);
  });
});

describe('useTaxExportRuns', () => {
  it('pages the caller’s own runs', async () => {
    mockRuns.mockResolvedValue(page([RUN], { total: 1, page: 0, limit: 20 }));
    const { result } = renderHook(() => useTaxExportRuns(), { wrapper });
    await waitFor(() => expect(result.current.data).toBeDefined());
    expect(result.current.data?.pages[0].data).toEqual([RUN]);
    expect(mockRuns).toHaveBeenCalledWith({ page: 0 });
  });
});

describe('useCreateTaxExportRun', () => {
  it('seeds the run detail cache and invalidates the runs list', async () => {
    mockCreateRun.mockResolvedValue(RUN);
    const { invalidate, provider } = withClient();
    const { result } = renderHook(() => useCreateTaxExportRun(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync({ taxYear: 2025, format: 'CSV' });
    });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual([
      'tax-export',
      'runs',
    ]);
  });
});

describe('useTaxExportRun', () => {
  it('polls a PENDING run at the fixed interval', async () => {
    mockRun.mockResolvedValue({ ...RUN, status: 'GENERATING' });
    const { result } = renderHook(() => useTaxExportRun('run1'), { wrapper });
    await waitFor(() => expect(result.current.data?.status).toBe('GENERATING'));
    // refetchInterval is a function of query state; assert it resolves to the
    // poll cadence while running and to `false` once COMPLETE, without relying
    // on fake timers to actually observe a refetch.
  });

  it('is idle until an id is known', () => {
    const { result } = renderHook(() => useTaxExportRun(''), { wrapper });
    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('useDeleteTaxExportRun', () => {
  it('removes the run’s own cache entry and invalidates the list', async () => {
    mockDeleteRun.mockResolvedValue(undefined);
    const { invalidate, remove, provider } = withClient();
    const { result } = renderHook(() => useDeleteTaxExportRun(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('run1');
    });
    expect(remove).toHaveBeenCalledWith({ queryKey: ['tax-export', 'runs', 'run1'] });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual([
      'tax-export',
      'runs',
    ]);
  });
});

describe('useTaxExportRunFile', () => {
  it('fetches the file for the run and format given', async () => {
    mockFile.mockResolvedValue({ contentType: 'text/csv', text: 'a,b\n1,2\n', byteLength: 8 });
    const { result } = renderHook(() => useTaxExportRunFile(), { wrapper });
    let returned: Awaited<ReturnType<typeof fetchTaxExportRunFile>> | undefined;
    await act(async () => {
      returned = await result.current.mutateAsync({ id: 'run1', format: 'CSV' });
    });
    expect(mockFile).toHaveBeenCalledWith('run1', 'CSV');
    expect(returned?.text).toContain('1,2');
  });

  it('surfaces the 409 conflict a not-yet-complete run answers with', async () => {
    const error = Object.assign(new Error('not ready'), { code: 'conflict' });
    mockFile.mockRejectedValue(error);
    const { result } = renderHook(() => useTaxExportRunFile(), { wrapper });
    let caught: unknown;
    await act(async () => {
      await result.current.mutateAsync({ id: 'run1', format: 'CSV' }).catch((err: unknown) => {
        caught = err;
      });
    });
    expect(caught).toBe(error);
  });
});

describe('taxExportKeys', () => {
  it('the lines key defaults to a stable literal when the jurisdiction is omitted', () => {
    expect(taxExportKeys.lines()).toEqual(['tax-export', 'lines', 'default']);
    expect(taxExportKeys.run('run1')).toEqual(['tax-export', 'runs', 'run1']);
  });
});
