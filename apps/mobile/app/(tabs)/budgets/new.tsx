import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { fieldError, toApiFailure } from '@api/errors';
import { BUDGET_TYPES, BUDGET_TYPE_HINT, BUDGET_TYPE_LABEL } from '@lib/labels';
import { useCreateBudgetPeriod } from '@hooks/useBudgets';
import { CURRENCY_CODES, type BudgetType, type CurrencyCode } from '@api/types';

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * `POST /v1/budgets` — open a budget period for a month.
 *
 * `currency` omitted takes the creating member's own reporting currency and
 * `budgetType` omitted defaults to `FLEXIBLE` — both start unset here so the
 * server's own default is what actually gets recorded unless the member picks
 * one, rather than this screen inventing a currency literal it has no basis
 * for.
 */
export default function NewBudgetPeriodScreen() {
  const theme = useTheme();
  const router = useRouter();
  const create = useCreateBudgetPeriod();

  const [month, setMonth] = useState(todayIso());
  const [totalIncome, setTotalIncome] = useState('');
  const [currency, setCurrency] = useState<CurrencyCode | null>(null);
  const [budgetType, setBudgetType] = useState<BudgetType | null>(null);

  const failure =
    create.error == null ? null : toApiFailure(create.error, 'Could not open that budget period.');

  const submit = () => {
    create.mutate(
      {
        month: month.trim(),
        totalIncome: totalIncome.trim(),
        currency: currency ?? undefined,
        budgetType: budgetType ?? undefined,
      },
      { onSuccess: () => router.back() }
    );
  };

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="new-budget-screen"
    >
      <ScrollView contentContainerStyle={styles.content}>
        <MnBtn
          label="Back"
          variant="ghost"
          onPress={() => router.back()}
          testID="new-budget-back"
        />
        <MnEyebrow>Open a budget period</MnEyebrow>

        <View style={styles.field}>
          <Text style={[styles.label, { color: theme.ink2 }]}>Month</Text>
          <Text style={[styles.hint, { color: theme.ink3 }]}>
            Any day inside the target month — it is normalised to the first.
          </Text>
          <TextInput
            value={month}
            onChangeText={setMonth}
            accessibilityLabel="Month"
            style={[
              styles.input,
              { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
            ]}
            testID="new-budget-month"
          />
        </View>

        <View style={styles.field}>
          <Text style={[styles.label, { color: theme.ink2 }]}>Total income</Text>
          <TextInput
            value={totalIncome}
            onChangeText={setTotalIncome}
            keyboardType="decimal-pad"
            accessibilityLabel="Total income"
            style={[
              styles.input,
              { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
            ]}
            testID="new-budget-income"
          />
          {failure === null ? null : (
            <FieldErrorText message={fieldError(failure, 'totalIncome')} />
          )}
        </View>

        <View style={styles.field}>
          <Text style={[styles.label, { color: theme.ink2 }]}>Currency</Text>
          <Text style={[styles.hint, { color: theme.ink3 }]}>Leave unset to use your own.</Text>
          <View style={styles.chips}>
            {CURRENCY_CODES.map((value) => (
              <TouchableOpacity
                key={value}
                onPress={() => setCurrency(currency === value ? null : value)}
                accessibilityRole="radio"
                accessibilityLabel={value}
                accessibilityState={{ selected: currency === value }}
                style={styles.chip}
                testID={`new-budget-currency-${value}`}
              >
                <MnPill label={value} variant={currency === value ? 'sage' : 'muted'} />
              </TouchableOpacity>
            ))}
          </View>
        </View>

        <View style={styles.field}>
          <Text style={[styles.label, { color: theme.ink2 }]}>Budget type</Text>
          <View style={styles.chips}>
            {BUDGET_TYPES.map((value) => (
              <TouchableOpacity
                key={value}
                onPress={() => setBudgetType(budgetType === value ? null : value)}
                accessibilityRole="radio"
                accessibilityLabel={BUDGET_TYPE_LABEL[value]}
                accessibilityState={{ selected: budgetType === value }}
                style={styles.chip}
                testID={`new-budget-type-${value}`}
              >
                <MnPill
                  label={BUDGET_TYPE_LABEL[value]}
                  variant={budgetType === value ? 'sage' : 'muted'}
                />
              </TouchableOpacity>
            ))}
          </View>
          {budgetType === null ? null : (
            <Text style={[styles.hint, { color: theme.ink3 }]}>{BUDGET_TYPE_HINT[budgetType]}</Text>
          )}
          {failure === null ? null : <FieldErrorText message={fieldError(failure, 'budgetType')} />}
        </View>

        {failure === null ? null : (
          <Text
            accessibilityRole="alert"
            style={[styles.error, { color: theme.red }]}
            testID="new-budget-error"
          >
            {failure.status === 409
              ? 'Your household already has a budget for that month. Open it from All periods instead.'
              : failureMessage(create.error, failure)}
          </Text>
        )}

        <MnBtn
          label="Open this period"
          fullWidth
          loading={create.isPending}
          disabled={month.trim() === '' || totalIncome.trim() === ''}
          onPress={submit}
          testID="new-budget-submit"
        />
      </ScrollView>
    </SafeAreaView>
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
  content: { padding: Space.s4, gap: Space.s3, paddingBottom: Space.s12 },
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
