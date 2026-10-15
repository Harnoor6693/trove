import React from 'react';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/queryClient';
import {
  isTerminalReceiptStatus,
  nextReceiptPage,
  receiptKeys,
  useDeleteReceipt,
  usePatchReceipt,
  useReceipt,
  useReceiptImage,
  useReceiptStatus,
  useReceipts,
  useReceiptThumbnail,
  useReviewReceipt,
} from './useReceipts';
import {
  deleteReceipt,
  fetchReceipt,
  fetchReceiptImage,
  fetchReceiptStatus,
  fetchReceipts,
  fetchReceiptThumbnail,
  patchReceipt,
  reviewReceipt,
} from '@api/receipts';
import type { Page, ReceiptResponse, ReceiptSummaryResponse } from '@api/types';

jest.mock('@api/receipts', () => ({
  fetchReceipts: jest.fn(),
  fetchReceipt: jest.fn(),
  fetchReceiptStatus: jest.fn(),
  patchReceipt: jest.fn(),
  reviewReceipt: jest.fn(),
  deleteReceipt: jest.fn(),
  fetchReceiptThumbnail: jest.fn(),
  fetchReceiptImage: jest.fn(),
}));

const mockList = fetchReceipts as jest.MockedFunction<typeof fetchReceipts>;
const mockOne = fetchReceipt as jest.MockedFunction<typeof fetchReceipt>;
const mockStatus = fetchReceiptStatus as jest.MockedFunction<typeof fetchReceiptStatus>;
const mockPatch = patchReceipt as jest.MockedFunction<typeof patchReceipt>;
const mockReview = reviewReceipt as jest.MockedFunction<typeof reviewReceipt>;
const mockDelete = deleteReceipt as jest.MockedFunction<typeof deleteReceipt>;
const mockThumb = fetchReceiptThumbnail as jest.MockedFunction<typeof fetchReceiptThumbnail>;
const mockImage = fetchReceiptImage as jest.MockedFunction<typeof fetchReceiptImage>;

/** Every mocked body is typed from the generated schema, never hand-shaped. */
const SUMMARY: ReceiptSummaryResponse = {
  id: 'r1',
  status: 'COMPLETE',
  source: 'SCAN',
  extractedMerchant: 'Loblaws',
  extractedAmount: '42.1000',
  extractedCurrency: 'CAD',
  extractedDate: '2026-09-01',
  confidence: '0.940',
  reviewRequired: false,
  createdAt: '2026-09-01T12:00:00Z',
};

const FULL: ReceiptResponse = {
  id: 'r1',
  status: 'COMPLETE',
  source: 'SCAN',
  uploadSizeBytes: 1024,
  extractedMerchant: 'Loblaws',
  extractedAmount: '42.1000',
  extractedCurrency: 'CAD',
  extractedDate: '2026-09-01',
  gstAmount: null,
  pstAmount: null,
  hstAmount: null,
  confidence: '0.940',
  reviewRequired: true,
  matchedTransactionId: null,
  thumbnailUrl: '/api/v1/receipts/r1/thumbnail',
  lineItems: [],
  errorMessage: null,
  processingStartedAt: null,
  processingCompletedAt: null,
  createdAt: '2026-09-01T12:00:00Z',
  updatedAt: '2026-09-01T12:00:00Z',
};

const page = (rows: ReceiptSummaryResponse[], meta: Page<ReceiptSummaryResponse>['meta']) => ({
  data: rows,
  meta,
});

// Every client `wrapper` hands out, so `afterEach` can destroy them all — a
// query's `gcTime` can ratchet back up from the `0` the factory sets, and
// `clear()` cancels the pending GC timeout whatever it ratcheted to.
let clients: ReturnType<typeof createTestQueryClient>[] = [];

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = createTestQueryClient();
  clients.push(qc);
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

function clientWrapper(qc: ReturnType<typeof createTestQueryClient>) {
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  };
}

beforeEach(() => {
  jest.clearAllMocks();
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('nextReceiptPage', () => {
  it('asks for the next page while the totals say there is one', () => {
    expect(nextReceiptPage(page([], { total: 137, page: 0, limit: 50 }))).toBe(1);
    expect(nextReceiptPage(page([], { total: 137, page: 1, limit: 50 }))).toBe(2);
  });

  it('stops at the real end of the list rather than asking for empty pages for ever', () => {
    expect(nextReceiptPage(page([], { total: 137, page: 2, limit: 50 }))).toBeUndefined();
    expect(nextReceiptPage(page([], { total: 0, page: 0, limit: 50 }))).toBeUndefined();
  });

  it('refuses to divide by a limit of zero', () => {
    expect(nextReceiptPage(page([], { total: 10, page: 0, limit: 0 }))).toBeUndefined();
  });
});

describe('isTerminalReceiptStatus', () => {
  it('stops on COMPLETE and FAILED and on nothing else', () => {
    expect(isTerminalReceiptStatus('COMPLETE')).toBe(true);
    expect(isTerminalReceiptStatus('FAILED')).toBe(true);
    for (const running of ['UPLOADED', 'VALIDATING', 'PARSING', 'EXTRACTING'] as const) {
      expect(isTerminalReceiptStatus(running)).toBe(false);
    }
  });
});

describe('useReceipts', () => {
  it('consumes GET /v1/receipts as a PAGE, with its totals', async () => {
    mockList.mockResolvedValue(page([SUMMARY], { total: 1, page: 0, limit: 50 }));
    const { result } = await renderHook(() => useReceipts(), { wrapper });
    await waitFor(() => expect(result.current.data).toBeDefined());
    expect(result.current.data?.pages[0].data).toEqual([SUMMARY]);
    expect(result.current.data?.pages[0].meta.total).toBe(1);
    expect(mockList).toHaveBeenCalledWith({ page: 0 });
  });

  it('makes page two reachable — this replaces the old bare-array expectation', async () => {
    const second: ReceiptSummaryResponse = { ...SUMMARY, id: 'r2' };
    mockList
      .mockResolvedValueOnce(page([SUMMARY], { total: 2, page: 0, limit: 1 }))
      .mockResolvedValueOnce(page([second], { total: 2, page: 1, limit: 1 }));

    const { result } = await renderHook(() => useReceipts(), { wrapper });
    await waitFor(() => expect(result.current.hasNextPage).toBe(true));
    await act(async () => {
      await result.current.fetchNextPage();
    });
    await waitFor(() => expect(result.current.data?.pages).toHaveLength(2));

    expect(mockList).toHaveBeenNthCalledWith(2, { page: 1 });
    expect(result.current.data?.pages.flatMap((p) => p.data).map((r) => r.id)).toEqual([
      'r1',
      'r2',
    ]);
    expect(result.current.hasNextPage).toBe(false);
  });

  it('surfaces a failure rather than an empty list', async () => {
    mockList.mockRejectedValue(new Error('boom'));
    const { result } = await renderHook(() => useReceipts(), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
  });
});

describe('useReceipt', () => {
  it('fetches one receipt by id', async () => {
    mockOne.mockResolvedValue(FULL);
    const { result } = await renderHook(() => useReceipt('r1'), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual(FULL));
    expect(mockOne).toHaveBeenCalledWith('r1');
  });

  it('stays idle without an id', async () => {
    const { result } = await renderHook(() => useReceipt(''), { wrapper });
    expect(result.current.fetchStatus).toBe('idle');
    expect(mockOne).not.toHaveBeenCalled();
  });
});

describe('useReceiptStatus', () => {
  it('polls the cheap status route while the pipeline runs', async () => {
    mockStatus.mockResolvedValue({
      id: 'r1',
      status: 'EXTRACTING',
      reviewRequired: false,
      confidence: null,
    });
    const { result } = await renderHook(() => useReceiptStatus('r1'), { wrapper });
    await waitFor(() => expect(result.current.data?.status).toBe('EXTRACTING'));
    expect(mockStatus).toHaveBeenCalledWith('r1');
  });

  it('is idle when the caller says the receipt is already finished', async () => {
    const { result } = await renderHook(() => useReceiptStatus('r1', false), { wrapper });
    expect(result.current.fetchStatus).toBe('idle');
    expect(mockStatus).not.toHaveBeenCalled();
  });
});

describe('usePatchReceipt', () => {
  it('writes the response into the detail cache rather than refetching it', async () => {
    const updated = { ...FULL, extractedMerchant: 'Metro' };
    mockPatch.mockResolvedValue(updated);
    // `gcTime` is raised for this one client: the factory's default of 0
    // collects an observer-less entry the instant it is written, so a cache
    // assertion against the default would pass whether or not the mutation
    // wrote anything. `queryOverrides` merges into the query branch only, so
    // mutations keep the `gcTime: 0` that stops the suite hanging.
    const qc = createTestQueryClient({ gcTime: 5_000 });
    clients.push(qc);
    const { result } = await renderHook(() => usePatchReceipt('r1'), {
      wrapper: clientWrapper(qc),
    });

    await act(async () => {
      await result.current.mutateAsync({ extractedMerchant: 'Metro' });
    });

    expect(mockPatch).toHaveBeenCalledWith('r1', { extractedMerchant: 'Metro' });
    expect(qc.getQueryData(receiptKeys.detail('r1'))).toEqual(updated);
  });

  it('surfaces the 409 a still-running pipeline answers', async () => {
    mockPatch.mockRejectedValue(new Error('This receipt is still being processed.'));
    const { result } = await renderHook(() => usePatchReceipt('r1'), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ extractedMerchant: 'X' }).catch(() => undefined);
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe('useReviewReceipt', () => {
  it('calls the review route, which is not the field patch', async () => {
    mockReview.mockResolvedValue({ ...FULL, reviewRequired: false });
    const { result } = await renderHook(() => useReviewReceipt('r1'), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ accepted: true });
    });
    expect(mockReview).toHaveBeenCalledWith('r1', { accepted: true });
    expect(mockPatch).not.toHaveBeenCalled();
  });
});

describe('useDeleteReceipt', () => {
  it('drops the row AND the image bytes it had fetched for it', async () => {
    mockDelete.mockResolvedValue(undefined);
    // Raised for the same reason: with `gcTime: 0` the three seeded entries
    // would be gone before the mutation ran, and the assertion would prove
    // nothing about `removeQueries`.
    const qc = createTestQueryClient({ gcTime: 5_000 });
    clients.push(qc);
    qc.setQueryData(receiptKeys.detail('r1'), FULL);
    qc.setQueryData(receiptKeys.thumbnail('r1'), 'data:image/jpeg;base64,AAAA');
    qc.setQueryData(receiptKeys.image('r1'), 'data:image/jpeg;base64,BBBB');

    const { result } = await renderHook(() => useDeleteReceipt(), { wrapper: clientWrapper(qc) });
    await act(async () => {
      await result.current.mutateAsync('r1');
    });

    expect(qc.getQueryData(receiptKeys.detail('r1'))).toBeUndefined();
    expect(qc.getQueryData(receiptKeys.thumbnail('r1'))).toBeUndefined();
    expect(qc.getQueryData(receiptKeys.image('r1'))).toBeUndefined();
  });
});

describe('the authenticated image hooks', () => {
  it('fetches the thumbnail as a data URI', async () => {
    mockThumb.mockResolvedValue('data:image/jpeg;base64,AAAA');
    const { result } = await renderHook(() => useReceiptThumbnail('r1'), { wrapper });
    await waitFor(() => expect(result.current.data).toBe('data:image/jpeg;base64,AAAA'));
    expect(mockThumb).toHaveBeenCalledWith('r1');
  });

  it('does not fetch the full image until a screen asks for it', async () => {
    const { result } = await renderHook(() => useReceiptImage('r1', false), { wrapper });
    expect(result.current.fetchStatus).toBe('idle');
    expect(mockImage).not.toHaveBeenCalled();
  });

  it('fetches the full image when enabled', async () => {
    mockImage.mockResolvedValue('data:image/jpeg;base64,BBBB');
    const { result } = await renderHook(() => useReceiptImage('r1'), { wrapper });
    await waitFor(() => expect(result.current.data).toBe('data:image/jpeg;base64,BBBB'));
  });

  it('passes the id and nothing else — no token can ride along', async () => {
    mockThumb.mockResolvedValue('data:image/jpeg;base64,AAAA');
    await renderHook(() => useReceiptThumbnail('r1'), { wrapper });
    await waitFor(() => expect(mockThumb).toHaveBeenCalled());
    expect(mockThumb.mock.calls[0]).toEqual(['r1']);
  });
});
