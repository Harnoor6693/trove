import React from 'react';
import { ActivityIndicator, Image, StyleSheet, Text, View, type ImageStyle } from 'react-native';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useReceiptImage, useReceiptThumbnail } from '@hooks/useReceipts';
import { useUserFormat } from '@lib/useUserFormat';

/**
 * A receipt's stored image, fetched with the member's own credentials.
 *
 * **Not `<Image source={{ uri: '/v1/receipts/…/thumbnail' }} />`.** That route
 * is authenticated on purpose — it is a photograph of the household's
 * spending, and an unauthenticated image path would be a way to read the
 * ledger without a session. React Native's `Image` issues its own request from
 * native code and cannot carry the `Authorization` header, so the bytes are
 * pulled through the axios instance and handed to `Image` as a `data:` URI
 * (see `src/api/binary.ts`). No token ever appears in a URL, and nothing is
 * written to disk.
 *
 * The alternative text is the merchant and the date, because "receipt image"
 * tells a screen-reader user nothing about *which* receipt they have landed
 * on in a list of them.
 */
export interface ReceiptImageProps {
  receiptId: string;
  /** The small JPEG for a row, or the full stored one for the detail screen. */
  variant?: 'thumbnail' | 'full';
  /** Shown in the alternative text so the image identifies itself. */
  merchant?: string | null;
  /** ISO date, formatted in the member's own locale and zone for the label. */
  date?: string | null;
  /** Skip the fetch entirely — a summary row with no thumbnail row on the server. */
  enabled?: boolean;
  style?: ImageStyle;
  testID?: string;
}

export function ReceiptImage({
  receiptId,
  variant = 'thumbnail',
  merchant,
  date,
  enabled = true,
  style,
  testID,
}: ReceiptImageProps) {
  const theme = useTheme();
  const format = useUserFormat();
  const thumbnail = useReceiptThumbnail(receiptId, enabled && variant === 'thumbnail');
  const full = useReceiptImage(receiptId, enabled && variant === 'full');
  const query = variant === 'thumbnail' ? thumbnail : full;

  const named = merchant !== null && merchant !== undefined && merchant !== '' ? merchant : null;
  const when = date === null || date === undefined ? null : format.date(date, 'medium');
  const label =
    named === null && when === null
      ? 'Receipt image'
      : `Receipt image${named === null ? '' : ` from ${named}`}${when === null ? '' : `, ${when}`}`;

  const frame = [
    variant === 'thumbnail' ? styles.thumbnail : styles.full,
    { backgroundColor: theme.paperDim, borderColor: theme.line },
  ];

  if (!enabled || query.isError) {
    return (
      <View style={frame} testID={testID ? `${testID}-missing` : undefined}>
        <Text style={[styles.placeholder, { color: theme.ink3 }]}>No image</Text>
      </View>
    );
  }

  if (query.data === undefined) {
    return (
      <View style={frame} testID={testID ? `${testID}-loading` : undefined}>
        <ActivityIndicator accessibilityLabel="Loading the receipt image" color={theme.sage} />
      </View>
    );
  }

  return (
    <Image
      source={{ uri: query.data }}
      style={[...frame, style] as ImageStyle[]}
      resizeMode="cover"
      accessible
      accessibilityRole="image"
      accessibilityLabel={label}
      testID={testID}
    />
  );
}

const styles = StyleSheet.create({
  thumbnail: {
    width: 56,
    height: 56,
    borderRadius: Radius.md,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  full: {
    width: '100%',
    height: 260,
    borderRadius: Radius.lg,
    borderWidth: 1,
    marginTop: Space.s3,
    alignItems: 'center',
    justifyContent: 'center',
  },
  placeholder: { fontFamily: FontFamily.body, fontSize: FontSize.xs, textAlign: 'center' },
});
