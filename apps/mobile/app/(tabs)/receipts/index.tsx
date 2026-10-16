import React, { useState } from 'react';
import { FlatList, Modal, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useReceipts } from '@hooks/useReceipts';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { ScreenState } from '@components/ui/ScreenState/ScreenState';
import { ReceiptCapture } from '@components/mobile/ReceiptCapture/ReceiptCapture';
import { ReceiptImage } from '@components/mobile/ReceiptImage/ReceiptImage';
import { RECEIPT_STATUS_LABEL, RECEIPT_STATUS_VARIANT } from '@lib/labels';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';

/**
 * The household's receipts, **one page at a time**.
 *
 * `GET /v1/receipts` is paged and enveloped: its 200 requires `data` *and*
 * `meta`. This screen used to feed a `FlatList` from a function that returned
 * a bare array, so page two was unreachable and a household past its first
 * page was shown a truncated list it was never told about. It is an infinite
 * query now, driven by `onEndReached` and bounded by `meta.total`.
 *
 * The capture modal uses the **one** `QueryClient` defined in
 * `src/api/queryClient.ts` and provided by `app/_layout.tsx`.
 * The second, module-scope client that used to wrap it is the defect the
 * mobile inventory records (§4) and must not come back.
 */
export default function ReceiptsScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  const { data, isLoading, error, refetch, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useReceipts();
  const [showCapture, setShowCapture] = useState(false);

  const rows = data?.pages.flatMap((page) => page.data) ?? [];
  const total = data?.pages[0]?.meta.total ?? 0;

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID="receipts-screen">
      <View style={styles.header}>
        <View>
          <MnEyebrow>Receipts</MnEyebrow>
          {total > 0 && (
            <Text style={[styles.count, { color: theme.ink3 }]} testID="receipts-count">
              {format.number(total)} in this household
            </Text>
          )}
        </View>
        <MnBtn
          label="Scan"
          variant="secondary"
          onPress={() => setShowCapture(true)}
          testID="receipts-scan-btn"
        />
      </View>

      <ScreenState
        isLoading={isLoading}
        error={error}
        isEmpty={rows.length === 0}
        emptyMessage="No receipts yet. Scan one and it will appear here."
        fallbackMessage="Could not load receipts."
        onRetry={() => void refetch()}
        testID="receipts"
      >
        <FlatList
          testID="receipts-list"
          data={rows}
          keyExtractor={(receipt) => receipt.id}
          onEndReachedThreshold={0.4}
          onEndReached={() => {
            if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
          }}
          renderItem={({ item }) => (
            <TouchableOpacity
              style={[styles.item, { borderBottomColor: theme.line }]}
              onPress={() => router.push(`/(tabs)/receipts/${item.id}`)}
              accessibilityRole="button"
              accessibilityLabel={`${item.extractedMerchant ?? 'Receipt with no merchant yet'}, ${
                RECEIPT_STATUS_LABEL[item.status]
              }`}
              testID={`receipt-row-${item.id}`}
            >
              <ReceiptImage
                receiptId={item.id}
                merchant={item.extractedMerchant}
                date={item.extractedDate ?? item.createdAt}
                testID={`receipt-thumb-${item.id}`}
              />
              <View style={styles.itemMain}>
                <Text style={[styles.merchant, { color: theme.ink }]} numberOfLines={1}>
                  {item.extractedMerchant ?? 'Not read yet'}
                </Text>
                <Text style={[styles.date, { color: theme.ink3 }]}>
                  {format.date(item.extractedDate ?? item.createdAt, 'medium')}
                </Text>
                {item.reviewRequired && (
                  <Text
                    style={[styles.review, { color: theme.amber }]}
                    testID={`receipt-review-${item.id}`}
                  >
                    Needs your review
                  </Text>
                )}
              </View>
              <View style={styles.itemRight}>
                <MoneyDisplay
                  amount={item.extractedAmount}
                  currency={item.extractedCurrency ?? undefined}
                  size="sm"
                />
                <MnPill
                  label={RECEIPT_STATUS_LABEL[item.status]}
                  variant={RECEIPT_STATUS_VARIANT[item.status]}
                />
              </View>
            </TouchableOpacity>
          )}
          contentContainerStyle={styles.list}
          ListFooterComponent={
            isFetchingNextPage ? (
              <Text style={[styles.footer, { color: theme.ink3 }]} testID="receipts-loading-more">
                Loading more…
              </Text>
            ) : undefined
          }
        />
      </ScreenState>

      <Modal
        visible={showCapture}
        animationType="slide"
        onRequestClose={() => setShowCapture(false)}
      >
        <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]}>
          <ReceiptCapture onCaptured={() => setShowCapture(false)} />
          <MnBtn
            label="Cancel"
            variant="ghost"
            onPress={() => setShowCapture(false)}
            testID="receipts-capture-cancel"
          />
        </SafeAreaView>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: Space.s4,
  },
  count: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  list: { paddingHorizontal: Space.s4 },
  item: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.s3,
    paddingVertical: Space.s3,
    borderBottomWidth: 1,
    minHeight: 64,
  },
  itemMain: { flex: 1 },
  itemRight: { alignItems: 'flex-end', gap: Space.s1 },
  merchant: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md },
  date: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  review: { fontFamily: FontFamily.bodySemi, fontSize: FontSize.xs, marginTop: 2 },
  footer: {
    textAlign: 'center',
    paddingVertical: Space.s4,
    fontFamily: FontFamily.body,
    fontSize: FontSize.sm,
  },
});
