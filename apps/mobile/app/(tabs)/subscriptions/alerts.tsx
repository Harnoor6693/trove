import React from 'react';
import { FlatList, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { ScreenState } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import { useMarkSubscriptionAlertRead, useSubscriptionAlerts } from '@hooks/useSubscriptions';
import type { SubscriptionAlertResponse } from '@api/types';

/**
 * `/v1/subscriptions/alerts*` — unread price-change alerts. Never deleted;
 * acknowledging one only sets `isRead`, which is why this list simply shrinks
 * rather than the row disappearing from the ledger.
 */
export default function SubscriptionAlertsScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  const alerts = useSubscriptionAlerts();

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="subscription-alerts-screen"
    >
      <View style={styles.header}>
        <MnBtn label="Back" variant="ghost" onPress={() => router.back()} testID="alerts-back" />
        <MnEyebrow>Subscription alerts</MnEyebrow>
      </View>

      <ScreenState
        isLoading={alerts.isLoading}
        error={alerts.error}
        isEmpty={!alerts.isLoading && alerts.error == null && (alerts.data ?? []).length === 0}
        emptyMessage="No unread alerts."
        fallbackMessage="Could not load your alerts."
        onRetry={() => void alerts.refetch()}
        testID="subscription-alerts"
      >
        <FlatList
          data={alerts.data ?? []}
          keyExtractor={(alert) => alert.id}
          contentContainerStyle={styles.list}
          renderItem={({ item }) => <AlertRow alert={item} formatDate={format.date} />}
        />
      </ScreenState>
    </SafeAreaView>
  );
}

function AlertRow({
  alert,
  formatDate,
}: {
  alert: SubscriptionAlertResponse;
  formatDate: (iso: string | null | undefined, style?: 'short' | 'medium' | 'long') => string;
}) {
  const theme = useTheme();
  const markRead = useMarkSubscriptionAlertRead();

  return (
    <MnCard style={styles.card} testID={`alert-row-${alert.id}`}>
      <Text style={[styles.message, { color: theme.ink }]}>{alert.alertMessage}</Text>
      <Text style={[styles.meta, { color: theme.ink3 }]}>
        {formatDate(alert.createdAt, 'medium')}
      </Text>
      <MnBtn
        label="Mark read"
        variant="secondary"
        loading={markRead.isPending}
        onPress={() => markRead.mutate(alert.id)}
        testID={`alert-row-${alert.id}-mark-read`}
      />
    </MnCard>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  header: { padding: Space.s4, gap: Space.s2 },
  list: { paddingHorizontal: Space.s4, gap: Space.s3 },
  card: { gap: Space.s2, marginBottom: Space.s3 },
  message: { fontFamily: FontFamily.body, fontSize: FontSize.md },
  meta: { fontFamily: FontFamily.body, fontSize: FontSize.xs },
});
