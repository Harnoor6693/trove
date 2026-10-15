import React from 'react';
import { RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { CurrencyTotalsCard } from '@components/mobile/CurrencyTotalsCard/CurrencyTotalsCard';
import { ScreenState } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import { useDashboardToday } from '@hooks/useDashboardToday';

/**
 * Today — `GET /v1/dashboard/today`.
 *
 * Two rules from the contract shape this screen entirely.
 *
 * **The day is the server's, not the device's.** The response carries `date`
 * and `timezone`: the boundaries were computed from the member's own
 * `users.timezone`, so a Vancouver member's evening purchase lands on today's
 * figure rather than tomorrow's. Nothing here reads a clock — the date shown is
 * the one the totals describe, rendered from the payload, and the zone is
 * printed beside it so the member can see which day was meant.
 *
 * **Currencies are never added together.** `byCurrency` carries one subtotal
 * per currency actually present in the ledger, and this screen renders one card
 * per entry, each labelled with its own code. There is no combined figure
 * anywhere on it and there must never be one: there is no exchange rate in this
 * product, so a blended total would be a number the server never computed and
 * could not stand behind. The headline fields are the caller's own currency's
 * subtotal repeated, which is why they are not rendered a second time — the
 * matching `byCurrency` card already says it.
 */
export default function TodayScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  const { data, isLoading, error, refetch, isRefetching } = useDashboardToday();

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID="today-screen">
      <ScrollView
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl refreshing={isRefetching} onRefresh={() => void refetch()} />
        }
      >
        <MnEyebrow>Today</MnEyebrow>

        <ScreenState
          isLoading={isLoading}
          error={error}
          isEmpty={data !== undefined && data.byCurrency.length === 0}
          emptyMessage="No transactions yet. Connect an account or add one by hand to see your day."
          fallbackMessage="Could not load your dashboard."
          onRetry={() => void refetch()}
          testID="today"
        >
          {data === undefined ? null : (
            <View style={styles.body}>
              <Text style={[styles.date, { color: theme.ink }]} testID="today-date">
                {format.date(data.date, 'long')}
              </Text>
              <Text
                style={[styles.zone, { color: theme.ink3 }]}
                accessibilityLabel={`Figures computed in the ${data.timezone} timezone`}
                testID="today-timezone"
              >
                {data.timezone}
              </Text>

              {data.byCurrency.map((totals) => (
                <CurrencyTotalsCard key={totals.currency} totals={totals} />
              ))}
            </View>
          )}
        </ScreenState>

        <View style={styles.actions}>
          <MnBtn
            label="View transactions"
            variant="secondary"
            fullWidth
            onPress={() => router.push('/(tabs)/transactions')}
            testID="today-to-transactions"
          />
          <MnBtn
            label="Manage accounts"
            variant="ghost"
            fullWidth
            onPress={() => router.push('/(tabs)/transactions/accounts')}
            testID="today-to-accounts"
          />
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, gap: Space.s3 },
  body: { gap: Space.s3 },
  date: { fontFamily: FontFamily.display, fontSize: FontSize.xl },
  zone: { fontFamily: FontFamily.mono, fontSize: FontSize.xs },
  actions: { gap: Space.s2, marginTop: Space.s4 },
});
