import React from 'react';
import { ActivityIndicator, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useReceiptCamera, type PickedReceiptImage } from '@hooks/useReceiptCamera';
import { useUserFormat } from '@lib/useUserFormat';

/**
 * Capture a receipt from the camera or the photo library, and upload it.
 *
 * Three states this used to have no answer for, each of which left the member
 * pressing a button that did nothing:
 *
 *  - **A refused permission.** `launchCameraAsync` resolves `canceled: true`
 *    when the OS has already denied the camera, which is indistinguishable
 *    from the member backing out — so nothing happened and nothing was said.
 *    The refusal is explained here, and the only route back from it, the OS
 *    settings screen, is offered as a button.
 *  - **413 `upload-too-large`.** The ceiling is stated *before* the member
 *    shoots, and the server's own sentence is shown when it refuses.
 *  - **Any other refusal** — a format the server does not accept, a 429 —
 *    through the one error normaliser rather than swallowed.
 *
 * The screen keeps using the **one** `QueryClient` from `app/_layout.tsx`. The
 * second, module-scope client that used to wrap this modal is the exact defect
 * the mobile inventory records (§4) and must not come back.
 */
export interface ReceiptCaptureProps {
  onCaptured?: (receiptId: string) => void;
  testID?: string;
}

const BYTES_PER_MB = 1024 * 1024;

export function ReceiptCapture({ onCaptured, testID }: ReceiptCaptureProps) {
  const theme = useTheme();
  const format = useUserFormat();
  const {
    captureFromCamera,
    pickFromLibrary,
    uploadReceipt,
    isUploading,
    uploadFailure,
    permissionDenied,
    openDeviceSettings,
    maxUploadBytes,
  } = useReceiptCamera();

  // The picker decides the `ReceiptSource` and reports the format, and both
  // travel with the URI: a library pick is `UPLOAD`, not `SCAN`, and the part
  // is declared as the format the picker actually handed over.
  const send = async (pick: () => Promise<PickedReceiptImage | null>) => {
    const picked = await pick();
    if (picked === null) return;
    try {
      const accepted = await uploadReceipt(picked);
      if (accepted.id) onCaptured?.(accepted.id);
    } catch {
      // The refusal is already normalised onto `uploadFailure` and rendered
      // below; rethrowing here would surface an unhandled rejection instead.
    }
  };

  const limit = `${format.number(Math.round(maxUploadBytes / BYTES_PER_MB))} MB`;

  return (
    <View style={styles.container} testID={testID}>
      <Text style={[styles.title, { color: theme.ink }]}>Add Receipt</Text>
      <Text style={[styles.hint, { color: theme.ink3 }]} testID="receipt-capture-limit">
        JPEG or PNG, up to {limit}. The photo is re-encoded on the server, which is what removes the
        location your camera writes into it.
      </Text>

      {permissionDenied !== null && (
        <View
          style={[styles.notice, { backgroundColor: theme.amberSoft, borderColor: theme.amber }]}
          testID="receipt-capture-permission-denied"
        >
          <Text style={[styles.noticeText, { color: theme.ink }]} accessibilityRole="alert">
            {permissionDenied === 'camera'
              ? 'Trove cannot open the camera because the permission was refused. Grant camera access in your device settings to take a photo of a receipt.'
              : 'Trove cannot open your photo library because the permission was refused. Grant photo access in your device settings to choose a receipt.'}
          </Text>
          <MnBtn
            label="Open device settings"
            variant="secondary"
            onPress={() => {
              void openDeviceSettings();
            }}
            testID="receipt-capture-open-settings"
            style={styles.noticeBtn}
          />
        </View>
      )}

      {uploadFailure !== null && (
        <View
          style={[styles.notice, { backgroundColor: theme.redSoft, borderColor: theme.red }]}
          testID="receipt-capture-error"
        >
          <Text style={[styles.noticeText, { color: theme.ink }]} accessibilityRole="alert">
            {uploadFailure.status === 413
              ? `${uploadFailure.message} This app sends images up to ${limit}.`
              : uploadFailure.message}
          </Text>
        </View>
      )}

      {isUploading ? (
        <ActivityIndicator
          accessibilityLabel="Uploading the receipt"
          accessibilityRole="progressbar"
          color={theme.sage}
        />
      ) : (
        <>
          <TouchableOpacity
            style={[styles.option, { borderColor: theme.line }]}
            onPress={() => void send(captureFromCamera)}
            accessibilityRole="button"
            accessibilityLabel="Take a photo of a receipt"
            testID="camera-btn"
          >
            <Text style={[styles.optionText, { color: theme.ink }]}>Take Photo</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.option, { borderColor: theme.line }]}
            onPress={() => void send(pickFromLibrary)}
            accessibilityRole="button"
            accessibilityLabel="Choose a receipt from your photo library"
            testID="library-btn"
          >
            <Text style={[styles.optionText, { color: theme.ink }]}>Choose from Library</Text>
          </TouchableOpacity>
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { padding: Space.s6, alignItems: 'center' },
  title: {
    fontFamily: FontFamily.displaySemi,
    fontSize: FontSize.lg,
    marginBottom: Space.s2,
  },
  hint: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.xs,
    textAlign: 'center',
    marginBottom: Space.s4,
  },
  notice: {
    alignSelf: 'stretch',
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Space.s3,
    marginBottom: Space.s3,
  },
  noticeText: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
  noticeBtn: { marginTop: Space.s3 },
  option: {
    width: '100%',
    padding: Space.s4,
    borderRadius: Radius.md,
    borderWidth: 1,
    alignItems: 'center',
    marginBottom: Space.s2,
    minHeight: 48,
    justifyContent: 'center',
  },
  optionText: { fontFamily: FontFamily.bodySemi, fontSize: FontSize.md },
});
