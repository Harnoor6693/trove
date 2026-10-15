import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { ScreenState, failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { fieldError, toApiFailure } from '@api/errors';
import { isUuid } from '@api/paths';
import { useCategories } from '@hooks/useCategories';
import { useCreateEnvelope } from '@hooks/useBudgets';
import { CURRENCY_CODES, type CurrencyCode } from '@api/types';
import { normaliseAmountInput } from '@lib/amountInput';

/**
 * `POST /v1/budgets/{id}/envelopes` — budget a category inside this period.
 *
 * One envelope per category per period: a second `POST` for a category the
 * period already budgets is 409 `conflict`, worded below rather than shown as
 * a generic failure. `currency` omitted takes the PERIOD's own currency.
 */
export default function NewEnvelopeScreen() {
  const theme = useTheme();
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const periodId = typeof id === 'string' && isUuid(id) ? id : '';

  const categories = useCategories();
  const create = useCreateEnvelope(periodId);

  const [categoryId, setCategoryId] = useState('');
  const [allocated, setAllocated] = useState('');
  const [currency, setCurrency] = useState<CurrencyCode | null>(null);

  const failure =
    create.error == null ? null : toApiFailure(create.error, 'Could not add that envelope.');

  const submit = () => {
    create.mutate(
      { categoryId, allocated: normaliseAmountInput(allocated), currency: currency ?? undefined },
      { onSuccess: () => router.back() }
    );
  };

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="new-envelope-screen"
    >
      <ScrollView contentContainerStyle={styles.content}>
        <MnBtn
          label="Back"
          variant="ghost"
          onPress={() => router.back()}
          testID="new-envelope-back"
        />
        <MnEyebrow>Add an envelope</MnEyebrow>

        <ScreenState
          isLoading={periodId !== '' && categories.isLoading}
          error={
            periodId === '' ? new Error('That budget period could not be found.') : categories.error
          }
          isEmpty={
            periodId !== '' &&
            !categories.isLoading &&
            categories.error == null &&
            (categories.data ?? []).length === 0
          }
          emptyMessage="Add a category first, then come back."
          fallbackMessage="Could not load your categories."
          onRetry={() => void categories.refetch()}
          testID="new-envelope-categories"
        >
          <View style={styles.body}>
            <Text style={[styles.label, { color: theme.ink2 }]}>Category</Text>
            <View style={styles.chips}>
              {(categories.data ?? []).map((category) => (
                <TouchableOpacity
                  key={category.id}
                  onPress={() => setCategoryId(category.id)}
                  accessibilityRole="radio"
                  accessibilityLabel={category.name}
                  accessibilityState={{ selected: categoryId === category.id }}
                  style={styles.chip}
                  testID={`new-envelope-category-${category.id}`}
                >
                  <MnPill
                    label={category.name}
                    variant={categoryId === category.id ? 'sage' : 'muted'}
                  />
                </TouchableOpacity>
              ))}
            </View>
            {failure === null ? null : (
              <FieldErrorText message={fieldError(failure, 'categoryId')} />
            )}

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
              testID="new-envelope-allocated"
            />
            {failure === null ? null : (
              <FieldErrorText message={fieldError(failure, 'allocated')} />
            )}

            <Text style={[styles.label, { color: theme.ink2 }]}>Currency</Text>
            <Text style={[styles.hint, { color: theme.ink3 }]}>
              Leave unset to use this period&apos;s currency.
            </Text>
            <View style={styles.chips}>
              {CURRENCY_CODES.map((value) => (
                <TouchableOpacity
                  key={value}
                  onPress={() => setCurrency(currency === value ? null : value)}
                  accessibilityRole="radio"
                  accessibilityLabel={value}
                  accessibilityState={{ selected: currency === value }}
                  style={styles.chip}
                  testID={`new-envelope-currency-${value}`}
                >
                  <MnPill label={value} variant={currency === value ? 'sage' : 'muted'} />
                </TouchableOpacity>
              ))}
            </View>

            {failure === null ? null : (
              <Text
                accessibilityRole="alert"
                style={[styles.error, { color: theme.red }]}
                testID="new-envelope-error"
              >
                {failure.status === 409
                  ? 'This period already has an envelope for that category. Edit it instead.'
                  : failureMessage(create.error, failure)}
              </Text>
            )}

            <MnBtn
              label="Add envelope"
              fullWidth
              loading={create.isPending}
              disabled={categoryId === '' || allocated.trim() === ''}
              onPress={submit}
              testID="new-envelope-submit"
            />
          </View>
        </ScreenState>
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
  body: { gap: Space.s3 },
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
