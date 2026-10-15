import React from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { ScreenState } from '@components/ui/ScreenState/ScreenState';
import { PeriodDetail } from '@components/mobile/PeriodDetail/PeriodDetail';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { toApiFailure } from '@api/errors';
import { useCurrentBudgetPeriod } from '@hooks/useBudgets';

/**
 * The Budgets tab's landing screen: the household's CURRENT month.
 *
 * `GET /v1/budgets/current` 404s when there is no period for this month —
 * that is this screen's EMPTY STATE ("no budget for this month, create
 * one"), not a failure to render through `ScreenState`'s generic error
 * branch. Every other failure (network, 401, 429, 500) still goes through
 * `ScreenState` in the ordinary way.
 */
export default function BudgetsHomeScreen() {
  const theme = useTheme();
  const router = useRouter();
  const current = useCurrentBudgetPeriod();

  const failure =
    current.error == null
      ? null
      : toApiFailure(current.error, "Could not load this month's budget.");
  const noBudgetThisMonth = failure !== null && failure.status === 404;

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID="budgets-screen">
      <ScrollView contentContainerStyle={styles.content}>
        <MnEyebrow>Budgets</MnEyebrow>

        <View style={styles.nav}>
          <MnBtn
            label="All periods"
            variant="ghost"
            onPress={() => router.push('/(tabs)/budgets/periods')}
            testID="budgets-nav-periods"
          />
          <MnBtn
            label="Goals"
            variant="ghost"
            onPress={() => router.push('/(tabs)/budgets/goals')}
            testID="budgets-nav-goals"
          />
          <MnBtn
            label="Categories"
            variant="ghost"
            onPress={() => router.push('/(tabs)/budgets/categories')}
            testID="budgets-nav-categories"
          />
          <MnBtn
            label="Merchant rules"
            variant="ghost"
            onPress={() => router.push('/(tabs)/budgets/rules')}
            testID="budgets-nav-rules"
          />
        </View>

        {noBudgetThisMonth ? (
          <View style={styles.empty} testID="budgets-empty-month">
            <Text style={[styles.emptyText, { color: theme.ink2 }]}>
              No budget for this month yet.
            </Text>
            <MnBtn
              label="Create a budget for this month"
              onPress={() => router.push('/(tabs)/budgets/new')}
              testID="budgets-create-cta"
            />
          </View>
        ) : (
          <ScreenState
            isLoading={current.isLoading}
            error={current.error}
            fallbackMessage="Could not load this month's budget."
            onRetry={() => void current.refetch()}
            testID="budgets-current"
          >
            {current.data ? (
              <PeriodDetail
                period={current.data}
                onDeleted={() => void current.refetch()}
                testID="budgets-current-period"
              />
            ) : null}
          </ScreenState>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, paddingBottom: Space.s16, gap: Space.s3 },
  nav: { flexDirection: 'row', flexWrap: 'wrap', gap: Space.s2 },
  empty: { alignItems: 'center', gap: Space.s3, paddingVertical: Space.s8 },
  emptyText: { fontFamily: FontFamily.body, fontSize: FontSize.md, textAlign: 'center' },
});
