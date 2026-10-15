import React, { useState } from 'react';
import { StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { ScreenState, failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import { fieldError, toApiFailure } from '@api/errors';
import { BUDGET_TYPES, BUDGET_TYPE_HINT, BUDGET_TYPE_LABEL } from '@lib/labels';
import { useDeleteBudgetPeriod, useEnvelopes, useUpdateBudgetPeriod } from '@hooks/useBudgets';
import type { BudgetPeriodResponse, BudgetType, EnvelopeResponse } from '@api/types';
import { normaliseAmountInput } from '@lib/amountInput';

/**
 * One budget period: its income and type (editable in place), and its
 * envelopes with their live health. Shared by `budgets/index.tsx` (the
 * caller's current month) and `budgets/period/[id].tsx` (any period from the
 * list) — the same view either way, since the wire shape is identical.
 *
 * **The spent figure, the remaining amount and the health percentages are
 * all `EnvelopeResponse.health` / the response's own fields.** Nothing here
 * recomputes a percentage or sums an envelope's currency into anything but
 * itself — a household can hold a CAD and a USD envelope in the same period,
 * and this view renders each in its own currency with no combined total.
 */
export interface PeriodDetailProps {
  period: BudgetPeriodResponse;
  /** Called after a successful delete, so the caller decides where to go. */
  onDeleted: () => void;
  testID: string;
}

export function PeriodDetail({ period, onDeleted, testID }: PeriodDetailProps) {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();

  const envelopes = useEnvelopes(period.id);
  const update = useUpdateBudgetPeriod(period.id);
  const remove = useDeleteBudgetPeriod();

  const [editing, setEditing] = useState(false);
  const [income, setIncome] = useState(period.totalIncome);
  const [budgetType, setBudgetType] = useState<BudgetType>(period.budgetType);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const updateFailure =
    update.error == null ? null : toApiFailure(update.error, 'Could not save that change.');
  const deleteFailure =
    remove.error == null ? null : toApiFailure(remove.error, 'Could not delete this period.');

  const startEdit = () => {
    setIncome(period.totalIncome);
    setBudgetType(period.budgetType);
    update.reset();
    setEditing(true);
  };

  const save = () => {
    update.mutate(
      { totalIncome: normaliseAmountInput(income), budgetType },
      { onSuccess: () => setEditing(false) }
    );
  };

  return (
    <View testID={testID}>
      <MnCard style={styles.card}>
        <Text style={[styles.month, { color: theme.ink }]} testID={`${testID}-month`}>
          {format.date(period.month, 'monthYear')}
        </Text>

        {editing ? (
          <View style={styles.editBody}>
            <Text style={[styles.label, { color: theme.ink2 }]}>Total income</Text>
            <TextInput
              value={income}
              onChangeText={setIncome}
              keyboardType="decimal-pad"
              accessibilityLabel="Total income"
              style={[
                styles.input,
                { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
              ]}
              testID={`${testID}-income-input`}
            />
            {updateFailure === null ? null : (
              <FieldErrorText message={fieldError(updateFailure, 'totalIncome')} />
            )}

            <Text style={[styles.label, { color: theme.ink2 }]}>Budget type</Text>
            <View style={styles.chips}>
              {BUDGET_TYPES.map((value) => (
                <TouchableOpacity
                  key={value}
                  onPress={() => setBudgetType(value)}
                  accessibilityRole="radio"
                  accessibilityLabel={BUDGET_TYPE_LABEL[value]}
                  accessibilityState={{ selected: budgetType === value }}
                  style={styles.chip}
                  testID={`${testID}-type-${value}`}
                >
                  <MnPill
                    label={BUDGET_TYPE_LABEL[value]}
                    variant={budgetType === value ? 'sage' : 'muted'}
                  />
                </TouchableOpacity>
              ))}
            </View>
            <Text style={[styles.hint, { color: theme.ink3 }]}>{BUDGET_TYPE_HINT[budgetType]}</Text>
            {updateFailure === null ? null : (
              <FieldErrorText message={fieldError(updateFailure, 'budgetType')} />
            )}
            {updateFailure === null || updateFailure.status === 400 ? null : (
              <Text
                accessibilityRole="alert"
                style={[styles.error, { color: theme.red }]}
                testID={`${testID}-update-error`}
              >
                {failureMessage(update.error, updateFailure)}
              </Text>
            )}

            <View style={styles.pair}>
              <MnBtn
                label="Save"
                style={styles.grow}
                loading={update.isPending}
                disabled={income.trim() === ''}
                onPress={save}
                testID={`${testID}-save`}
              />
              <MnBtn
                label="Cancel"
                variant="ghost"
                style={styles.grow}
                onPress={() => {
                  update.reset();
                  setEditing(false);
                }}
                testID={`${testID}-cancel`}
              />
            </View>
          </View>
        ) : (
          <View style={styles.summaryBody}>
            <View style={styles.summaryRow}>
              <MoneyDisplay
                amount={period.totalIncome}
                currency={period.currency}
                size="lg"
                testID={`${testID}-income`}
              />
              <MnPill label={BUDGET_TYPE_LABEL[period.budgetType]} variant="blue" />
            </View>
            <MnBtn label="Edit" variant="secondary" onPress={startEdit} testID={`${testID}-edit`} />
          </View>
        )}
      </MnCard>

      <Text style={[styles.sectionTitle, { color: theme.ink2 }]}>Envelopes</Text>
      <ScreenState
        isLoading={envelopes.isLoading}
        error={envelopes.error}
        isEmpty={
          !envelopes.isLoading && envelopes.error == null && (envelopes.data ?? []).length === 0
        }
        emptyMessage="No envelopes yet. Add one to start tracking a category."
        fallbackMessage="Could not load this period's envelopes."
        onRetry={() => void envelopes.refetch()}
        testID={`${testID}-envelopes`}
      >
        <View testID={`${testID}-envelope-list`}>
          {(envelopes.data ?? []).map((envelope) => (
            <EnvelopeRow
              key={envelope.id}
              envelope={envelope}
              onPress={() =>
                router.push(`/(tabs)/budgets/period/${period.id}/envelope/${envelope.id}`)
              }
            />
          ))}
        </View>
      </ScreenState>

      <MnBtn
        label="Add an envelope"
        variant="secondary"
        fullWidth
        onPress={() => router.push(`/(tabs)/budgets/period/${period.id}/envelope/new`)}
        testID={`${testID}-add-envelope`}
      />

      {deleteFailure === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`${testID}-delete-error`}
        >
          {failureMessage(remove.error, deleteFailure)}
        </Text>
      )}

      {confirmingDelete ? (
        <View style={styles.confirm}>
          <Text
            style={[styles.hint, { color: theme.ink }]}
            testID={`${testID}-delete-confirm-copy`}
          >
            Deleting this period removes every envelope in it. Its transactions are untouched.
          </Text>
          <View style={styles.pair}>
            <MnBtn
              label="Delete period"
              variant="danger"
              style={styles.grow}
              loading={remove.isPending}
              onPress={() => remove.mutate(period.id, { onSuccess: onDeleted })}
              testID={`${testID}-delete-confirm`}
            />
            <MnBtn
              label="Keep it"
              variant="ghost"
              style={styles.grow}
              onPress={() => setConfirmingDelete(false)}
              testID={`${testID}-delete-cancel`}
            />
          </View>
        </View>
      ) : (
        <MnBtn
          label="Delete this period"
          variant="danger"
          fullWidth
          style={styles.deleteBtn}
          onPress={() => setConfirmingDelete(true)}
          testID={`${testID}-delete`}
        />
      )}
    </View>
  );
}

function EnvelopeRow({ envelope, onPress }: { envelope: EnvelopeResponse; onPress: () => void }) {
  const theme = useTheme();
  const health = envelope.health;
  return (
    <TouchableOpacity
      style={[styles.envelope, { borderBottomColor: theme.line }]}
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={`${envelope.categoryName}, ${health.healthPercentage} percent spent${
        health.overPace ? ', over pace' : ''
      }`}
      testID={`envelope-row-${envelope.id}`}
    >
      <View style={styles.envelopeMain}>
        <Text style={[styles.envelopeName, { color: theme.ink }]}>{envelope.categoryName}</Text>
        <Text style={[styles.envelopeMeta, { color: theme.ink3 }]}>
          <MoneyDisplay amount={envelope.spent} currency={envelope.currency} size="sm" /> of{' '}
          <MoneyDisplay amount={envelope.allocated} currency={envelope.currency} size="sm" />
        </Text>
      </View>
      <View style={styles.envelopeRight}>
        <MoneyDisplay
          amount={envelope.health.remainingAmount}
          currency={envelope.currency}
          size="sm"
          testID={`envelope-remaining-${envelope.id}`}
        />
        <MnPill label={`${health.healthPercentage}%`} variant={health.overPace ? 'red' : 'sage'} />
      </View>
    </TouchableOpacity>
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
  card: { gap: Space.s2, marginBottom: Space.s4 },
  month: { fontFamily: FontFamily.displaySemi, fontSize: FontSize.lg },
  summaryBody: { gap: Space.s3 },
  summaryRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  editBody: { gap: Space.s2 },
  label: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  hint: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  input: {
    borderWidth: 1,
    borderRadius: Radius.md,
    paddingHorizontal: Space.s3,
    minHeight: 44,
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
  },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: Space.s2 },
  chip: { minHeight: 44, justifyContent: 'center' },
  pair: { flexDirection: 'row', gap: Space.s2 },
  grow: { flex: 1 },
  error: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  sectionTitle: {
    fontFamily: FontFamily.bodySemi,
    fontSize: FontSize.sm,
    marginBottom: Space.s2,
    marginTop: Space.s2,
  },
  envelope: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: Space.s3,
    borderBottomWidth: 1,
    minHeight: 44,
  },
  envelopeMain: { flex: 1, gap: 2 },
  envelopeName: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md },
  envelopeMeta: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  envelopeRight: { alignItems: 'flex-end', gap: Space.s1 },
  confirm: { gap: Space.s2, marginTop: Space.s3 },
  deleteBtn: { marginTop: Space.s4 },
});
