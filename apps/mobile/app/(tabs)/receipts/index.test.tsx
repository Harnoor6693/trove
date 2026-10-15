import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/queryClient';
import ReceiptsScreen from './index';
import { fetchReceipts, fetchReceiptThumbnail, uploadReceipt } from '@api/receipts';
import type { ReceiptSummaryResponse } from '@api/types';

jest.mock('@api/receipts', () => ({
  fetchReceipts: jest.fn(),
  fetchReceiptThumbnail: jest.fn(),
  fetchReceiptImage: jest.fn(),
  uploadReceipt: jest.fn(),
  RECEIPT_MAX_UPLOAD_BYTES: 10 * 1024 * 1024,
}));

const mockList = fetchReceipts as jest.MockedFunction<typeof fetchReceipts>;
const mockThumb = fetchReceiptThumbnail as jest.MockedFunction<typeof fetchReceiptThumbnail>;

const ROW: ReceiptSummaryResponse = {
  id: 'r1',
  status: 'COMPLETE',
  source: 'SCAN',
  extractedMerchant: 'Blue Bottle Coffee',
  extractedAmount: '12.3400',
  extractedCurrency: 'CAD',
  extractedDate: '2026-06-05',
  confidence: '0.940',
  reviewRequired: true,
  createdAt: '2026-09-05T14:00:00Z',
};

let clients: ReturnType<typeof createTestQueryClient>[] = [];

function createWrapper() {
  const qc = createTestQueryClient();
  clients.push(qc);
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  };
}

beforeEach(() => {
  jest.clearAllMocks();
  mockThumb.mockResolvedValue('data:image/jpeg;base64,/9j/4A==');
  (uploadReceipt as jest.Mock).mockResolvedValue({
    id: 'r9',
    status: 'UPLOADED',
    createdAt: '2026-09-05T14:00:00Z',
  });
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('ReceiptsScreen', () => {
  it('renders one PAGE of receipts and says how many the household has', async () => {
    mockList.mockResolvedValue({ data: [ROW], meta: { total: 137, page: 0, limit: 50 } });
    const { getByTestId, getByText } = render(<ReceiptsScreen />, { wrapper: createWrapper() });

    await waitFor(() => expect(getByTestId('receipt-row-r1')).toBeTruthy());
    expect(getByText('Blue Bottle Coffee')).toBeTruthy();
    // The totals come from `meta`, which the old bare-array shape threw away.
    expect(getByTestId('receipts-count')).toBeTruthy();
  });

  it('fetches page two when the list is scrolled to the end', async () => {
    const second: ReceiptSummaryResponse = { ...ROW, id: 'r2', extractedMerchant: 'Metro' };
    mockList
      .mockResolvedValueOnce({ data: [ROW], meta: { total: 2, page: 0, limit: 1 } })
      .mockResolvedValueOnce({ data: [second], meta: { total: 2, page: 1, limit: 1 } });

    const { getByTestId, getByText, UNSAFE_getByType } = render(<ReceiptsScreen />, {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(getByTestId('receipt-row-r1')).toBeTruthy());

    const { FlatList } = jest.requireActual<typeof import('react-native')>('react-native');
    fireEvent(UNSAFE_getByType(FlatList), 'endReached');

    await waitFor(() => expect(mockList).toHaveBeenNthCalledWith(2, { page: 1 }));
    await waitFor(() => expect(getByText('Metro')).toBeTruthy());
  });

  it('says a receipt needs review — reviewRequired is a field, not a status', async () => {
    mockList.mockResolvedValue({ data: [ROW], meta: { total: 1, page: 0, limit: 50 } });
    const { getByTestId } = render(<ReceiptsScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('receipt-review-r1')).toBeTruthy());
    // The status pill still says COMPLETE, which is the ordinary outcome.
    expect(getByTestId('receipt-row-r1').props.accessibilityLabel).toContain('Complete');
  });

  it('shows the empty state rather than a blank list', async () => {
    mockList.mockResolvedValue({ data: [], meta: { total: 0, page: 0, limit: 50 } });
    const { getByTestId } = render(<ReceiptsScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('receipts-empty')).toBeTruthy());
  });

  it('shows a failure with a retry rather than an empty list', async () => {
    mockList.mockRejectedValue(new Error('nope'));
    const { getByTestId } = render(<ReceiptsScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('receipts-error')).toBeTruthy());
    fireEvent.press(getByTestId('receipts-retry'));
    await waitFor(() => expect(mockList.mock.calls.length).toBeGreaterThan(1));
  });

  it('opens the capture modal inside the SAME QueryClient — never a second one', async () => {
    mockList.mockResolvedValue({ data: [], meta: { total: 0, page: 0, limit: 50 } });
    const qc = createTestQueryClient();
    clients.push(qc);
    const invalidate = jest.spyOn(qc, 'invalidateQueries');

    const { getByTestId } = render(<ReceiptsScreen />, {
      wrapper: ({ children }: { children: React.ReactNode }) => (
        <QueryClientProvider client={qc}>{children}</QueryClientProvider>
      ),
    });
    fireEvent.press(getByTestId('receipts-scan-btn'));
    await waitFor(() => expect(getByTestId('camera-btn')).toBeTruthy());

    fireEvent.press(getByTestId('camera-btn'));
    // The invalidation lands on the client this screen reads, which is the
    // whole point: a second, modal-scoped client is the shipped defect the
    // mobile inventory records.
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['receipts'] }));
  });
});
