import React, { useState } from 'react';
import { FlatList, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { ScreenState, failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { fieldError, toApiFailure, type ApiFailure } from '@api/errors';
import {
  useCreateLiability,
  useDeleteLiability,
  useLiabilities,
  useUpdateLiability,
} from '@hooks/useNetWorth';
import { useUserFormat } from '@lib/useUserFormat';
import {
  CURRENCY_CODES,
  type CurrencyCode,
  type LiabilityResponse,
  type LiabilityType,
} from '@api/types';
import { normaliseAmountInput } from '@lib/amountInput';
import { keyboardScrollProps } from '@components/ui/FormScroll/FormScroll';

const LIABILITY_TYPES: LiabilityType[] = [
  'MORTGAGE',
  'CAR_LOAN',
  'STUDENT_LOAN',
  'CREDIT_CARD',
  'OTHER',
];

const LIABILITY_TYPE_LABEL: Record<LiabilityType, string> = {
  MORTGAGE: 'Mortgage',
  CAR_LOAN: 'Car loan',
  STUDENT_LOAN: 'Student loan',
  CREDIT_CARD: 'Credit card',
  OTHER: 'Other',
};

/**
 * `/v1/networth/liabilities*` — the household's shared ledger, CRUD.
 * `currency` is writable only at creation, for the same reason as an asset's;
 * `interestRate` is a percentage (`"19.9900"` means 19.99%), never money.
 */
export default function LiabilitiesScreen() {
  const theme = useTheme();
  const router = useRouter();
  const liabilities = useLiabilities();
  const [creating, setCreating] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="liabilities-screen"
    >
      <View style={styles.header}>
        <MnBtn
          label="Back"
          variant="ghost"
          onPress={() => router.back()}
          testID="liabilities-back"
        />
        <MnEyebrow>Liabilities</MnEyebrow>
        <MnBtn
          label={creating ? 'Cancel' : 'New liability'}
          variant="secondary"
          onPress={() => setCreating((v) => !v)}
          testID="liabilities-new-toggle"
        />
      </View>

      {creating ? (
        <LiabilityForm
          mode="create"
          onDone={() => setCreating(false)}
          testID="liabilities-create-form"
        />
      ) : null}

      <ScreenState
        isLoading={liabilities.isLoading}
        error={liabilities.error}
        isEmpty={
          !liabilities.isLoading &&
          liabilities.error == null &&
          (liabilities.data ?? []).length === 0
        }
        emptyMessage="No liabilities yet."
        fallbackMessage="Could not load your liabilities."
        onRetry={() => void liabilities.refetch()}
        testID="liabilities"
      >
        <FlatList
          {...keyboardScrollProps}
          data={liabilities.data ?? []}
          keyExtractor={(liability) => liability.id}
          contentContainerStyle={styles.list}
          renderItem={({ item }) =>
            editingId === item.id ? (
              <LiabilityForm
                mode="edit"
                liability={item}
                onDone={() => setEditingId(null)}
                testID={`liability-edit-form-${item.id}`}
              />
            ) : (
              <LiabilityRow liability={item} onEdit={() => setEditingId(item.id)} />
            )
          }
        />
      </ScreenState>
    </SafeAreaView>
  );
}

function LiabilityRow({ liability, onEdit }: { liability: LiabilityResponse; onEdit: () => void }) {
  const theme = useTheme();
  const remove = useDeleteLiability();
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const deleteFailure =
    remove.error == null ? null : toApiFailure(remove.error, 'Could not delete this liability.');

  return (
    <MnCard style={styles.card} testID={`liability-row-${liability.id}`}>
      <View style={styles.rowHeader}>
        <Text style={[styles.name, { color: theme.ink }]}>{liability.name}</Text>
        <Text style={[styles.typeLabel, { color: theme.ink3 }]}>
          {LIABILITY_TYPE_LABEL[liability.liabilityType]}
        </Text>
      </View>
      <MoneyDisplay
        amount={liability.balance}
        currency={liability.currency}
        size="lg"
        testID={`liability-balance-${liability.id}`}
      />
      <Text style={[styles.rate, { color: theme.ink3 }]}>{liability.interestRate}% interest</Text>
      <View style={styles.pair}>
        <MnBtn
          label="Edit"
          variant="secondary"
          style={styles.grow}
          onPress={onEdit}
          testID={`liability-edit-${liability.id}`}
        />
        {confirmingDelete ? (
          <MnBtn
            label="Confirm delete"
            variant="danger"
            style={styles.grow}
            loading={remove.isPending}
            onPress={() => remove.mutate(liability.id)}
            testID={`liability-delete-confirm-${liability.id}`}
          />
        ) : (
          <MnBtn
            label="Delete"
            variant="danger"
            style={styles.grow}
            onPress={() => setConfirmingDelete(true)}
            testID={`liability-delete-${liability.id}`}
          />
        )}
      </View>
      {deleteFailure === null ? null : (
        <Text
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
          testID={`liability-delete-error-${liability.id}`}
        >
          {failureMessage(remove.error, deleteFailure)}
        </Text>
      )}
    </MnCard>
  );
}

function LiabilityForm({
  mode,
  liability,
  onDone,
  testID,
}: {
  mode: 'create' | 'edit';
  liability?: LiabilityResponse;
  onDone: () => void;
  testID: string;
}) {
  const theme = useTheme();
  const create = useCreateLiability();
  const update = useUpdateLiability(liability?.id ?? '');

  const format = useUserFormat();
  const [name, setName] = useState(liability?.name ?? '');
  const [liabilityType, setLiabilityType] = useState<LiabilityType>(
    liability?.liabilityType ?? 'CREDIT_CARD'
  );
  const [balance, setBalance] = useState(liability?.balance ?? '');
  const [interestRate, setInterestRate] = useState(liability?.interestRate ?? '0');
  // Only meaningful in create mode — see the matching comment in assets.tsx.
  const [currency, setCurrency] = useState<CurrencyCode | null>(null);

  const pending = mode === 'create' ? create : update;
  const error = pending.error;
  const failure: ApiFailure | null =
    error == null ? null : toApiFailure(error, 'Could not save that liability.');

  const submit = () => {
    if (mode === 'create') {
      create.mutate(
        {
          name: name.trim(),
          liabilityType,
          balance: normaliseAmountInput(balance),
          interestRate: interestRate.trim() === '' ? '0' : normaliseAmountInput(interestRate),
          currency: currency ?? undefined,
        },
        { onSuccess: onDone }
      );
    } else {
      // A full replace (per `LiabilityUpdateRequest`): carry the row's
      // existing `accountId` forward so an edit doesn't silently unlink a
      // connected account.
      update.mutate(
        {
          name: name.trim(),
          liabilityType,
          balance: normaliseAmountInput(balance),
          interestRate: interestRate.trim() === '' ? '0' : normaliseAmountInput(interestRate),
          accountId: liability?.accountId ?? null,
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
        accessibilityLabel="Liability name"
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-name`}
      />
      {failure === null ? null : <FieldErrorText message={fieldError(failure, 'name')} />}

      <Text style={[styles.label, { color: theme.ink2 }]}>Type</Text>
      <View style={styles.choices}>
        {LIABILITY_TYPES.map((type) => (
          <TouchableOpacity
            key={type}
            onPress={() => setLiabilityType(type)}
            accessibilityRole="radio"
            accessibilityState={{ selected: liabilityType === type }}
            accessibilityLabel={LIABILITY_TYPE_LABEL[type]}
            style={[
              styles.chip,
              {
                borderColor: liabilityType === type ? theme.sage : theme.line,
                backgroundColor: liabilityType === type ? theme.sageSoft : 'transparent',
              },
            ]}
            testID={`${testID}-type-${type}`}
          >
            <Text
              style={[styles.chipText, { color: liabilityType === type ? theme.sage : theme.ink2 }]}
            >
              {LIABILITY_TYPE_LABEL[type]}
            </Text>
          </TouchableOpacity>
        ))}
      </View>

      {mode === 'create' && (
        <>
          <Text style={[styles.label, { color: theme.ink2 }]}>Currency</Text>
          <Text style={[styles.hint, { color: theme.ink3 }]}>
            Recorded in your own currency by default. Pick the household&apos;s other currency below
            if you hold this liability in it instead.
          </Text>
          <View style={styles.choices}>
            {CURRENCY_CODES.map((code) => {
              const selected = (currency ?? format.currency) === code;
              return (
                <TouchableOpacity
                  key={code}
                  onPress={() => setCurrency(code === format.currency ? null : code)}
                  accessibilityRole="radio"
                  accessibilityState={{ selected }}
                  accessibilityLabel={code === format.currency ? `${code} (your currency)` : code}
                  style={[
                    styles.chip,
                    {
                      borderColor: selected ? theme.sage : theme.line,
                      backgroundColor: selected ? theme.sageSoft : 'transparent',
                    },
                  ]}
                  testID={`${testID}-currency-${code}`}
                >
                  <Text style={[styles.chipText, { color: selected ? theme.sage : theme.ink2 }]}>
                    {code}
                    {code === format.currency ? ' (yours)' : ''}
                  </Text>
                </TouchableOpacity>
              );
            })}
          </View>
        </>
      )}

      <Text style={[styles.label, { color: theme.ink2 }]}>Balance</Text>
      <TextInput
        value={balance}
        onChangeText={setBalance}
        keyboardType="decimal-pad"
        accessibilityLabel="Liability balance"
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-balance`}
      />
      {failure === null ? null : <FieldErrorText message={fieldError(failure, 'balance')} />}

      <Text style={[styles.label, { color: theme.ink2 }]}>Interest rate (%)</Text>
      <Text style={[styles.hint, { color: theme.ink3 }]}>Leave at 0 for interest-free debt.</Text>
      <TextInput
        value={interestRate}
        onChangeText={setInterestRate}
        keyboardType="decimal-pad"
        accessibilityLabel="Interest rate percent"
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={`${testID}-rate`}
      />

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
          disabled={name.trim() === '' || balance.trim() === ''}
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
  typeLabel: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  rate: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
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
  choices: { flexDirection: 'row', flexWrap: 'wrap', gap: Space.s2 },
  chip: {
    paddingHorizontal: Space.s3,
    paddingVertical: Space.s2,
    borderRadius: Radius.md,
    borderWidth: 1,
    minHeight: 44,
    justifyContent: 'center',
  },
  chipText: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  error: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
});
