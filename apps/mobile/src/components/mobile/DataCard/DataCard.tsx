import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';

/**
 * The **Data** section on Settings — step 215, family E.
 *
 * This replaces the `settings-data-deferred` placeholder the shipped tree
 * carried, which said export and deletion were "coming in a later build".
 * They are here now: `POST /v1/me/export` and `POST /v1/me/delete`.
 *
 * The card itself makes **no request**. Both actions are step-up operations
 * that need a password (and a code where a second factor is enrolled), and a
 * step-up form does not belong on a screen the member scrolls past — so each
 * is a stack screen of its own behind a button, and this card is the way in.
 * That also keeps the credential off the settings screen entirely.
 *
 * The deletion button is `danger` and says what it does in text above itself,
 * not only in its label: a destructive action whose consequence is only in the
 * verb is one somebody taps by reflex.
 */
export function DataCard() {
  const theme = useTheme();
  const router = useRouter();

  return (
    <View testID="settings-data-section">
      <MnEyebrow style={styles.section}>Data</MnEyebrow>
      <MnCard style={styles.card}>
        <Text style={[styles.note, { color: theme.ink3 }]} testID="settings-data-export-note">
          Export everything in your account as a ZIP archive — transactions, accounts, categories,
          budgets, goals, subscriptions, net worth and receipt details. Receipt images are not in
          the archive; download those from a receipt.
        </Text>
        <MnBtn
          testID="settings-export-btn"
          label="Export my data"
          variant="secondary"
          fullWidth
          style={styles.rowBtn}
          onPress={() => router.push('/(tabs)/account/export')}
        />

        <Text style={[styles.note, { color: theme.ink3 }]} testID="settings-delete-account-note">
          Deleting your account signs you out everywhere and disables it immediately. An
          administrator can restore it within seven days; after that it is erased for good. Your
          household keeps its shared ledger.
        </Text>
        <MnBtn
          testID="settings-delete-account-btn"
          label="Delete my account"
          variant="danger"
          fullWidth
          style={styles.rowBtn}
          onPress={() => router.push('/(tabs)/account/delete')}
        />
      </MnCard>
    </View>
  );
}

const styles = StyleSheet.create({
  section: { marginTop: Space.s4 },
  card: { marginBottom: Space.s4 },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginTop: Space.s3 },
  rowBtn: { marginTop: Space.s3 },
});
