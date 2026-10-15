import React from 'react';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import * as ImagePicker from 'expo-image-picker';
import * as Linking from 'expo-linking';
import { AxiosError, AxiosHeaders } from 'axios';
import { createTestQueryClient } from '@/test/queryClient';
import { useReceiptCamera } from './useReceiptCamera';
import { isLockSuppressed } from '@/security/lockSuppression';
import { RECEIPT_MAX_UPLOAD_BYTES, uploadReceipt } from '@api/receipts';

jest.mock('@api/receipts', () => ({
  uploadReceipt: jest.fn(),
  RECEIPT_MAX_UPLOAD_BYTES: 10 * 1024 * 1024,
}));

const mockUpload = uploadReceipt as jest.MockedFunction<typeof uploadReceipt>;

/** A refusal shaped exactly like the one axios throws, so the normaliser is exercised. */
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

let clients: ReturnType<typeof createTestQueryClient>[] = [];

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = createTestQueryClient();
  clients.push(qc);
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
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

describe('app lock (B5-12)', () => {
  it.each(['captureFromCamera', 'pickFromLibrary'] as const)(
    '%s runs inside the lock-suppression window',
    async (method) => {
      const seen: boolean[] = [];
      const launch = (
        method === 'captureFromCamera'
          ? ImagePicker.launchCameraAsync
          : ImagePicker.launchImageLibraryAsync
      ) as jest.Mock;
      launch.mockImplementation(async () => {
        seen.push(isLockSuppressed());
        return { canceled: true };
      });
      const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
      await act(async () => {
        await result.current[method]();
      });
      expect(seen).toEqual([true]);
      expect(isLockSuppressed()).toBe(false);
    }
  );
});

describe('permissions', () => {
  it('asks for the camera permission before opening the camera', async () => {
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await expect(result.current.captureFromCamera()).resolves.toEqual({
        uri: 'file://cam.jpg',
        source: 'SCAN',
        mimeType: 'image/jpeg',
      });
    });
    expect(ImagePicker.requestCameraPermissionsAsync).toHaveBeenCalled();
    expect(result.current.permissionDenied).toBeNull();
  });

  it('records a refused CAMERA permission instead of silently doing nothing', async () => {
    // This is the real defect: `launchCameraAsync` on a denied device resolves
    // `canceled: true`, which the old code read as "changed their mind".
    (ImagePicker.requestCameraPermissionsAsync as jest.Mock).mockResolvedValue({
      status: 'denied',
      granted: false,
    });
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await expect(result.current.captureFromCamera()).resolves.toBeNull();
    });
    await waitFor(() => expect(result.current.permissionDenied).toBe('camera'));
    expect(ImagePicker.launchCameraAsync).not.toHaveBeenCalled();
  });

  it('records a refused LIBRARY permission separately from the camera one', async () => {
    (ImagePicker.requestMediaLibraryPermissionsAsync as jest.Mock).mockResolvedValue({
      status: 'denied',
      granted: false,
    });
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await expect(result.current.pickFromLibrary()).resolves.toBeNull();
    });
    await waitFor(() => expect(result.current.permissionDenied).toBe('library'));
    expect(ImagePicker.launchImageLibraryAsync).not.toHaveBeenCalled();
  });

  it('offers the OS settings screen, the only route back from a denial', async () => {
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await result.current.openDeviceSettings();
    });
    expect(Linking.openSettings).toHaveBeenCalled();
  });

  it('clears the denial when a later attempt is granted', async () => {
    (ImagePicker.requestCameraPermissionsAsync as jest.Mock).mockResolvedValueOnce({
      status: 'denied',
      granted: false,
    });
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await result.current.captureFromCamera();
    });
    await waitFor(() => expect(result.current.permissionDenied).toBe('camera'));
    await act(async () => {
      await result.current.captureFromCamera();
    });
    await waitFor(() => expect(result.current.permissionDenied).toBeNull());
  });
});

describe('picking', () => {
  it('returns null when the member backs out of the picker', async () => {
    (ImagePicker.launchCameraAsync as jest.Mock).mockResolvedValue({ canceled: true });
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await expect(result.current.captureFromCamera()).resolves.toBeNull();
    });
    // A cancellation is not a denial, and must not be reported as one.
    expect(result.current.permissionDenied).toBeNull();
  });

  it('returns null when the picker hands back no asset', async () => {
    (ImagePicker.launchImageLibraryAsync as jest.Mock).mockResolvedValue({
      canceled: false,
      assets: [],
    });
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await expect(result.current.pickFromLibrary()).resolves.toBeNull();
    });
  });

  it('picks from the library as an UPLOAD, which is not a SCAN', async () => {
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await expect(result.current.pickFromLibrary()).resolves.toEqual({
        uri: 'file://lib.jpg',
        source: 'UPLOAD',
        mimeType: 'image/jpeg',
      });
    });
  });

  it('asks PhotoKit for the most compatible representation, so a HEIC library photo arrives as JPEG', async () => {
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await result.current.pickFromLibrary();
    });
    expect(ImagePicker.launchImageLibraryAsync).toHaveBeenCalledWith(
      expect.objectContaining({
        preferredAssetRepresentationMode:
          ImagePicker.UIImagePickerPreferredAssetRepresentationMode.Compatible,
      })
    );
  });

  it('carries the format the picker declared, rather than assuming JPEG', async () => {
    (ImagePicker.launchImageLibraryAsync as jest.Mock).mockResolvedValue({
      canceled: false,
      assets: [{ uri: 'file://shot.png', mimeType: 'image/png' }],
    });
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await expect(result.current.pickFromLibrary()).resolves.toEqual({
        uri: 'file://shot.png',
        source: 'UPLOAD',
        mimeType: 'image/png',
      });
    });
  });

  it('reports a null format when the picker declared none', async () => {
    (ImagePicker.launchImageLibraryAsync as jest.Mock).mockResolvedValue({
      canceled: false,
      assets: [{ uri: 'file://lib.jpg' }],
    });
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await expect(result.current.pickFromLibrary()).resolves.toEqual({
        uri: 'file://lib.jpg',
        source: 'UPLOAD',
        mimeType: null,
      });
    });
  });
});

describe('uploading', () => {
  it('uploads as a SCAN and invalidates the receipts cache the ONE client holds', async () => {
    const qc = createTestQueryClient();
    clients.push(qc);
    const invalidate = jest.spyOn(qc, 'invalidateQueries');
    const { result } = await renderHook(() => useReceiptCamera(), {
      wrapper: ({ children }: { children: React.ReactNode }) => (
        <QueryClientProvider client={qc}>{children}</QueryClientProvider>
      ),
    });

    await act(async () => {
      await result.current.uploadReceipt({
        uri: 'file://cam.jpg',
        source: 'SCAN',
        mimeType: 'image/jpeg',
      });
    });

    expect(mockUpload).toHaveBeenCalledWith('file://cam.jpg', 'SCAN', 'image/jpeg');
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['receipts'] });
  });

  it('uploads a LIBRARY pick as UPLOAD — the member cannot correct this field later', async () => {
    // `ReceiptSource` is stored and rendered as fact by the detail screen
    // ("Uploaded file" vs "Phone camera") and `ReceiptPatchRequest` carries no
    // field for it, so a library pick recorded as `SCAN` is wrong for ever.
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });

    let picked: Awaited<ReturnType<typeof result.current.pickFromLibrary>> = null;
    await act(async () => {
      picked = await result.current.pickFromLibrary();
    });
    await act(async () => {
      await result.current.uploadReceipt(picked!);
    });

    expect(mockUpload).toHaveBeenCalledWith('file://lib.jpg', 'UPLOAD', 'image/jpeg');
  });

  it('declares a library PNG as a PNG rather than mislabelling it JPEG', async () => {
    (ImagePicker.launchImageLibraryAsync as jest.Mock).mockResolvedValue({
      canceled: false,
      assets: [{ uri: 'file://receipt.png', mimeType: 'image/png' }],
    });
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });

    let picked: Awaited<ReturnType<typeof result.current.pickFromLibrary>> = null;
    await act(async () => {
      picked = await result.current.pickFromLibrary();
    });
    await act(async () => {
      await result.current.uploadReceipt(picked!);
    });

    expect(mockUpload).toHaveBeenCalledWith('file://receipt.png', 'UPLOAD', 'image/png');
  });

  it('surfaces 413 upload-too-large with the sentence the server sent', async () => {
    mockUpload.mockRejectedValue(
      refusal(413, 'upload-too-large', 'That image is larger than this server accepts.')
    );
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await result.current
        .uploadReceipt({ uri: 'file://big.jpg', source: 'SCAN', mimeType: 'image/jpeg' })
        .catch(() => undefined);
    });

    await waitFor(() => expect(result.current.uploadFailure).not.toBeNull());
    expect(result.current.uploadFailure?.status).toBe(413);
    expect(result.current.uploadFailure?.code).toBe('upload-too-large');
    expect(result.current.uploadFailure?.message).toBe(
      'That image is larger than this server accepts.'
    );
  });

  it('surfaces a rejected format as a validation error, not a crash', async () => {
    mockUpload.mockRejectedValue(
      refusal(400, 'validation-error', 'Those bytes are not the format they claim to be.')
    );
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await result.current
        .uploadReceipt({ uri: 'file://x.heic', source: 'UPLOAD', mimeType: 'image/heic' })
        .catch(() => undefined);
    });
    await waitFor(() => expect(result.current.uploadFailure?.code).toBe('validation-error'));
  });

  it('surfaces the client-side format refusal with its own sentence', async () => {
    // `uploadReceipt` refuses a format this API does not accept before a byte
    // leaves the device (HEIC is the iOS default and there is no conversion
    // step in this app), and that plain Error must reach the member as itself
    // rather than as "That receipt could not be uploaded."
    mockUpload.mockRejectedValue(new Error('Trove can send a JPEG or a PNG. That photo is not.'));
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await result.current
        .uploadReceipt({ uri: 'file://x.heic', source: 'UPLOAD', mimeType: 'image/heic' })
        .catch(() => undefined);
    });
    await waitFor(() => expect(result.current.uploadFailure).not.toBeNull());
    expect(result.current.uploadFailure?.message).toContain('JPEG or a PNG');
  });

  it('clears a previous failure when a later upload succeeds', async () => {
    mockUpload.mockRejectedValueOnce(refusal(413, 'upload-too-large', 'Too big.'));
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await result.current
        .uploadReceipt({ uri: 'file://big.jpg', source: 'SCAN', mimeType: 'image/jpeg' })
        .catch(() => undefined);
    });
    await waitFor(() => expect(result.current.uploadFailure).not.toBeNull());

    await act(async () => {
      await result.current.uploadReceipt({
        uri: 'file://ok.jpg',
        source: 'SCAN',
        mimeType: 'image/jpeg',
      });
    });
    await waitFor(() => expect(result.current.uploadFailure).toBeNull());
  });

  it('resets both states, e.g. when the capture modal is reopened', async () => {
    mockUpload.mockRejectedValue(refusal(413, 'upload-too-large', 'Too big.'));
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    await act(async () => {
      await result.current
        .uploadReceipt({ uri: 'file://big.jpg', source: 'SCAN', mimeType: 'image/jpeg' })
        .catch(() => undefined);
    });
    await waitFor(() => expect(result.current.uploadFailure).not.toBeNull());
    await act(() => result.current.reset());
    expect(result.current.uploadFailure).toBeNull();
    expect(result.current.permissionDenied).toBeNull();
  });

  it('publishes the size ceiling the capture screen quotes before the member shoots', async () => {
    const { result } = await renderHook(() => useReceiptCamera(), { wrapper });
    expect(result.current.maxUploadBytes).toBe(RECEIPT_MAX_UPLOAD_BYTES);
  });
});
