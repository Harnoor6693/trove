import React, { useState } from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { ScreenState } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import { TRANSACTION_TYPE_LABEL, isOutflow } from '@lib/labels';
import { fieldError, toApiFailure } from '@api/errors';
import { isUuid } from '@api/paths';
import { useDeleteTransaction, usePatchTransaction, useTransaction } from '@hooks/useTransactions';
import { failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FormScroll } from '@components/ui/FormScroll/FormScroll';

/**
 * One transaction — `GET`, `PATCH` and `DELETE /v1/transactions/{id}`.
 *
 * **The patch is per field, on purpose.** `PATCH /v1/transactions/{id}` is
 * presence-meaningful: a field that is absent is left alone and a field present
 * with `null` clears it. This screen therefore never spreads a form into the
 * body — each control sends a body containing its own field and nothing else,
 * so saving a note cannot silently rewrite a category the member never touched,
 * and clearing one is an explicit `null` rather than an omission.
 *
 * **A transfer is not a route.** Marking this row as a transfer leg is
 * `{transferGroupId: "<uuid>"}` and unlinking it is `{transferGroupId: null}`;
 * that patch is the entire affordance the contract offers, alongside the
 * server's own automatic pairing job, and nothing else is invented here.
 *
 * **Delete is manual-only.** A row that came from a source answers 409 — it
 * would reappear on the next sync — so the way to remove it is to disconnect
 * its account, which is what the refusal copy says.
 */
export default function TransactionDetailScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  const params = useLocalSearchParams<{ id?: string }>();
  // Reachable from a `trove://` deep link as well as from the ledger, so the
  // id is untrusted input: anything that is not an id the contract could have
  // issued leaves the query idle and renders the state below rather than
  // becoming part of a request path.
  const routeId = typeof params.id === 'string' ? params.id : '';
  const id = isUuid(routeId) ? routeId : '';

  const { data, isLoading, error, refetch } = useTransaction(id);
  const patch = usePatchTransaction(id);
  const remove = useDeleteTransaction();

  const [merchant, setMerchant] = useState<string | null>(null);
  const [notes, setNotes] = useState<string | null>(null);
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [transferGroupId, setTransferGroupId] = useState<string | null>(null);
  const [tags, setTags] = useState<string | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  /** The category id a "Assign category" press would send, already trimmed. */
  const nextCategoryId = (categoryId ?? data?.categoryId ?? '').trim();
  /** The transfer group id a "Link transfer" press would send, already trimmed. */
  const nextTransferGroupId = (transferGroupId ?? data?.transferGroupId ?? '').trim();
  /** The tag list a "Save tags" press would send: comma-separated, trimmed, blanks dropped. */
  const nextTags = (tags ?? (data?.tags ?? []).join(', '))
    .split(',')
    .map((tag) => tag.trim())
    .filter((tag) => tag !== '');

  const patchFailure =
    patch.error === null || patch.error === undefined
      ? null
      : toApiFailure(patch.error, 'Could not save that change.');
  const deleteFailure =
    remove.error === null || remove.error === undefined
      ? null
      : toApiFailure(remove.error, 'Could not delete this transaction.');

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="transaction-detail-screen"
    >
      <FormScroll contentContainerStyle={styles.content}>
        <MnBtn
          label="Back"
          variant="ghost"
          onPress={() => router.back()}
          testID="transaction-detail-back"
        />

        {id === '' ? (
          <Text
            accessibilityRole="alert"
            style={[styles.error, { color: theme.red }]}
            testID="transaction-detail-invalid-id"
          >
            That is not a transaction id, so there is nothing to show here.
          </Text>
        ) : null}

        <ScreenState
          isLoading={isLoading}
          error={error}
          fallbackMessage="Could not load this transaction."
          onRetry={() => void refetch()}
          testID="transaction-detail"
        >
          {data === undefined ? null : (
            <View style={styles.body}>
              {/* Computed once, in one place: a control that sends
                  `<local state> ?? <server value>` sends `null` on a row that
                  never had the field (an unlink request under a "Link" button)
                  and `''` once the member clears the box, and both
                  `categoryId` and `transferGroupId` are `format: uuid` on the
                  wire, so the empty string is a 400 rather than the "clear it"
                  the empty box suggests. Clearing has its own button, which
                  sends the explicit `null` the contract defines. */}
              <MnCard style={[styles.card, { backgroundColor: theme.card }]}>
                <MnEyebrow>{TRANSACTION_TYPE_LABEL[data.type]}</MnEyebrow>
                <Text style={[styles.title, { color: theme.ink }]}>
                  {data.merchantName ?? data.description ?? 'Unnamed transaction'}
                </Text>
                <MoneyDisplay
                  amount={isOutflow(data.type) ? `-${data.amount}` : data.amount}
                  currency={data.currency}
                  size="xl"
                  colorBySign
                  testID="transaction-detail-amount"
                />
                <Text style={[styles.meta, { color: theme.ink3 }]} testID="transaction-detail-date">
                  {format.date(data.date, 'long')}
                </Text>
                {data.postedAt === null ? null : (
                  <Text style={[styles.meta, { color: theme.ink3 }]}>
                    Posted {format.date(data.postedAt, 'dateTime')}
                  </Text>
                )}
                <View style={styles.badges}>
                  {data.pending ? <MnPill label="Pending" variant="amber" /> : null}
                  {data.manual ? <MnPill label="Manual" variant="muted" /> : null}
                  {data.transferGroupId === null ? null : (
                    <MnPill label="Transfer leg" variant="blue" />
                  )}
                </View>
                {data.description === null ? null : (
                  <Text style={[styles.meta, { color: theme.ink2 }]}>{data.description}</Text>
                )}
              </MnCard>

              {patchFailure === null ? null : (
                <Text
                  accessibilityRole="alert"
                  style={[styles.error, { color: theme.red }]}
                  testID="transaction-patch-error"
                >
                  {failureMessage(patch.error, patchFailure)}
                </Text>
              )}

              <Field
                label="Merchant"
                value={merchant ?? data.merchantName ?? ''}
                onChangeText={setMerchant}
                error={patchFailure === null ? null : fieldError(patchFailure, 'merchantName')}
                testID="transaction-merchant"
              />
              <MnBtn
                label="Save merchant"
                variant="secondary"
                fullWidth
                loading={patch.isPending}
                onPress={() => patch.mutate({ merchantName: merchant ?? data.merchantName })}
                testID="transaction-save-merchant"
              />

              <Field
                label="Notes"
                value={notes ?? data.notes ?? ''}
                onChangeText={setNotes}
                error={patchFailure === null ? null : fieldError(patchFailure, 'notes')}
                testID="transaction-notes"
              />
              <MnBtn
                label="Save notes"
                variant="secondary"
                fullWidth
                loading={patch.isPending}
                onPress={() => patch.mutate({ notes: notes ?? data.notes })}
                testID="transaction-save-notes"
              />

              <Field
                label="Category id"
                value={categoryId ?? data.categoryId ?? ''}
                onChangeText={setCategoryId}
                error={patchFailure === null ? null : fieldError(patchFailure, 'categoryId')}
                testID="transaction-category"
              />
              <View style={styles.pair}>
                <MnBtn
                  label="Assign category"
                  variant="secondary"
                  style={styles.grow}
                  loading={patch.isPending}
                  disabled={nextCategoryId === ''}
                  onPress={() => patch.mutate({ categoryId: nextCategoryId })}
                  testID="transaction-save-category"
                />
                <MnBtn
                  label="Clear category"
                  variant="ghost"
                  style={styles.grow}
                  loading={patch.isPending}
                  // An explicit null clears both the column and the pinned
                  // override; omitting the field would leave them alone.
                  onPress={() => patch.mutate({ categoryId: null })}
                  testID="transaction-clear-category"
                />
              </View>

              <Field
                label="Transfer group id"
                value={transferGroupId ?? data.transferGroupId ?? ''}
                onChangeText={setTransferGroupId}
                error={patchFailure === null ? null : fieldError(patchFailure, 'transferGroupId')}
                testID="transaction-transfer-group"
              />
              <View style={styles.pair}>
                <MnBtn
                  label="Link transfer"
                  variant="secondary"
                  style={styles.grow}
                  loading={patch.isPending}
                  disabled={nextTransferGroupId === ''}
                  onPress={() => patch.mutate({ transferGroupId: nextTransferGroupId })}
                  testID="transaction-link-transfer"
                />
                <MnBtn
                  label="Unlink transfer"
                  variant="ghost"
                  style={styles.grow}
                  loading={patch.isPending}
                  onPress={() => patch.mutate({ transferGroupId: null })}
                  testID="transaction-unlink-transfer"
                />
              </View>

              {/* `tags` is the fifth writable field on `TransactionPatchRequest`
                  — a nullable list of strings. It is edited as one
                  comma-separated line because a tag chip editor is a component
                  this app does not have and this family does not own; the two
                  buttons are the two requests the contract distinguishes, a
                  list and an explicit `null`. */}
              <Field
                label="Tags"
                value={tags ?? (data.tags ?? []).join(', ')}
                onChangeText={setTags}
                error={patchFailure === null ? null : fieldError(patchFailure, 'tags')}
                testID="transaction-tags"
              />
              <View style={styles.pair}>
                <MnBtn
                  label="Save tags"
                  variant="secondary"
                  style={styles.grow}
                  loading={patch.isPending}
                  disabled={nextTags.length === 0}
                  onPress={() => patch.mutate({ tags: nextTags })}
                  testID="transaction-save-tags"
                />
                <MnBtn
                  label="Clear tags"
                  variant="ghost"
                  style={styles.grow}
                  loading={patch.isPending}
                  // An explicit null is "this row has no tags"; omitting the
                  // field would leave the ones it has exactly where they are.
                  onPress={() => patch.mutate({ tags: null })}
                  testID="transaction-clear-tags"
                />
              </View>

              {data.importBatchId === null ? null : (
                <MnBtn
                  label="View the import this came from"
                  variant="ghost"
                  fullWidth
                  onPress={() =>
                    router.push(`/(tabs)/transactions/import/${data.importBatchId ?? ''}`)
                  }
                  testID="transaction-to-import"
                />
              )}

              {deleteFailure === null ? null : (
                <Text
                  accessibilityRole="alert"
                  style={[styles.error, { color: theme.red }]}
                  testID="transaction-delete-error"
                >
                  {deleteFailure.status === 409
                    ? 'This row came from a source, so deleting it here would only bring it back on the next sync. Disconnect the account instead.'
                    : failureMessage(remove.error, deleteFailure)}
                </Text>
              )}

              {confirmingDelete ? (
                <View style={styles.confirm}>
                  <Text
                    style={[styles.meta, { color: theme.ink }]}
                    testID="transaction-delete-confirm-copy"
                  >
                    Delete this transaction permanently? Only a hand-entered row can be deleted.
                  </Text>
                  <View style={styles.pair}>
                    <MnBtn
                      label="Delete permanently"
                      variant="danger"
                      style={styles.grow}
                      loading={remove.isPending}
                      onPress={() =>
                        remove.mutate(data.id, {
                          onSuccess: () => router.back(),
                        })
                      }
                      testID="transaction-delete-confirm"
                    />
                    <MnBtn
                      label="Keep it"
                      variant="ghost"
                      style={styles.grow}
                      onPress={() => setConfirmingDelete(false)}
                      testID="transaction-delete-cancel"
                    />
                  </View>
                </View>
              ) : (
                <MnBtn
                  label="Delete transaction"
                  variant="danger"
                  fullWidth
                  onPress={() => setConfirmingDelete(true)}
                  testID="transaction-delete"
                />
              )}
            </View>
          )}
        </ScreenState>
      </FormScroll>
    </SafeAreaView>
  );
}

function Field({
  label,
  value,
  onChangeText,
  error,
  testID,
}: {
  label: string;
  value: string;
  onChangeText: (next: string) => void;
  error: string | null;
  testID: string;
}) {
  const theme = useTheme();
  return (
    <View style={styles.field}>
      <Text style={[styles.label, { color: theme.ink2 }]} nativeID={`${testID}-label`}>
        {label}
      </Text>
      <TextInput
        value={value}
        onChangeText={onChangeText}
        accessibilityLabel={label}
        accessibilityLabelledBy={`${testID}-label`}
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={testID}
      />
      {error === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`${testID}-error`}
        >
          {error}
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, gap: Space.s3, paddingBottom: Space.s12 },
  body: { gap: Space.s3 },
  card: { gap: Space.s2 },
  title: { fontFamily: FontFamily.displaySemi, fontSize: FontSize.lg },
  meta: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
  badges: { flexDirection: 'row', gap: Space.s2 },
  field: { gap: Space.s1 },
  label: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  input: {
    borderWidth: 1,
    borderRadius: Radius.md,
    paddingHorizontal: Space.s3,
    minHeight: 44,
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
  },
  pair: { flexDirection: 'row', gap: Space.s2 },
  grow: { flex: 1 },
  confirm: { gap: Space.s2 },
  error: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
});
