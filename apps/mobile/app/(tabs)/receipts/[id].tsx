import React, { useState } from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLocalSearchParams, useRouter } from 'expo-router';
import {
  isTerminalReceiptStatus,
  useDeleteReceipt,
  usePatchReceipt,
  useReceipt,
  useReceiptStatus,
  useReviewReceipt,
} from '@hooks/useReceipts';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { ScreenState } from '@components/ui/ScreenState/ScreenState';
import { ReceiptImage } from '@components/mobile/ReceiptImage/ReceiptImage';
import { RECEIPT_SOURCE_LABEL, RECEIPT_STATUS_LABEL, RECEIPT_STATUS_VARIANT } from '@lib/labels';
import { toApiFailure } from '@api/errors';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import type { CurrencyCode, ReceiptPatchRequest } from '@api/types';
import { normaliseAmountInput } from '@lib/amountInput';
import { FormScroll } from '@components/ui/FormScroll/FormScroll';

/**
 * One receipt in full.
 *
 * Four things happen here that the placeholder version did not do at all:
 *
 *  - **The image is shown**, fetched with the member's own credentials rather
 *    than handed to `<Image>` as a route it cannot authenticate.
 *  - **The pipeline is polled** while it is running, and the poll stops on
 *    `COMPLETE` or `FAILED` rather than running for as long as the screen is
 *    open.
 *  - **Review is a distinct action from a correction.** `PATCH
 *    /v1/receipts/{id}` writes fields; `PATCH /v1/receipts/{id}/review`
 *    records a verdict. Both are 409 `conflict` while the pipeline is still
 *    running, which is a "wait, this is still being read" state and not a
 *    generic failure.
 *  - **The tax split is displayed, never computed by the app.** The server
 *    reverse-calculates GST, PST and HST from the receipt total for Canadian
 *    members only; this screen shows what it sent, each on its own
 *    currency-carrying row, absent for a member whose country is not CA. The
 *    tax export does not carry these figures (the member data export includes
 *    the stored split).
 */
export default function ReceiptDetailScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  const { id } = useLocalSearchParams<{ id: string }>();
  const receiptId = id ?? '';

  const { data: receipt, isLoading, error, refetch } = useReceipt(receiptId);
  const inFlight = receipt !== undefined && !isTerminalReceiptStatus(receipt.status);
  const { data: live } = useReceiptStatus(receiptId, inFlight);
  const patch = usePatchReceipt(receiptId);
  const review = useReviewReceipt(receiptId);
  const remove = useDeleteReceipt();

  const [editing, setEditing] = useState(false);
  const [merchant, setMerchant] = useState('');
  const [amount, setAmount] = useState('');
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  // The status query is the fresher of the two while the pipeline runs.
  const status = live?.status ?? receipt?.status;
  const reviewRequired = live?.reviewRequired ?? receipt?.reviewRequired ?? false;

  const failure =
    patch.error !== null
      ? toApiFailure(patch.error, 'That change could not be saved.')
      : review.error !== null
        ? toApiFailure(review.error, 'That decision could not be recorded.')
        : remove.error !== null
          ? toApiFailure(remove.error, 'That receipt could not be deleted.')
          : null;

  const startEditing = () => {
    setMerchant(receipt?.extractedMerchant ?? '');
    setAmount(receipt?.extractedAmount ?? '');
    setEditing(true);
  };

  const save = () => {
    // Presence is meaningful: a field the member left blank is *not* sent, so
    // the server leaves it alone. There is no way to clear a field to null
    // through this operation, and pretending otherwise by sending an empty
    // string would be a 400.
    const body: ReceiptPatchRequest = {};
    if (merchant.trim() !== '') body.extractedMerchant = merchant.trim();
    if (amount.trim() !== '') body.extractedAmount = normaliseAmountInput(amount);
    patch.mutate(body, { onSuccess: () => setEditing(false) });
  };

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="receipt-detail-screen"
    >
      <FormScroll contentContainerStyle={styles.content}>
        <ScreenState
          isLoading={isLoading}
          error={error}
          fallbackMessage="Could not load that receipt."
          onRetry={() => void refetch()}
          testID="receipt-detail"
        >
          {receipt !== undefined && status !== undefined && (
            <>
              <MnCard style={styles.card}>
                <View style={styles.headerRow}>
                  <Text style={[styles.merchant, { color: theme.ink }]}>
                    {receipt.extractedMerchant ?? 'Not read yet'}
                  </Text>
                  <MnPill
                    label={RECEIPT_STATUS_LABEL[status]}
                    variant={RECEIPT_STATUS_VARIANT[status]}
                  />
                </View>

                <ReceiptImage
                  receiptId={receiptId}
                  variant="full"
                  merchant={receipt.extractedMerchant}
                  date={receipt.extractedDate ?? receipt.createdAt}
                  testID="receipt-detail-image"
                />

                <MoneyDisplay
                  amount={receipt.extractedAmount}
                  currency={receipt.extractedCurrency ?? undefined}
                  size="xl"
                  style={styles.amount}
                />
                <Text style={[styles.body, { color: theme.ink3 }]}>
                  {format.date(receipt.extractedDate ?? receipt.createdAt, 'long')} ·{' '}
                  {RECEIPT_SOURCE_LABEL[receipt.source]}
                </Text>

                {!isTerminalReceiptStatus(status) && (
                  <Text
                    style={[styles.body, { color: theme.blue }]}
                    accessibilityRole="alert"
                    testID="receipt-detail-processing"
                  >
                    Still being read — {RECEIPT_STATUS_LABEL[status]}. This page updates itself.
                  </Text>
                )}

                {receipt.errorMessage !== null && (
                  <Text
                    style={[styles.body, { color: theme.red }]}
                    testID="receipt-detail-failure-reason"
                  >
                    {receipt.errorMessage}
                  </Text>
                )}
              </MnCard>

              {(receipt.gstAmount !== null ||
                receipt.pstAmount !== null ||
                receipt.hstAmount !== null) && (
                <MnCard style={styles.card} testID="receipt-tax-split">
                  <Text style={[styles.section, { color: theme.ink3 }]}>TAX ON THIS RECEIPT</Text>
                  <Text style={[styles.body, { color: theme.ink3 }]}>
                    These are the figures your server worked out from the total. Trove calculates no
                    tax of its own.
                  </Text>
                  {receipt.gstAmount !== null && (
                    <TaxRow
                      label="GST"
                      amount={receipt.gstAmount}
                      currency={receipt.extractedCurrency}
                    />
                  )}
                  {receipt.pstAmount !== null && (
                    <TaxRow
                      label="PST"
                      amount={receipt.pstAmount}
                      currency={receipt.extractedCurrency}
                    />
                  )}
                  {receipt.hstAmount !== null && (
                    <TaxRow
                      label="HST"
                      amount={receipt.hstAmount}
                      currency={receipt.extractedCurrency}
                    />
                  )}
                </MnCard>
              )}

              {receipt.lineItems.length > 0 && (
                <MnCard style={styles.card} testID="receipt-line-items">
                  <Text style={[styles.section, { color: theme.ink3 }]}>ITEMS</Text>
                  {receipt.lineItems.map((line) => (
                    <View key={line.id} style={styles.lineRow}>
                      <View style={styles.lineMain}>
                        <Text style={[styles.value, { color: theme.ink }]} numberOfLines={2}>
                          {line.description}
                        </Text>
                        <Text style={[styles.body, { color: theme.ink3 }]}>
                          {format.number(Number(line.quantity))} ×{' '}
                          {format.money(line.unitPrice, {
                            currency: receipt.extractedCurrency ?? undefined,
                          })}
                          {line.taxable ? '' : ' · not taxable'}
                        </Text>
                      </View>
                      <MoneyDisplay
                        amount={line.totalPrice}
                        currency={receipt.extractedCurrency ?? undefined}
                        size="sm"
                      />
                    </View>
                  ))}
                </MnCard>
              )}

              {reviewRequired && (
                <MnCard style={styles.card} testID="receipt-review-required">
                  <Text style={[styles.section, { color: theme.ink3 }]}>REVIEW</Text>
                  <Text style={[styles.body, { color: theme.ink2 }]}>
                    These fields have not been checked by anyone. On an install with no vision model
                    that is the normal outcome — the image is stored and the fields are yours to
                    fill in.
                  </Text>
                  <MnBtn
                    label="These look right"
                    onPress={() => review.mutate({ accepted: true })}
                    loading={review.isPending}
                    style={styles.rowBtn}
                    testID="receipt-review-accept"
                  />
                  <MnBtn
                    label="Not worth correcting"
                    variant="ghost"
                    onPress={() => review.mutate({ accepted: false })}
                    loading={review.isPending}
                    style={styles.rowBtn}
                    testID="receipt-review-reject"
                  />
                </MnCard>
              )}

              <MnCard style={styles.card}>
                <Text style={[styles.section, { color: theme.ink3 }]}>EDIT</Text>
                {editing ? (
                  <>
                    <Text style={[styles.label, { color: theme.ink3 }]}>Merchant</Text>
                    <TextInput
                      value={merchant}
                      onChangeText={setMerchant}
                      accessibilityLabel="Merchant"
                      style={[styles.input, { borderColor: theme.line, color: theme.ink }]}
                      testID="receipt-edit-merchant"
                    />
                    <Text style={[styles.label, { color: theme.ink3 }]}>Total</Text>
                    <TextInput
                      value={amount}
                      onChangeText={setAmount}
                      keyboardType="decimal-pad"
                      accessibilityLabel="Receipt total"
                      style={[styles.input, { borderColor: theme.line, color: theme.ink }]}
                      testID="receipt-edit-amount"
                    />
                    <MnBtn
                      label="Save"
                      onPress={save}
                      loading={patch.isPending}
                      style={styles.rowBtn}
                      testID="receipt-edit-save"
                    />
                    <MnBtn
                      label="Cancel"
                      variant="ghost"
                      onPress={() => setEditing(false)}
                      style={styles.rowBtn}
                      testID="receipt-edit-cancel"
                    />
                  </>
                ) : (
                  <MnBtn
                    label="Edit these fields"
                    variant="secondary"
                    onPress={startEditing}
                    style={styles.rowBtn}
                    testID="receipt-edit-start"
                  />
                )}
              </MnCard>

              <MnCard style={styles.card}>
                <Text style={[styles.section, { color: theme.ink3 }]}>DELETE</Text>
                {confirmingDelete ? (
                  <>
                    <Text style={[styles.body, { color: theme.ink2 }]} accessibilityRole="alert">
                      This removes the receipt, its items and the image files on your server. It
                      cannot be undone.
                    </Text>
                    <MnBtn
                      label="Delete this receipt"
                      variant="danger"
                      loading={remove.isPending}
                      onPress={() =>
                        remove.mutate(receiptId, {
                          onSuccess: () => router.back(),
                        })
                      }
                      style={styles.rowBtn}
                      testID="receipt-delete-confirm"
                    />
                    <MnBtn
                      label="Keep it"
                      variant="ghost"
                      onPress={() => setConfirmingDelete(false)}
                      style={styles.rowBtn}
                      testID="receipt-delete-cancel"
                    />
                  </>
                ) : (
                  <MnBtn
                    label="Delete"
                    variant="ghost"
                    onPress={() => setConfirmingDelete(true)}
                    style={styles.rowBtn}
                    testID="receipt-delete-start"
                  />
                )}
              </MnCard>

              {failure !== null && (
                <Text
                  style={[styles.body, { color: theme.red }]}
                  accessibilityRole="alert"
                  testID="receipt-detail-action-error"
                >
                  {failure.code === 'conflict'
                    ? 'This receipt is still being read by your server. Wait for it to finish and try again.'
                    : failure.status === 429
                      ? `Too many requests. Try again${
                          failure.retryAfterSeconds === null
                            ? ' in a moment'
                            : ` in ${failure.retryAfterSeconds} seconds`
                        }.`
                      : failure.message}
                </Text>
              )}
            </>
          )}
        </ScreenState>
      </FormScroll>
    </SafeAreaView>
  );
}

function TaxRow({
  label,
  amount,
  currency,
}: {
  label: string;
  amount: string;
  // `CurrencyCode` rather than the two codes written out: the currency is the
  // member's own and this screen must never name one.
  currency: CurrencyCode | null;
}) {
  const theme = useTheme();
  return (
    <View style={styles.lineRow}>
      <Text style={[styles.value, { color: theme.ink2 }]}>{label}</Text>
      <MoneyDisplay amount={amount} currency={currency ?? undefined} size="sm" />
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, paddingBottom: Space.s16 },
  card: { marginBottom: Space.s4 },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: Space.s3,
    gap: Space.s3,
  },
  merchant: { fontFamily: FontFamily.displaySemi, fontSize: FontSize.lg, flex: 1 },
  amount: { marginTop: Space.s3 },
  body: { fontFamily: FontFamily.body, fontSize: FontSize.sm, marginTop: Space.s2 },
  section: {
    fontFamily: FontFamily.bodySemi,
    fontSize: FontSize.xs,
    letterSpacing: 1,
    marginBottom: Space.s2,
  },
  label: {
    fontFamily: FontFamily.bodySemi,
    fontSize: FontSize.xs,
    letterSpacing: 1,
    marginTop: Space.s3,
  },
  value: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md, flexShrink: 1 },
  lineRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: Space.s3,
    marginTop: Space.s2,
  },
  lineMain: { flex: 1 },
  input: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Space.s3,
    marginTop: Space.s2,
    minHeight: 44,
  },
  rowBtn: { marginTop: Space.s3 },
});
