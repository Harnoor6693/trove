import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { useLocalSearchParams } from 'expo-router';
import { AxiosError, AxiosHeaders } from 'axios';
import { createTestQueryClient } from '@/test/queryClient';
import ReceiptDetailScreen from './[id]';
import {
  deleteReceipt,
  fetchReceipt,
  fetchReceiptImage,
  fetchReceiptStatus,
  patchReceipt,
  reviewReceipt,
} from '@api/receipts';
import type { ReceiptResponse } from '@api/types';

jest.mock('@api/receipts', () => ({
  fetchReceipt: jest.fn(),
  fetchReceiptStatus: jest.fn(),
  patchReceipt: jest.fn(),
  reviewReceipt: jest.fn(),
  deleteReceipt: jest.fn(),
  fetchReceiptThumbnail: jest.fn(),
  fetchReceiptImage: jest.fn(),
}));

const mockOne = fetchReceipt as jest.MockedFunction<typeof fetchReceipt>;
const mockStatus = fetchReceiptStatus as jest.MockedFunction<typeof fetchReceiptStatus>;
const mockPatch = patchReceipt as jest.MockedFunction<typeof patchReceipt>;
const mockReview = reviewReceipt as jest.MockedFunction<typeof reviewReceipt>;
const mockDelete = deleteReceipt as jest.MockedFunction<typeof deleteReceipt>;
const mockImage = fetchReceiptImage as jest.MockedFunction<typeof fetchReceiptImage>;

const RECEIPT: ReceiptResponse = {
  id: 'r1',
  status: 'COMPLETE',
  source: 'SCAN',
  uploadSizeBytes: 148320,
  extractedMerchant: 'Blue Bottle Coffee',
  extractedAmount: '12.3400',
  extractedCurrency: 'CAD',
  extractedDate: '2026-06-05',
  gstAmount: '0.5900',
  pstAmount: '0.8600',
  hstAmount: '0.0000',
  confidence: '0.940',
  reviewRequired: true,
  matchedTransactionId: null,
  thumbnailUrl: '/api/v1/receipts/r1/thumbnail',
  lineItems: [
    {
      id: 'l1',
      description: 'Oat milk latte',
      quantity: '2',
      unitPrice: '5.5000',
      totalPrice: '11.0000',
      taxable: true,
    },
  ],
  errorMessage: null,
  processingStartedAt: null,
  processingCompletedAt: null,
  createdAt: '2026-09-05T14:00:00Z',
  updatedAt: '2026-09-05T14:00:09Z',
};

function refusal(status: number, slug: string, detail: string) {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, {
    status,
    statusText: '',
    headers: {},
    config,
    data: {
      success: false,
      error: {
        type: `https://trove.finance/errors/${slug}`,
        title: slug,
        status,
        detail,
        instance: '/api/v1/receipts/r1',
      },
    },
  } as never);
}

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
  (useLocalSearchParams as jest.Mock).mockReturnValue({ id: 'r1' });
  mockOne.mockResolvedValue(RECEIPT);
  mockImage.mockResolvedValue('data:image/jpeg;base64,/9j/4A==');
  mockReview.mockResolvedValue({ ...RECEIPT, reviewRequired: false });
  mockPatch.mockResolvedValue(RECEIPT);
  mockDelete.mockResolvedValue(undefined);
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('ReceiptDetailScreen', () => {
  it('shows the receipt, its image and its line items', async () => {
    const { getByTestId, getByText } = await render(<ReceiptDetailScreen />, {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(getByText('Blue Bottle Coffee')).toBeTruthy());
    expect(getByTestId('receipt-line-items')).toBeTruthy();
    expect(getByText('Oat milk latte')).toBeTruthy();
    // The image is fetched, not linked: the route needs a header `Image` cannot send.
    await waitFor(() => expect(mockImage).toHaveBeenCalledWith('r1'));
  });

  it('DISPLAYS the tax split and computes none of it', async () => {
    const { getByTestId, getByText } = await render(<ReceiptDetailScreen />, {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(getByTestId('receipt-tax-split')).toBeTruthy());
    expect(getByText('GST')).toBeTruthy();
    expect(getByText('PST')).toBeTruthy();
    expect(getByText('HST')).toBeTruthy();
  });

  it('shows no tax card for a member whose country sends none', async () => {
    mockOne.mockResolvedValue({
      ...RECEIPT,
      gstAmount: null,
      pstAmount: null,
      hstAmount: null,
    });
    const { queryByTestId, getByText } = await render(<ReceiptDetailScreen />, {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(getByText('Blue Bottle Coffee')).toBeTruthy());
    expect(queryByTestId('receipt-tax-split')).toBeNull();
  });

  it('offers the REVIEW action, which is a different route from a field edit', async () => {
    const { getByTestId } = await render(<ReceiptDetailScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('receipt-review-required')).toBeTruthy());

    await fireEvent.press(getByTestId('receipt-review-accept'));
    await waitFor(() => expect(mockReview).toHaveBeenCalledWith('r1', { accepted: true }));
    expect(mockPatch).not.toHaveBeenCalled();
  });

  it('records a rejection as a decision, not a correction', async () => {
    const { getByTestId } = await render(<ReceiptDetailScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('receipt-review-reject')).toBeTruthy());
    await fireEvent.press(getByTestId('receipt-review-reject'));
    await waitFor(() => expect(mockReview).toHaveBeenCalledWith('r1', { accepted: false }));
  });

  it('sends only the fields the member actually filled in', async () => {
    const { getByTestId } = await render(<ReceiptDetailScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('receipt-edit-start')).toBeTruthy());

    await fireEvent.press(getByTestId('receipt-edit-start'));
    await fireEvent.changeText(getByTestId('receipt-edit-merchant'), 'Metro');
    await fireEvent.changeText(getByTestId('receipt-edit-amount'), '');
    await fireEvent.press(getByTestId('receipt-edit-save'));

    await waitFor(() => expect(mockPatch).toHaveBeenCalled());
    // An absent field is left alone by the server; an empty string would be a
    // 400, so it is never sent.
    expect(mockPatch).toHaveBeenCalledWith('r1', { extractedMerchant: 'Metro' });
  });

  it('explains a 409 as "still being read", not as a generic failure', async () => {
    mockPatch.mockRejectedValue(refusal(409, 'conflict', 'This receipt is still processing.'));
    const { getByTestId } = await render(<ReceiptDetailScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('receipt-edit-start')).toBeTruthy());

    await fireEvent.press(getByTestId('receipt-edit-start'));
    await fireEvent.changeText(getByTestId('receipt-edit-merchant'), 'Metro');
    await fireEvent.press(getByTestId('receipt-edit-save'));

    const shown = await waitFor(() => getByTestId('receipt-detail-action-error'));
    expect(String(shown.props.children)).toContain('still being read');
  });

  it('says how long to wait on a 429 rather than showing a generic toast', async () => {
    const config = { headers: new AxiosHeaders() };
    mockReview.mockRejectedValue(
      new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, {
        status: 429,
        statusText: '',
        headers: { 'retry-after': '30' },
        config,
        data: {
          success: false,
          error: {
            type: 'https://trove.finance/errors/rate-limited',
            title: 'Too Many Requests',
            status: 429,
            detail: 'Slow down.',
            instance: '/api/v1/receipts/r1/review',
          },
        },
      } as never)
    );
    const { getByTestId } = await render(<ReceiptDetailScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('receipt-review-accept')).toBeTruthy());
    await fireEvent.press(getByTestId('receipt-review-accept'));

    const shown = await waitFor(() => getByTestId('receipt-detail-action-error'));
    expect(String(shown.props.children)).toContain('30 seconds');
  });

  it('confirms a delete, and says what goes with it', async () => {
    const { getByTestId } = await render(<ReceiptDetailScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('receipt-delete-start')).toBeTruthy());

    await fireEvent.press(getByTestId('receipt-delete-start'));
    // Nothing has been deleted by opening the confirmation.
    expect(mockDelete).not.toHaveBeenCalled();

    await fireEvent.press(getByTestId('receipt-delete-confirm'));
    await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('r1'));
  });

  it('lets the member back out of a delete', async () => {
    const { getByTestId, queryByTestId } = await render(<ReceiptDetailScreen />, {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(getByTestId('receipt-delete-start')).toBeTruthy());
    await fireEvent.press(getByTestId('receipt-delete-start'));
    await fireEvent.press(getByTestId('receipt-delete-cancel'));
    expect(queryByTestId('receipt-delete-confirm')).toBeNull();
    expect(mockDelete).not.toHaveBeenCalled();
  });

  it('polls while the pipeline is running and says which stage it is at', async () => {
    mockOne.mockResolvedValue({ ...RECEIPT, status: 'EXTRACTING', reviewRequired: false });
    mockStatus.mockResolvedValue({
      id: 'r1',
      status: 'EXTRACTING',
      reviewRequired: false,
      confidence: null,
    });
    const { getByTestId } = await render(<ReceiptDetailScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('receipt-detail-processing')).toBeTruthy());
    expect(mockStatus).toHaveBeenCalledWith('r1');
  });

  it('does not poll a receipt that has already finished', async () => {
    const { getByText } = await render(<ReceiptDetailScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByText('Blue Bottle Coffee')).toBeTruthy());
    expect(mockStatus).not.toHaveBeenCalled();
  });

  it('shows why a FAILED receipt failed, in the sentence the server sent', async () => {
    mockOne.mockResolvedValue({
      ...RECEIPT,
      status: 'FAILED',
      reviewRequired: false,
      errorMessage: 'Unsupported file type',
    });
    const { getByTestId } = await render(<ReceiptDetailScreen />, { wrapper: createWrapper() });
    await waitFor(() =>
      expect(getByTestId('receipt-detail-failure-reason').props.children).toBe(
        'Unsupported file type'
      )
    );
  });

  it('shows a load failure with a retry', async () => {
    mockOne.mockRejectedValue(new Error('nope'));
    const { getByTestId } = await render(<ReceiptDetailScreen />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('receipt-detail-error')).toBeTruthy());
    await fireEvent.press(getByTestId('receipt-detail-retry'));
    await waitFor(() => expect(mockOne.mock.calls.length).toBeGreaterThan(1));
  });
});
