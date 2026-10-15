import React from 'react';
import { FlatList, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { ScreenState } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import {
  useScanSubscriptions,
  useSubscriptionAlerts,
  useSubscriptionSummary,
  useSubscriptions,
} from '@hooks/useSubscriptions';
import type { SubscriptionResponse, SubscriptionSignal } from '@api/types';

const CYCLE_LABEL: Record<SubscriptionResponse['billingCycle'], string> = {
  WEEKLY: 'weekly',
  BIWEEKLY: 'every two weeks',
  MONTHLY: 'monthly',
  QUARTERLY: 'quarterly',
  ANNUAL: 'yearly',
};

const SIGNAL_VARIANT: Record<SubscriptionSignal, 'sage' | 'amber' | 'red'> = {
  GREEN: 'sage',
  AMBER: 'amber',
  RED: 'red',
};

const SIGNAL_LABEL: Record<SubscriptionSignal, string> = {
  GREEN: 'On track',
  AMBER: 'Price changed',
  RED: 'Charge missing',
};

/**
 * The Subscriptions tab's landing screen: detected recurring charges, the
 * per-currency run-rate summary, and the manual scan trigger. Nothing here is
 * created or edited by hand — a subscription is an observation about the
 * ledger, written only by detection.
 */
export default function SubscriptionsScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  const list = useSubscriptions();
  const summary = useSubscriptionSummary();
  const alerts = useSubscriptionAlerts();
  const scan = useScanSubscriptions();

  const unreadAlerts = alerts.data?.length ?? 0;

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="subscriptions-screen"
    >
      <View style={styles.header}>
        <MnEyebrow>Subscriptions</MnEyebrow>
        <View style={styles.headerActions}>
          <MnBtn
            label="Alerts"
            variant="ghost"
            onPress={() => router.push('/(tabs)/subscriptions/alerts')}
            testID="subscriptions-nav-alerts"
            accessibilityLabel={unreadAlerts > 0 ? `Alerts, ${unreadAlerts} unread` : 'Alerts'}
          />
          {unreadAlerts > 0 && (
            <View
              style={[styles.badge, { backgroundColor: theme.red }]}
              testID="subscriptions-alert-badge"
            >
              <Text style={styles.badgeText}>{unreadAlerts}</Text>
            </View>
          )}
          <MnBtn
            label="Scan now"
            variant="secondary"
            loading={scan.isPending}
            onPress={() => scan.mutate()}
            testID="subscriptions-scan-btn"
          />
        </View>
      </View>

      {summary.data !== undefined && (
        <View style={styles.summaryRow} testID="subscriptions-summary">
          {summary.data.byCurrency.length === 0 ? (
            <Text style={[styles.note, { color: theme.ink3 }]}>No active subscriptions yet.</Text>
          ) : (
            summary.data.byCurrency.map((totals) => (
              <MnCard
                key={totals.currency}
                style={styles.summaryCard}
                testID={`subscriptions-summary-${totals.currency}`}
              >
                <Text style={[styles.summaryLabel, { color: theme.ink3 }]}>
                  {totals.currency} · monthly
                </Text>
                <MoneyDisplay amount={totals.monthlyRunRate} currency={totals.currency} size="lg" />
                <Text style={[styles.summaryMeta, { color: theme.ink3 }]}>
                  {format.number(totals.activeCount)} active
                  {totals.flaggedCount > 0 ? `, ${format.number(totals.flaggedCount)} flagged` : ''}
                </Text>
              </MnCard>
            ))
          )}
        </View>
      )}

      {summary.data !== undefined && summary.data.next14Days.length > 0 && (
        <View style={styles.upcomingSection} testID="subscriptions-upcoming">
          <Text style={[styles.sectionLabel, { color: theme.ink2 }]}>Upcoming charges</Text>
          {summary.data.next14Days.map((day) => (
            <MnCard
              key={day.date}
              style={styles.upcomingCard}
              testID={`subscriptions-upcoming-${day.date}`}
            >
              <Text style={[styles.upcomingDate, { color: theme.ink }]}>
                {format.date(day.date, 'medium')}
              </Text>
              {day.subscriptions.map((sub) => (
                <Text
                  key={sub.id}
                  style={[styles.upcomingSub, { color: theme.ink3 }]}
                  testID={`subscriptions-upcoming-${day.date}-sub-${sub.id}`}
                >
                  {sub.merchantName}
                </Text>
              ))}
              <View style={styles.pair}>
                {day.totals.map((total) => (
                  <MoneyDisplay
                    key={total.currency}
                    amount={total.amount}
                    currency={total.currency}
                    size="sm"
                    testID={`subscriptions-upcoming-${day.date}-total-${total.currency}`}
                  />
                ))}
              </View>
            </MnCard>
          ))}
        </View>
      )}

      <ScreenState
        isLoading={list.isLoading}
        error={list.error}
        isEmpty={!list.isLoading && list.error == null && (list.data ?? []).length === 0}
        emptyMessage="No subscriptions detected yet. Try Scan now."
        fallbackMessage="Could not load your subscriptions."
        onRetry={() => void list.refetch()}
        testID="subscriptions-list"
      >
        <FlatList
          data={list.data ?? []}
          keyExtractor={(row) => row.id}
          contentContainerStyle={styles.list}
          renderItem={({ item }) => (
            <TouchableOpacity
              style={[styles.row, { borderBottomColor: theme.line }]}
              accessibilityRole="button"
              accessibilityLabel={`${item.merchantName}, ${SIGNAL_LABEL[item.signal]}`}
              onPress={() => router.push(`/(tabs)/subscriptions/${item.id}`)}
              testID={`subscription-row-${item.id}`}
            >
              <View style={styles.rowMain}>
                <Text style={[styles.merchant, { color: theme.ink }]}>{item.merchantName}</Text>
                <Text style={[styles.meta, { color: theme.ink3 }]}>
                  {CYCLE_LABEL[item.billingCycle]}
                  {item.nextChargeDate !== null ? ` · next ${item.nextChargeDate}` : ''}
                </Text>
              </View>
              <View style={styles.rowRight}>
                <MoneyDisplay amount={item.normalizedAmount} currency={item.currency} size="sm" />
                <MnPill label={SIGNAL_LABEL[item.signal]} variant={SIGNAL_VARIANT[item.signal]} />
              </View>
            </TouchableOpacity>
          )}
        />
      </ScreenState>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: Space.s4,
  },
  headerActions: { flexDirection: 'row', alignItems: 'center', gap: Space.s2 },
  badge: {
    minWidth: 20,
    height: 20,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 4,
  },
  badgeText: { color: '#fff', fontFamily: FontFamily.bodySemi, fontSize: FontSize.xs },
  summaryRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: Space.s3,
    paddingHorizontal: Space.s4,
    marginBottom: Space.s3,
  },
  summaryCard: { flexGrow: 1, gap: Space.s1 },
  summaryLabel: { fontFamily: FontFamily.bodySemi, fontSize: FontSize.xs, letterSpacing: 1 },
  summaryMeta: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
  upcomingSection: { paddingHorizontal: Space.s4, marginBottom: Space.s3, gap: Space.s2 },
  sectionLabel: { fontFamily: FontFamily.bodySemi, fontSize: FontSize.xs, letterSpacing: 1 },
  upcomingCard: { gap: Space.s1 },
  upcomingDate: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  upcomingSub: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  pair: { flexDirection: 'row', flexWrap: 'wrap', gap: Space.s3 },
  list: { paddingHorizontal: Space.s4 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: Space.s3,
    borderBottomWidth: 1,
    minHeight: 64,
  },
  rowMain: { flex: 1 },
  merchant: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md },
  meta: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
  rowRight: { alignItems: 'flex-end', gap: Space.s1 },
});
