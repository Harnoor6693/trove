import React from 'react';
import { FlatList, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { ScreenState } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import { BUDGET_TYPE_LABEL } from '@lib/labels';
import { useBudgetPeriods } from '@hooks/useBudgets';

/** `GET /v1/budgets` — every period the household has, month descending. Not paged. */
export default function BudgetPeriodsScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  const periods = useBudgetPeriods();

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="budget-periods-screen"
    >
      <View style={styles.header}>
        <MnBtn
          label="Back"
          variant="ghost"
          onPress={() => router.back()}
          testID="budget-periods-back"
        />
        <MnEyebrow>All budget periods</MnEyebrow>
      </View>

      <ScreenState
        isLoading={periods.isLoading}
        error={periods.error}
        isEmpty={!periods.isLoading && periods.error == null && (periods.data ?? []).length === 0}
        emptyMessage="No budget periods yet."
        fallbackMessage="Could not load your budget periods."
        onRetry={() => void periods.refetch()}
        testID="budget-periods"
      >
        <FlatList
          data={periods.data ?? []}
          keyExtractor={(period) => period.id}
          contentContainerStyle={styles.list}
          renderItem={({ item }) => (
            <TouchableOpacity
              style={[styles.row, { borderBottomColor: theme.line }]}
              onPress={() => router.push(`/(tabs)/budgets/period/${item.id}`)}
              accessibilityRole="button"
              accessibilityLabel={`${format.date(item.month, 'monthYear')}, ${BUDGET_TYPE_LABEL[item.budgetType]}`}
              testID={`budget-period-row-${item.id}`}
            >
              <View>
                <Text style={[styles.month, { color: theme.ink }]}>
                  {format.date(item.month, 'monthYear')}
                </Text>
                <MnPill label={BUDGET_TYPE_LABEL[item.budgetType]} variant="blue" />
              </View>
              <MoneyDisplay amount={item.totalIncome} currency={item.currency} size="sm" />
            </TouchableOpacity>
          )}
        />
      </ScreenState>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  header: { padding: Space.s4, gap: Space.s2 },
  list: { paddingHorizontal: Space.s4 },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: Space.s3,
    borderBottomWidth: 1,
    minHeight: 44,
    gap: Space.s2,
  },
  month: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md },
});
