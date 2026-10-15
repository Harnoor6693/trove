import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { MoneyDisplay } from '@components/ui/MoneyDisplay/MoneyDisplay';
import { ScreenState, failureMessage } from '@components/ui/ScreenState/ScreenState';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { toApiFailure } from '@api/errors';
import { isUuid } from '@api/paths';
import { useUserFormat } from '@lib/useUserFormat';
import { useCancelSubscription, useSubscription } from '@hooks/useSubscriptions';
import type { SubscriptionSignal } from '@api/types';

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
 * One detected subscription. `cancelSubscription` is a SOFT delete: the
 * confirmation says so in plain words, because a DELETE whose resource is
 * still readable afterwards — and whose merchant is never contacted — is not
 * what the verb usually means.
 */
export default function SubscriptionDetailScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  const { id } = useLocalSearchParams<{ id: string }>();
  // A pasted or deep-linked value that is not an id is a screen state rather
  // than a request (`@api/paths`'s own convention — see every other dynamic
  // route in this app).
  const subscriptionId = typeof id === 'string' && isUuid(id) ? id : '';
  const subscription = useSubscription(subscriptionId);
  const cancel = useCancelSubscription();
  const [confirming, setConfirming] = useState(false);

  const cancelFailure =
    cancel.error == null ? null : toApiFailure(cancel.error, 'Could not cancel this subscription.');

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="subscription-detail-screen"
    >
      <View style={styles.header}>
        <MnBtn
          label="Back"
          variant="ghost"
          onPress={() => router.back()}
          testID="subscription-back"
        />
        <MnEyebrow>Subscription</MnEyebrow>
      </View>

      <ScreenState
        isLoading={subscription.isLoading}
        error={subscription.error}
        fallbackMessage="Could not load this subscription."
        onRetry={() => void subscription.refetch()}
        testID="subscription-detail"
      >
        {subscription.data !== undefined && (
          <ScrollView contentContainerStyle={styles.content}>
            <MnCard style={styles.card}>
              <View style={styles.rowHeader}>
                <Text style={[styles.merchant, { color: theme.ink }]}>
                  {subscription.data.merchantName}
                </Text>
                {subscription.data.cancelled ? (
                  <MnPill label="Cancelled" variant="muted" />
                ) : (
                  <MnPill
                    label={SIGNAL_LABEL[subscription.data.signal]}
                    variant={SIGNAL_VARIANT[subscription.data.signal]}
                  />
                )}
              </View>

              <MoneyDisplay
                amount={subscription.data.normalizedAmount}
                currency={subscription.data.currency}
                size="xl"
                testID="subscription-amount"
              />
              {subscription.data.priceChanged && subscription.data.previousAmount !== null && (
                <Text
                  style={[styles.note, { color: theme.ink3 }]}
                  testID="subscription-previous-amount"
                >
                  Was{' '}
                  <MoneyDisplay
                    amount={subscription.data.previousAmount}
                    currency={subscription.data.currency}
                    size="sm"
                  />
                </Text>
              )}

              <Text style={[styles.label, { color: theme.ink2 }]}>Next charge</Text>
              <Text style={[styles.value, { color: theme.ink }]}>
                {subscription.data.nextChargeDate !== null
                  ? format.date(subscription.data.nextChargeDate, 'long')
                  : 'Unknown'}
              </Text>

              <Text style={[styles.label, { color: theme.ink2 }]}>First seen</Text>
              <Text style={[styles.value, { color: theme.ink }]}>
                {format.date(subscription.data.firstSeenDate, 'long')}
              </Text>

              <Text style={[styles.label, { color: theme.ink2 }]}>Last seen</Text>
              <Text style={[styles.value, { color: theme.ink }]}>
                {format.date(subscription.data.lastSeenDate, 'long')}
              </Text>
            </MnCard>

            {!subscription.data.cancelled && (
              <MnCard style={styles.card}>
                {confirming ? (
                  <>
                    <Text
                      style={[styles.note, { color: theme.ink2 }]}
                      testID="subscription-cancel-explainer"
                    >
                      This stops Trove tracking {subscription.data.merchantName} as a subscription.
                      Trove never contacts the merchant, and nothing is cancelled with them — if you
                      are still being charged, this only stops Trove telling you about it.
                    </Text>
                    <View style={styles.pair}>
                      <MnBtn
                        label="Confirm — stop tracking"
                        variant="danger"
                        style={styles.grow}
                        loading={cancel.isPending}
                        onPress={() =>
                          cancel.mutate(subscriptionId, { onSuccess: () => setConfirming(false) })
                        }
                        testID="subscription-cancel-confirm"
                      />
                      <MnBtn
                        label="Never mind"
                        variant="ghost"
                        style={styles.grow}
                        onPress={() => setConfirming(false)}
                        testID="subscription-cancel-abort"
                      />
                    </View>
                  </>
                ) : (
                  <MnBtn
                    label="Stop tracking this subscription"
                    variant="danger"
                    onPress={() => setConfirming(true)}
                    testID="subscription-cancel-start"
                  />
                )}
                {cancelFailure !== null && (
                  <Text
                    accessibilityRole="alert"
                    style={[styles.error, { color: theme.red }]}
                    testID="subscription-cancel-error"
                  >
                    {failureMessage(cancel.error, cancelFailure)}
                  </Text>
                )}
              </MnCard>
            )}
          </ScrollView>
        )}
      </ScreenState>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  header: { padding: Space.s4, gap: Space.s2 },
  content: { paddingHorizontal: Space.s4, paddingBottom: Space.s16, gap: Space.s3 },
  card: { gap: Space.s2 },
  rowHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  merchant: { fontFamily: FontFamily.bodySemi, fontSize: FontSize.lg },
  label: {
    fontFamily: FontFamily.bodySemi,
    fontSize: FontSize.xs,
    letterSpacing: 1,
    marginTop: Space.s2,
    textTransform: 'uppercase',
  },
  value: { fontFamily: FontFamily.body, fontSize: FontSize.md },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
  pair: { flexDirection: 'row', gap: Space.s2 },
  grow: { flex: 1 },
  error: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
});
