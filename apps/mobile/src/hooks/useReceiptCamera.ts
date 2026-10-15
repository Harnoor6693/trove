import { useCallback, useState } from 'react';
import * as ImagePicker from 'expo-image-picker';
import * as Linking from 'expo-linking';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { RECEIPT_MAX_UPLOAD_BYTES, uploadReceipt } from '@api/receipts';
import { toApiFailure, type ApiFailure } from '@api/errors';
import type { ReceiptSource, ReceiptUploadResponse } from '@api/types';
import { receiptKeys } from './useReceipts';

/**
 * Camera and photo-library capture, and the upload behind it.
 *
 * Three things this hook now does that the shipped one did not, each of which
 * was a dead end on a real device:
 *
 *  - **It asks for the permission.** `launchCameraAsync` on a device whose
 *    camera permission has been refused resolves `canceled: true`, which the
 *    old code read as "the member changed their mind" — so the button did
 *    nothing, for ever, with no explanation. The permission is requested
 *    first, a refusal is a state of its own, and
 *    {@link UseReceiptCamera.openDeviceSettings} is the only route back the
 *    app can offer: once a permission has been denied, iOS and Android both
 *    stop showing the prompt and only the OS settings screen can grant it.
 *  - **It surfaces the refusal.** 413 `upload-too-large` is a real answer with
 *    a sentence attached, not a silent failure. So are 400 `validation-error`
 *    (a format the server does not accept, or bytes that are not the format
 *    they claim) and a 429.
 *  - **It says which capture it was, and what format came back.** The
 *    contract's `ReceiptSource` distinguishes `SCAN` — "a photograph taken in
 *    the app" — from `UPLOAD`, "a file the member chose", and the receipt
 *    detail screen renders that value as fact (`RECEIPT_SOURCE_LABEL`:
 *    "Phone camera" / "Uploaded file"). `ReceiptPatchRequest` has no field to
 *    correct it, so a library pick uploaded as `SCAN` is permanently wrong.
 *    Each picker therefore returns a {@link PickedReceiptImage} carrying its
 *    own source, and the asset's `mimeType` with it, so
 *    `uploadReceipt` can declare the part as the format it really is.
 *  - **It still uses the one `QueryClient`.** The capture modal used to carry
 *    its own module-scope client, so an upload invalidated a cache the list
 *    never read and a captured receipt stayed invisible until the next cold
 *    start (mobile inventory §4). `useQueryClient()` resolves to the single
 *    client in `app/_layout.tsx`, and this hook must never build one.
 */

/** Which capture the member was refused. */
export type CaptureSource = 'camera' | 'library';

/**
 * One picked image, with the two facts the upload cannot invent: which
 * capture produced it, and what format the picker says it is.
 */
export interface PickedReceiptImage {
  uri: string;
  /**
   * The contract's own ingestion channel. `SCAN` for the camera, `UPLOAD` for
   * the library — this is stored and shown to the member and cannot be
   * corrected afterwards.
   */
  source: ReceiptSource;
  /** What the picker declared, or null when it declared nothing. */
  mimeType: string | null;
}

export interface UseReceiptCamera {
  captureFromCamera: () => Promise<PickedReceiptImage | null>;
  pickFromLibrary: () => Promise<PickedReceiptImage | null>;
  uploadReceipt: (picked: PickedReceiptImage) => Promise<ReceiptUploadResponse>;
  isUploading: boolean;
  /** The normalised refusal from the last upload, or null. */
  uploadFailure: ApiFailure | null;
  /** Set when the OS refused the permission; null otherwise. */
  permissionDenied: CaptureSource | null;
  /** Open this app's page in the OS settings — the only way back from a denial. */
  openDeviceSettings: () => Promise<void>;
  /** Clear the failure and denial states, e.g. when the modal is reopened. */
  reset: () => void;
  /** The size guidance shown before the member shoots. */
  maxUploadBytes: number;
}

export function useReceiptCamera(): UseReceiptCamera {
  const qc = useQueryClient();
  const [permissionDenied, setPermissionDenied] = useState<CaptureSource | null>(null);
  const [uploadFailure, setUploadFailure] = useState<ApiFailure | null>(null);

  const captureFromCamera = useCallback(async (): Promise<PickedReceiptImage | null> => {
    const permission = await ImagePicker.requestCameraPermissionsAsync();
    if (!permission.granted) {
      setPermissionDenied('camera');
      return null;
    }
    setPermissionDenied(null);
    const result = await ImagePicker.launchCameraAsync({ mediaTypes: ['images'], quality: 0.8 });
    const asset = result.canceled ? undefined : result.assets[0];
    if (asset === undefined) return null;
    // A photograph taken in the app is the contract's `SCAN`, and that is the
    // only thing this path may claim.
    return { uri: asset.uri, source: 'SCAN', mimeType: asset.mimeType ?? null };
  }, []);

  const pickFromLibrary = useCallback(async (): Promise<PickedReceiptImage | null> => {
    const permission = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (!permission.granted) {
      setPermissionDenied('library');
      return null;
    }
    setPermissionDenied(null);
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      quality: 0.8,
      // iOS keeps a library photo in its stored format by default, which is
      // HEIC — a format this API refuses. `Compatible` asks PhotoKit for the
      // most compatible representation (JPEG). Ignored on Android.
      preferredAssetRepresentationMode:
        ImagePicker.UIImagePickerPreferredAssetRepresentationMode.Compatible,
    });
    const asset = result.canceled ? undefined : result.assets[0];
    if (asset === undefined) return null;
    // A file the member chose is `UPLOAD`. It is not a scan, the detail screen
    // says which it was, and no later request can correct it.
    return { uri: asset.uri, source: 'UPLOAD', mimeType: asset.mimeType ?? null };
  }, []);

  const uploadMutation = useMutation({
    mutationFn: (picked: PickedReceiptImage) =>
      uploadReceipt(picked.uri, picked.source, picked.mimeType),
    onMutate: () => setUploadFailure(null),
    onError: (err: unknown) =>
      setUploadFailure(toApiFailure(err, 'That receipt could not be uploaded.')),
    onSuccess: () => {
      setUploadFailure(null);
      void qc.invalidateQueries({ queryKey: receiptKeys.all() });
    },
  });

  const openDeviceSettings = useCallback(() => Linking.openSettings(), []);

  const reset = useCallback(() => {
    setPermissionDenied(null);
    setUploadFailure(null);
  }, []);

  return {
    captureFromCamera,
    pickFromLibrary,
    uploadReceipt: uploadMutation.mutateAsync,
    isUploading: uploadMutation.isPending,
    uploadFailure,
    permissionDenied,
    openDeviceSettings,
    reset,
    maxUploadBytes: RECEIPT_MAX_UPLOAD_BYTES,
  };
}
