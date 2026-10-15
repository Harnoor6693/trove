import React from 'react';
import { fireEvent, render, waitFor, within } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import * as ImagePicker from 'expo-image-picker';
import * as Linking from 'expo-linking';
import { AxiosError, AxiosHeaders } from 'axios';
import { createTestQueryClient } from '@/test/queryClient';
import { ReceiptCapture } from './ReceiptCapture';
import { uploadReceipt } from '@api/receipts';

jest.mock('@api/receipts', () => ({
  uploadReceipt: jest.fn(),
  RECEIPT_MAX_UPLOAD_BYTES: 10 * 1024 * 1024,
}));

const mockUpload = uploadReceipt as jest.MockedFunction<typeof uploadReceipt>;

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
        instance: '/api/v1/receipts/upload',
      },
    },
  } as never);
}

// Every client this file hands out, so `afterEach` can destroy them all.
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
  (ImagePicker.requestCameraPermissionsAsync as jest.Mock).mockResolvedValue({
    status: 'granted',
    granted: true,
  });
  (ImagePicker.requestMediaLibraryPermissionsAsync as jest.Mock).mockResolvedValue({
    status: 'granted',
    granted: true,
  });
  (ImagePicker.launchCameraAsync as jest.Mock).mockResolvedValue({
    canceled: false,
    assets: [{ uri: 'file://cam.jpg', mimeType: 'image/jpeg' }],
  });
  (ImagePicker.launchImageLibraryAsync as jest.Mock).mockResolvedValue({
    canceled: false,
    assets: [{ uri: 'file://lib.jpg', mimeType: 'image/jpeg' }],
  });
  mockUpload.mockResolvedValue({
    id: 'r1',
    status: 'UPLOADED',
    createdAt: '2026-09-03T10:00:00Z',
  });
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('ReceiptCapture', () => {
  it('renders camera and library options with labels a screen reader can use', async () => {
    const { getByTestId } = await render(<ReceiptCapture />, { wrapper: createWrapper() });
    expect(getByTestId('camera-btn').props.accessibilityLabel).toBe('Take a photo of a receipt');
    expect(getByTestId('library-btn').props.accessibilityLabel).toBe(
      'Choose a receipt from your photo library'
    );
  });

  it('states the size ceiling BEFORE the member shoots', async () => {
    const { getByTestId } = await render(<ReceiptCapture />, { wrapper: createWrapper() });
    expect(getByTestId('receipt-capture-limit').props.children).toEqual(
      expect.arrayContaining([expect.stringContaining('JPEG or PNG')])
    );
  });

  it('camera option captures and uploads as a SCAN', async () => {
    const { getByTestId } = await render(<ReceiptCapture />, { wrapper: createWrapper() });
    await fireEvent.press(getByTestId('camera-btn'));
    await waitFor(() =>
      expect(mockUpload).toHaveBeenCalledWith('file://cam.jpg', 'SCAN', 'image/jpeg')
    );
  });

  it('library option picks and uploads as an UPLOAD, not a SCAN', async () => {
    // The detail screen renders this value as fact ("Uploaded file" vs "Phone
    // camera") and no later request can correct it, so the button the member
    // pressed is what decides it.
    const { getByTestId } = await render(<ReceiptCapture />, { wrapper: createWrapper() });
    await fireEvent.press(getByTestId('library-btn'));
    await waitFor(() =>
      expect(mockUpload).toHaveBeenCalledWith('file://lib.jpg', 'UPLOAD', 'image/jpeg')
    );
  });

  it('sends a library PNG declared as a PNG', async () => {
    (ImagePicker.launchImageLibraryAsync as jest.Mock).mockResolvedValue({
      canceled: false,
      assets: [{ uri: 'file://receipt.png', mimeType: 'image/png' }],
    });
    const { getByTestId } = await render(<ReceiptCapture />, { wrapper: createWrapper() });
    await fireEvent.press(getByTestId('library-btn'));
    await waitFor(() =>
      expect(mockUpload).toHaveBeenCalledWith('file://receipt.png', 'UPLOAD', 'image/png')
    );
  });

  it('reports the accepted receipt id to the caller', async () => {
    const onCaptured = jest.fn();
    const { getByTestId } = await render(<ReceiptCapture onCaptured={onCaptured} />, {
      wrapper: createWrapper(),
    });
    await fireEvent.press(getByTestId('camera-btn'));
    await waitFor(() => expect(onCaptured).toHaveBeenCalledWith('r1'));
  });

  it('does not upload when the picker is cancelled', async () => {
    (ImagePicker.launchCameraAsync as jest.Mock).mockResolvedValue({ canceled: true });
    const { getByTestId } = await render(<ReceiptCapture />, { wrapper: createWrapper() });
    await fireEvent.press(getByTestId('camera-btn'));
    await waitFor(() => expect(ImagePicker.launchCameraAsync).toHaveBeenCalled());
    expect(mockUpload).not.toHaveBeenCalled();
  });

  it('explains a DENIED camera permission and offers the OS settings route', async () => {
    (ImagePicker.requestCameraPermissionsAsync as jest.Mock).mockResolvedValue({
      status: 'denied',
      granted: false,
    });
    const { getByTestId } = await render(<ReceiptCapture />, { wrapper: createWrapper() });
    await fireEvent.press(getByTestId('camera-btn'));

    const notice = await waitFor(() => getByTestId('receipt-capture-permission-denied'));
    expect(notice).toBeTruthy();
    // The explanation is a real sentence, announced as an alert.
    const message = within(getByTestId('receipt-capture-permission-denied')).getByRole('alert');
    expect(message.props.children).toContain('camera');

    await fireEvent.press(getByTestId('receipt-capture-open-settings'));
    await waitFor(() => expect(Linking.openSettings).toHaveBeenCalled());
  });

  it('explains a denied photo-library permission in its own words', async () => {
    (ImagePicker.requestMediaLibraryPermissionsAsync as jest.Mock).mockResolvedValue({
      status: 'denied',
      granted: false,
    });
    const { getByTestId } = await render(<ReceiptCapture />, { wrapper: createWrapper() });
    await fireEvent.press(getByTestId('library-btn'));
    const message = await waitFor(() =>
      within(getByTestId('receipt-capture-permission-denied')).getByRole('alert')
    );
    expect(message.props.children).toContain('photo library');
  });

  it('shows the 413 refusal AND the limit, rather than a silent failure', async () => {
    mockUpload.mockRejectedValue(
      refusal(413, 'upload-too-large', 'That image is larger than this server accepts.')
    );
    const { getByTestId } = await render(<ReceiptCapture />, { wrapper: createWrapper() });
    await fireEvent.press(getByTestId('camera-btn'));

    const shown = await waitFor(() =>
      within(getByTestId('receipt-capture-error')).getByRole('alert')
    );
    // The server's own sentence, and the app's own guidance about the ceiling,
    // are both there: one says the upload was refused, the other says by how
    // much a member has to come down.
    const text = String(shown.props.children);
    expect(text).toContain('larger than this server accepts');
    expect(text).toContain('MB');
  });

  it('shows any other refusal with the server sentence', async () => {
    mockUpload.mockRejectedValue(
      refusal(400, 'validation-error', 'Those bytes are not the format they claim to be.')
    );
    const { getByTestId } = await render(<ReceiptCapture />, { wrapper: createWrapper() });
    await fireEvent.press(getByTestId('library-btn'));
    const shown = await waitFor(() =>
      within(getByTestId('receipt-capture-error')).getByRole('alert')
    );
    expect(shown.props.children).toBe('Those bytes are not the format they claim to be.');
  });
});
