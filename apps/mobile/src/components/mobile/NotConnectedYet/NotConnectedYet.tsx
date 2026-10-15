import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';

/**
 * The empty state for a tab whose screen is being rebuilt in step 21b.
 *
 * Step 21a deleted the hooks behind Today, Transactions, Budgets, Net Worth,
 * Subscriptions and Coach: they were typed against flat, cents-based shapes
 * (`Budget{limitCents, spentCents}`, `Transaction{amountCents}`,
 * `Subscription{billingCycle:'annual'}`) that no `trove-core` DTO returns, and
 * §4.13 requires `src/api/types.ts` to be rewritten wholesale against the real
 * server DTOs rather than patched. Rewriting the screens on those DTOs is
 * step 21b's job; the tab stays so the information architecture does not
 * churn twice.
 */
export function NotConnectedYet({
  title,
  detail,
  testID,
}: {
  title: string;
  detail?: string;
  testID?: string;
}) {
  const theme = useTheme();
  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID={testID}>
      <View style={styles.content}>
        <MnEyebrow>{title}</MnEyebrow>
        <Text style={[styles.headline, { color: theme.ink }]}>Not connected yet</Text>
        <Text style={[styles.body, { color: theme.ink3 }]}>
          {detail ?? 'This screen is rebuilt against the server contract in the next build (21b).'}
        </Text>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: Space.s6 },
  headline: {
    fontFamily: FontFamily.displaySemi,
    fontSize: FontSize.lg,
    marginTop: Space.s2,
    marginBottom: Space.s2,
    textAlign: 'center',
  },
  body: { fontFamily: FontFamily.body, fontSize: FontSize.sm, textAlign: 'center' },
});
