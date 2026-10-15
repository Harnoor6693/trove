import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { ScreenState, failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { fieldError, toApiFailure } from '@api/errors';
import { isUuid } from '@api/paths';
import { useDeleteEnvelope, useEnvelope, useUpdateEnvelope } from '@hooks/useBudgets';
import type { CurrencyCode } from '@api/types';
import { normaliseAmountInput } from '@lib/amountInput';

/**
 * One envelope: its live health (spent / allocated / remaining, the pace
 * marker, `overPace`) and its one writable field, `allocated`. THE SPENT
 * FIGURE, THE REMAINING AMOUNT AND THE PACE ARE THE SERVER'S OWN — this
 * screen renders `health` verbatim and computes no percentage of its own.
 */
export default function EnvelopeDetailScreen() {
  const theme = useTheme();
  const router = useRouter();
  const { id, envelopeId } = useLocalSearchParams<{ id: string; envelopeId: string }>();
  const periodId = typeof id === 'string' && isUuid(id) ? id : '';
  const validEnvelopeId = typeof envelopeId === 'string' && isUuid(envelopeId) ? envelopeId : '';

  const envelope = useEnvelope(periodId, validEnvelopeId);
  const update = useUpdateEnvelope(periodId, validEnvelopeId);
  const remove = useDeleteEnvelope(periodId);

  const [editing, setEditing] = useState(false);
  const [allocated, setAllocated] = useState('');
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const updateFailure =
    update.error == null ? null : toApiFailure(update.error, 'Could not save that change.');
  const deleteFailure =
    remove.error == null ? null : toApiFailure(remove.error, 'Could not remove this envelope.');

  const startEdit = () => {
    if (envelope.data) setAllocated(envelope.data.allocated);
    update.reset();
    setEditing(true);
  };

  const save = () => {
    update.mutate(
      { allocated: normaliseAmountInput(allocated) },
      { onSuccess: () => setEditing(false) }
    );
  };

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID="envelope-screen">
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.header}>
          <MnBtn
            label="Back"
            variant="ghost"
            onPress={() => router.back()}
            testID="envelope-back"
          />
          <MnEyebrow>Envelope</MnEyebrow>
        </View>

        <ScreenState
          isLoading={periodId !== '' && validEnvelopeId !== '' && envelope.isLoading}
          error={
            periodId === '' || validEnvelopeId === ''
              ? new Error('That envelope could not be found.')
              : envelope.error
          }
          fallbackMessage="Could not load this envelope."
          onRetry={() => void envelope.refetch()}
          testID="envelope"
        >
          {envelope.data ? (
            <>
              <MnCard style={styles.card}>
                <Text style={[styles.name, { color: theme.ink }]} testID="envelope-category">
                  {envelope.data.categoryName}
                </Text>

                <View style={styles.figures}>
                  <Figure
                    label="Allocated"
                    amount={envelope.data.allocated}
                    currency={envelope.data.currency}
                    testID="envelope-allocated"
                  />
                  <Figure
                    label="Spent"
                    amount={envelope.data.spent}
                    currency={envelope.data.currency}
                    testID="envelope-spent"
                  />
                  <Figure
                    label="Remaining"
                    amount={envelope.data.health.remainingAmount}
                    currency={envelope.data.currency}
                    testID="envelope-remaining"
                  />
                </View>

                <View style={styles.healthRow}>
                  <MnPill
                    label={`${envelope.data.health.healthPercentage}% spent`}
                    variant={envelope.data.health.overPace ? 'red' : 'sage'}
                  />
                  <Text style={[styles.hint, { color: theme.ink3 }]} testID="envelope-pace">
                    {envelope.data.health.paceMarkerPercentage}% of the month has passed
                    {envelope.data.health.overPace ? ' — spending is ahead of that pace.' : '.'}
                  </Text>
                </View>

                {editing ? (
                  <View style={styles.editBody}>
                    <Text style={[styles.label, { color: theme.ink2 }]}>Allocated</Text>
                    <TextInput
                      value={allocated}
                      onChangeText={setAllocated}
                      keyboardType="decimal-pad"
                      accessibilityLabel="Allocated amount"
                      style={[
                        styles.input,
                        { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
                      ]}
                      testID="envelope-allocated-input"
                    />
                    {updateFailure === null ? null : (
                      <FieldErrorText message={fieldError(updateFailure, 'allocated')} />
                    )}
                    {updateFailure === null || updateFailure.status === 400 ? null : (
                      <Text
                        accessibilityRole="alert"
                        style={[styles.error, { color: theme.red }]}
                        testID="envelope-update-error"
                      >
                        {failureMessage(update.error, updateFailure)}
                      </Text>
                    )}
                    <View style={styles.pair}>
                      <MnBtn
                        label="Save"
                        style={styles.grow}
                        loading={update.isPending}
                        disabled={allocated.trim() === ''}
                        onPress={save}
                        testID="envelope-save"
                      />
                      <MnBtn
                        label="Cancel"
                        variant="ghost"
                        style={styles.grow}
                        onPress={() => {
                          update.reset();
                          setEditing(false);
                        }}
                        testID="envelope-cancel"
                      />
                    </View>
                  </View>
                ) : (
                  <MnBtn
                    label="Edit allocation"
                    variant="secondary"
                    onPress={startEdit}
                    testID="envelope-edit"
                  />
                )}
              </MnCard>

              {deleteFailure === null ? null : (
                <Text
                  accessibilityRole="alert"
                  style={[styles.error, { color: theme.red }]}
                  testID="envelope-delete-error"
                >
                  {failureMessage(remove.error, deleteFailure)}
                </Text>
              )}

              {confirmingDelete ? (
                <View style={styles.confirm}>
                  <Text
                    style={[styles.hint, { color: theme.ink }]}
                    testID="envelope-delete-confirm-copy"
                  >
                    This stops budgeting the category this month. Its transactions are untouched.
                  </Text>
                  <View style={styles.pair}>
                    <MnBtn
                      label="Remove envelope"
                      variant="danger"
                      style={styles.grow}
                      loading={remove.isPending}
                      onPress={() =>
                        remove.mutate(validEnvelopeId, { onSuccess: () => router.back() })
                      }
                      testID="envelope-delete-confirm"
                    />
                    <MnBtn
                      label="Keep it"
                      variant="ghost"
                      style={styles.grow}
                      onPress={() => setConfirmingDelete(false)}
                      testID="envelope-delete-cancel"
                    />
                  </View>
                </View>
              ) : (
                <MnBtn
                  label="Remove this envelope"
                  variant="danger"
                  fullWidth
                  onPress={() => setConfirmingDelete(true)}
                  testID="envelope-delete"
                />
              )}
            </>
          ) : null}
        </ScreenState>
      </ScrollView>
    </SafeAreaView>
  );
}

function Figure({
  label,
  amount,
  currency,
  testID,
}: {
  label: string;
  amount: string;
  currency: CurrencyCode;
  testID: string;
}) {
  const theme = useTheme();
  return (
    <View style={styles.figure}>
      <Text style={[styles.figureLabel, { color: theme.ink3 }]}>{label}</Text>
      <MoneyDisplay amount={amount} currency={currency} testID={testID} />
    </View>
  );
}

function FieldErrorText({ message }: { message: string | null }) {
  const theme = useTheme();
  if (message === null) return null;
  return (
    <Text accessibilityRole="alert" style={[styles.error, { color: theme.red }]}>
      {message}
    </Text>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, paddingBottom: Space.s16, gap: Space.s3 },
  header: { gap: Space.s2 },
  card: { gap: Space.s3 },
  name: { fontFamily: FontFamily.displaySemi, fontSize: FontSize.lg },
  figures: { flexDirection: 'row', justifyContent: 'space-between' },
  figure: { gap: 2 },
  figureLabel: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  healthRow: { gap: Space.s1 },
  hint: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  editBody: { gap: Space.s2 },
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
  error: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  confirm: { gap: Space.s2 },
});
