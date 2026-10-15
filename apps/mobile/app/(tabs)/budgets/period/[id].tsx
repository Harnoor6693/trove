import React from 'react';
import { StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { ScreenState } from '@components/ui/ScreenState/ScreenState';
import { PeriodDetail } from '@components/mobile/PeriodDetail/PeriodDetail';
import { Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { isUuid } from '@api/paths';
import { useBudgetPeriod } from '@hooks/useBudgets';
import { FormScroll } from '@components/ui/FormScroll/FormScroll';

/** `GET /v1/budgets/{id}` — one period, from the periods list or a deep link. */
export default function BudgetPeriodScreen() {
  const theme = useTheme();
  const router = useRouter();
  const { id } = useLocalSearchParams<{ id: string }>();
  const validId = typeof id === 'string' && isUuid(id) ? id : '';

  const period = useBudgetPeriod(validId);

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="budget-period-screen"
    >
      <FormScroll contentContainerStyle={styles.content}>
        <View style={styles.header}>
          <MnBtn
            label="Back"
            variant="ghost"
            onPress={() => router.back()}
            testID="budget-period-back"
          />
          <MnEyebrow>Budget period</MnEyebrow>
        </View>

        <ScreenState
          isLoading={validId !== '' && period.isLoading}
          error={
            validId === '' ? new Error('That budget period could not be found.') : period.error
          }
          fallbackMessage="Could not load this budget period."
          onRetry={() => void period.refetch()}
          testID="budget-period"
        >
          {period.data ? (
            <PeriodDetail
              period={period.data}
              onDeleted={() => router.back()}
              testID="budget-period-detail"
            />
          ) : null}
        </ScreenState>
      </FormScroll>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, paddingBottom: Space.s16, gap: Space.s3 },
  header: { gap: Space.s2 },
});
