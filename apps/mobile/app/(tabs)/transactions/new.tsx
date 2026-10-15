import React, { useMemo, useState } from 'react';
import { StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { ScreenState } from '@components/ui/ScreenState/ScreenState';
import { failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { TRANSACTION_TYPES, TRANSACTION_TYPE_LABEL } from '@lib/labels';
import { fieldError, toApiFailure } from '@api/errors';
import { useAccounts } from '@hooks/useAccounts';
import { useCreateManualTransaction } from '@hooks/useTransactions';
import type { TransactionType } from '@api/types';
import { normaliseAmountInput } from '@lib/amountInput';
import { FormScroll } from '@components/ui/FormScroll/FormScroll';

/**
 * Record a transaction by hand — `POST /v1/transactions`.
 *
 * **The account picker offers only `MANUAL` accounts.** The operation accepts
 * an account of this household whose `sourceKind` is `MANUAL` and answers 409
 * for anything else, because a hand-entered row on a synced account would be
 * indistinguishable from a fetched one and would be reconciled away. Filtering
 * the picker is the difference between the member never meeting that 409 and
 * meeting it after typing the whole form.
 *
 * `amount` is the **absolute value** as a decimal string and `type` carries the
 * direction — the same convention every ingested row uses. `currency` is the
 * account's and is not accepted here, which is why there is no currency control
 * on this form.
 */
export default function NewTransactionScreen() {
  const theme = useTheme();
  const router = useRouter();

  const accounts = useAccounts();
  const create = useCreateManualTransaction();

  const manualAccounts = useMemo(
    () => (accounts.data ?? []).filter((account) => account.sourceKind === 'MANUAL'),
    [accounts.data]
  );

  const [accountId, setAccountId] = useState('');
  const [amount, setAmount] = useState('');
  const [date, setDate] = useState('');
  const [type, setType] = useState<TransactionType>('DEBIT');
  const [merchant, setMerchant] = useState('');
  const [notes, setNotes] = useState('');

  const failure =
    create.error === null || create.error === undefined
      ? null
      : toApiFailure(create.error, 'Could not record that transaction.');

  const submit = () => {
    create.mutate(
      {
        accountId,
        amount: normaliseAmountInput(amount),
        date: date.trim(),
        type,
        merchant: merchant.trim() === '' ? undefined : merchant.trim(),
        notes: notes.trim() === '' ? undefined : notes.trim(),
      },
      { onSuccess: () => router.back() }
    );
  };

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="new-transaction-screen"
    >
      <FormScroll contentContainerStyle={styles.content} testID="new-transaction-form">
        <MnBtn
          label="Back"
          variant="ghost"
          onPress={() => router.back()}
          testID="new-transaction-back"
        />
        <MnEyebrow>Add a transaction</MnEyebrow>

        <ScreenState
          isLoading={accounts.isLoading}
          error={accounts.error}
          isEmpty={!accounts.isLoading && accounts.error === null && manualAccounts.length === 0}
          emptyMessage="You need a manual account first. Add one from Accounts, then come back."
          fallbackMessage="Could not load your accounts."
          onRetry={() => void accounts.refetch()}
          testID="new-transaction-accounts"
        >
          <View style={styles.body}>
            <Text style={[styles.label, { color: theme.ink2 }]}>Account</Text>
            <View style={styles.chips}>
              {manualAccounts.map((account) => (
                <TouchableOpacity
                  key={account.id}
                  onPress={() => setAccountId(account.id)}
                  accessibilityRole="radio"
                  accessibilityLabel={`${account.displayName}, ${account.currency}`}
                  accessibilityState={{ selected: accountId === account.id }}
                  style={styles.chip}
                  testID={`new-transaction-account-${account.id}`}
                >
                  <MnPill
                    label={`${account.displayName} · ${account.currency}`}
                    variant={accountId === account.id ? 'sage' : 'muted'}
                  />
                </TouchableOpacity>
              ))}
            </View>
            {failure === null ? null : (
              <FieldError
                message={fieldError(failure, 'accountId')}
                testID="new-transaction-accountId-error"
              />
            )}

            <LabelledInput
              label="Amount"
              hint="The absolute value, in the account's own currency."
              value={amount}
              onChangeText={setAmount}
              keyboardType="decimal-pad"
              error={failure === null ? null : fieldError(failure, 'amount')}
              testID="new-transaction-amount"
            />

            <LabelledInput
              label="Date"
              hint="Calendar date, as year-month-day."
              value={date}
              onChangeText={setDate}
              error={failure === null ? null : fieldError(failure, 'date')}
              testID="new-transaction-date"
            />

            <Text style={[styles.label, { color: theme.ink2 }]}>Type</Text>
            <View style={styles.chips}>
              {TRANSACTION_TYPES.map((value) => (
                <TouchableOpacity
                  key={value}
                  onPress={() => setType(value)}
                  accessibilityRole="radio"
                  accessibilityLabel={TRANSACTION_TYPE_LABEL[value]}
                  accessibilityState={{ selected: type === value }}
                  style={styles.chip}
                  testID={`new-transaction-type-${value}`}
                >
                  <MnPill
                    label={TRANSACTION_TYPE_LABEL[value]}
                    variant={type === value ? 'sage' : 'muted'}
                  />
                </TouchableOpacity>
              ))}
            </View>

            <LabelledInput
              label="Merchant"
              value={merchant}
              onChangeText={setMerchant}
              error={failure === null ? null : fieldError(failure, 'merchant')}
              testID="new-transaction-merchant"
            />

            <LabelledInput
              label="Notes"
              value={notes}
              onChangeText={setNotes}
              error={failure === null ? null : fieldError(failure, 'notes')}
              testID="new-transaction-notes"
            />

            {failure === null ? null : (
              <Text
                accessibilityRole="alert"
                style={[styles.error, { color: theme.red }]}
                testID="new-transaction-error"
              >
                {failure.status === 409
                  ? 'That account is not a manual account, so a hand-entered row on it would be reconciled away. Pick a manual account.'
                  : failureMessage(create.error, failure)}
              </Text>
            )}

            <MnBtn
              label="Record transaction"
              fullWidth
              loading={create.isPending}
              disabled={accountId === '' || amount.trim() === '' || date.trim() === ''}
              onPress={submit}
              testID="new-transaction-submit"
            />
          </View>
        </ScreenState>
      </FormScroll>
    </SafeAreaView>
  );
}

function LabelledInput({
  label,
  hint,
  value,
  onChangeText,
  keyboardType,
  error,
  testID,
}: {
  label: string;
  hint?: string;
  value: string;
  onChangeText: (next: string) => void;
  keyboardType?: 'decimal-pad';
  error: string | null;
  testID: string;
}) {
  const theme = useTheme();
  return (
    <View style={styles.field}>
      <Text style={[styles.label, { color: theme.ink2 }]} nativeID={`${testID}-label`}>
        {label}
      </Text>
      {hint === undefined ? null : <Text style={[styles.hint, { color: theme.ink3 }]}>{hint}</Text>}
      <TextInput
        value={value}
        onChangeText={onChangeText}
        keyboardType={keyboardType}
        accessibilityLabel={label}
        accessibilityHint={hint}
        accessibilityLabelledBy={`${testID}-label`}
        style={[
          styles.input,
          { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
        ]}
        testID={testID}
      />
      <FieldError message={error} testID={`${testID}-error`} />
    </View>
  );
}

function FieldError({ message, testID }: { message: string | null; testID: string }) {
  const theme = useTheme();
  if (message === null) return null;
  return (
    <Text accessibilityRole="alert" style={[styles.error, { color: theme.red }]} testID={testID}>
      {message}
    </Text>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, gap: Space.s3, paddingBottom: Space.s12 },
  body: { gap: Space.s3 },
  field: { gap: Space.s1 },
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
  error: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
});
