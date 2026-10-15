import React, { useState } from 'react';
import { FlatList, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { ScreenState, failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { fieldError, toApiFailure, type ApiFailure } from '@api/errors';
import { useCreateGoal, useDeleteGoal, useGoals, useUpdateGoal } from '@hooks/useGoals';
import type { GoalResponse } from '@api/types';
import { normaliseAmountInput } from '@lib/amountInput';

/**
 * `/v1/goals*` — list, create, edit, delete. Not paged (a household
 * accumulates goals slowly). Progress is rendered only from what
 * `GoalResponse` itself carries (`currentAmount`/`targetAmount`/`achieved`) —
 * there is no server-computed pace for a goal the way there is for an
 * envelope, so a plain ratio of the two response fields is the only figure
 * this screen shows, and money always goes through `<MoneyDisplay>`.
 */
export default function GoalsScreen() {
  const theme = useTheme();
  const router = useRouter();
  const goals = useGoals();
  const [creating, setCreating] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID="goals-screen">
      <View style={styles.header}>
        <MnBtn label="Back" variant="ghost" onPress={() => router.back()} testID="goals-back" />
        <MnEyebrow>Savings goals</MnEyebrow>
        <MnBtn
          label={creating ? 'Cancel' : 'New goal'}
          variant="secondary"
          onPress={() => setCreating((v) => !v)}
          testID="goals-new-toggle"
        />
      </View>

      {creating ? (
        <GoalForm mode="create" onDone={() => setCreating(false)} testID="goals-create-form" />
      ) : null}

      <ScreenState
        isLoading={goals.isLoading}
        error={goals.error}
        isEmpty={!goals.isLoading && goals.error == null && (goals.data ?? []).length === 0}
        emptyMessage="No savings goals yet."
        fallbackMessage="Could not load your savings goals."
        onRetry={() => void goals.refetch()}
        testID="goals"
      >
        <FlatList
          data={goals.data ?? []}
          keyExtractor={(goal) => goal.id}
          contentContainerStyle={styles.list}
          renderItem={({ item }) =>
            editingId === item.id ? (
              <GoalForm
                mode="edit"
                goal={item}
                onDone={() => setEditingId(null)}
                testID={`goal-edit-form-${item.id}`}
              />
            ) : (
              <GoalRow goal={item} onEdit={() => setEditingId(item.id)} />
            )
          }
        />
      </ScreenState>
    </SafeAreaView>
  );
}

function GoalRow({ goal, onEdit }: { goal: GoalResponse; onEdit: () => void }) {
  const theme = useTheme();
  const remove = useDeleteGoal();
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const deleteFailure =
    remove.error == null ? null : toApiFailure(remove.error, 'Could not delete this goal.');

  const target = Number(goal.targetAmount);
  const current = Number(goal.currentAmount);
  const progress =
    Number.isFinite(target) && Number.isFinite(current) && target > 0
      ? Math.min(100, Math.round((current / target) * 100))
      : 0;

  return (
    <MnCard style={styles.card} testID={`goal-row-${goal.id}`}>
      <View style={styles.rowHeader}>
        <Text style={[styles.name, { color: theme.ink }]}>{goal.name}</Text>
        {goal.achieved ? <MnPill label="Achieved" variant="sage" /> : null}
      </View>

      <View
        style={[styles.progressTrack, { backgroundColor: theme.line }]}
        accessibilityRole="progressbar"
        accessibilityValue={{ now: progress, min: 0, max: 100 }}
      >
        <View
          style={[styles.progressFill, { width: `${progress}%`, backgroundColor: theme.sage }]}
        />
      </View>

      <View style={styles.figuresRow}>
        <MoneyDisplay
          amount={goal.currentAmount}
          currency={goal.currency}
          size="sm"
          testID={`goal-current-${goal.id}`}
        />
        <Text style={{ color: theme.ink3 }}> of </Text>
        <MoneyDisplay
          amount={goal.targetAmount}
          currency={goal.currency}
          size="sm"
          testID={`goal-target-${goal.id}`}
        />
      </View>

      <View style={styles.pair}>
        <MnBtn
          label="Edit"
          variant="secondary"
          style={styles.grow}
          onPress={onEdit}
          testID={`goal-edit-${goal.id}`}
        />
        {confirmingDelete ? (
          <MnBtn
            label="Confirm delete"
            variant="danger"
            style={styles.grow}
            loading={remove.isPending}
            onPress={() => remove.mutate(goal.id)}
            testID={`goal-delete-confirm-${goal.id}`}
          />
        ) : (
          <MnBtn
            label="Delete"
            variant="danger"
            style={styles.grow}
            onPress={() => setConfirmingDelete(true)}
            testID={`goal-delete-${goal.id}`}
          />
        )}
      </View>
      {deleteFailure === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`goal-delete-error-${goal.id}`}
        >
          {failureMessage(remove.error, deleteFailure)}
        </Text>
      )}
    </MnCard>
  );
}

function GoalForm({
  mode,
  goal,
  onDone,
  testID,
}: {
  mode: 'create' | 'edit';
  goal?: GoalResponse;
  onDone: () => void;
  testID: string;
}) {
  const theme = useTheme();
  const create = useCreateGoal();
  const update = useUpdateGoal(goal?.id ?? '');

  const [name, setName] = useState(goal?.name ?? '');
  const [targetAmount, setTargetAmount] = useState(goal?.targetAmount ?? '');
  const [currentAmount, setCurrentAmount] = useState(goal?.currentAmount ?? '0');
  const [deadline, setDeadline] = useState(goal?.deadline ?? '');

  const pending = mode === 'create' ? create : update;
  const error = pending.error;
  const failure: ApiFailure | null =
    error == null ? null : toApiFailure(error, 'Could not save that goal.');

  const submit = () => {
    if (mode === 'create') {
      create.mutate(
        {
          name: name.trim(),
          targetAmount: normaliseAmountInput(targetAmount),
          deadline: deadline.trim() === '' ? undefined : deadline.trim(),
        },
        { onSuccess: onDone }
      );
    } else {
      update.mutate(
        {
          name: name.trim(),
          targetAmount: normaliseAmountInput(targetAmount),
          currentAmount: normaliseAmountInput(currentAmount),
          deadline: deadline.trim() === '' ? null : deadline.trim(),
        },
        { onSuccess: onDone }
      );
    }
  };

  return (
    <MnCard style={styles.card} testID={testID}>
      <Text style={[styles.label, { color: theme.ink2 }]}>Name</Text>
      <TextInput
        value={name}
        onChangeText={setName}
        accessibilityLabel="Goal name"
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-name`}
      />
      {failure === null ? null : <FieldErrorText message={fieldError(failure, 'name')} />}

      <Text style={[styles.label, { color: theme.ink2 }]}>Target amount</Text>
      <TextInput
        value={targetAmount}
        onChangeText={setTargetAmount}
        keyboardType="decimal-pad"
        accessibilityLabel="Target amount"
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-target`}
      />
      {failure === null ? null : <FieldErrorText message={fieldError(failure, 'targetAmount')} />}

      {mode === 'edit' ? (
        <>
          <Text style={[styles.label, { color: theme.ink2 }]}>Current amount</Text>
          <TextInput
            value={currentAmount}
            onChangeText={setCurrentAmount}
            keyboardType="decimal-pad"
            accessibilityLabel="Current amount"
            style={[
              styles.input,
              { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
            ]}
            testID={`${testID}-current`}
          />
          {failure === null ? null : (
            <FieldErrorText message={fieldError(failure, 'currentAmount')} />
          )}
        </>
      ) : null}

      <Text style={[styles.label, { color: theme.ink2 }]}>Deadline</Text>
      <Text style={[styles.hint, { color: theme.ink3 }]}>
        Optional. Leave blank for open-ended.
      </Text>
      <TextInput
        value={deadline}
        onChangeText={setDeadline}
        accessibilityLabel="Deadline"
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-deadline`}
      />
      {failure === null ? null : <FieldErrorText message={fieldError(failure, 'deadline')} />}

      {failure === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`${testID}-error`}
        >
          {failureMessage(error, failure)}
        </Text>
      )}

      <View style={styles.pair}>
        <MnBtn
          label="Save"
          style={styles.grow}
          loading={pending.isPending}
          disabled={name.trim() === '' || targetAmount.trim() === ''}
          onPress={submit}
          testID={`${testID}-submit`}
        />
        <MnBtn
          label="Cancel"
          variant="ghost"
          style={styles.grow}
          onPress={onDone}
          testID={`${testID}-cancel`}
        />
      </View>
    </MnCard>
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
  header: { padding: Space.s4, gap: Space.s2 },
  list: { paddingHorizontal: Space.s4, gap: Space.s3 },
  card: { gap: Space.s2, marginBottom: Space.s3 },
  rowHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  name: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md },
  progressTrack: {
    height: 8,
    borderRadius: Radius.sm,
    overflow: 'hidden',
  },
  progressFill: { height: '100%' },
  figuresRow: { flexDirection: 'row', alignItems: 'center' },
  pair: { flexDirection: 'row', gap: Space.s2 },
  grow: { flex: 1 },
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
  error: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
});
