import React from 'react';
import { render, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/queryClient';
import { ReceiptImage } from './ReceiptImage';
import { fetchReceiptImage, fetchReceiptThumbnail } from '@api/receipts';

jest.mock('@api/receipts', () => ({
  fetchReceiptThumbnail: jest.fn(),
  fetchReceiptImage: jest.fn(),
}));

const mockThumb = fetchReceiptThumbnail as jest.MockedFunction<typeof fetchReceiptThumbnail>;
const mockFull = fetchReceiptImage as jest.MockedFunction<typeof fetchReceiptImage>;

const DATA_URI = 'data:image/jpeg;base64,/9j/4A==';

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
  mockThumb.mockResolvedValue(DATA_URI);
  mockFull.mockResolvedValue(DATA_URI);
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('ReceiptImage', () => {
  it('renders the bytes it fetched, NOT the route — the route needs a header Image cannot send', async () => {
    const { getByTestId } = render(<ReceiptImage receiptId="r1" testID="thumb" />, {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(getByTestId('thumb')).toBeTruthy());

    const uri = getByTestId('thumb').props.source.uri;
    expect(uri).toBe(DATA_URI);
    // The one thing that must never be true: an API path handed to `Image`.
    expect(uri).not.toContain('/v1/receipts');
    expect(uri).not.toMatch(/token|Bearer|access/i);
  });

  it('fetches through the thumbnail route by default', async () => {
    render(<ReceiptImage receiptId="r1" />, { wrapper: createWrapper() });
    await waitFor(() => expect(mockThumb).toHaveBeenCalledWith('r1'));
    expect(mockFull).not.toHaveBeenCalled();
  });

  it('fetches the full stored image when asked for the full variant', async () => {
    render(<ReceiptImage receiptId="r1" variant="full" />, { wrapper: createWrapper() });
    await waitFor(() => expect(mockFull).toHaveBeenCalledWith('r1'));
    expect(mockThumb).not.toHaveBeenCalled();
  });

  it('names the receipt in its alternative text, so a list of them is navigable', async () => {
    const { getByTestId } = render(
      <ReceiptImage receiptId="r1" merchant="Blue Bottle Coffee" date="2026-06-05" testID="t" />,
      { wrapper: createWrapper() }
    );
    await waitFor(() => expect(getByTestId('t')).toBeTruthy());
    const label = getByTestId('t').props.accessibilityLabel as string;
    expect(label).toContain('Blue Bottle Coffee');
    expect(label).toContain('Receipt image');
    // The date is rendered through the user's own formatter, not `toLocaleDateString`.
    expect(label).toMatch(/2026/);
  });

  it('falls back to a generic label when nothing identifies the receipt yet', async () => {
    const { getByTestId } = render(<ReceiptImage receiptId="r1" merchant={null} testID="t" />, {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(getByTestId('t')).toBeTruthy());
    expect(getByTestId('t').props.accessibilityLabel).toBe('Receipt image');
  });

  it('announces the load rather than showing an empty box', () => {
    const { getByTestId, getByLabelText } = render(<ReceiptImage receiptId="r1" testID="t" />, {
      wrapper: createWrapper(),
    });
    expect(getByTestId('t-loading')).toBeTruthy();
    expect(getByLabelText('Loading the receipt image')).toBeTruthy();
  });

  it('says "No image" rather than retrying for ever when the route 404s', async () => {
    mockThumb.mockRejectedValue(new Error('not found'));
    const { getByTestId } = render(<ReceiptImage receiptId="r1" testID="t" />, {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(getByTestId('t-missing')).toBeTruthy());
  });

  it('fetches nothing at all when the caller says there is no image', () => {
    const { getByTestId } = render(<ReceiptImage receiptId="r1" enabled={false} testID="t" />, {
      wrapper: createWrapper(),
    });
    expect(getByTestId('t-missing')).toBeTruthy();
    expect(mockThumb).not.toHaveBeenCalled();
    expect(mockFull).not.toHaveBeenCalled();
  });
});
