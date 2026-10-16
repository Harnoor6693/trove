import React, { useState } from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnToggle } from '@components/ui/MnToggle/MnToggle';
import {
  useNotificationPreferences,
  useUnreadNotificationCount,
  useUpdateNotificationPreferences,
} from '@hooks/useNotifications';
import { toApiFailure, type ApiFailure } from '@api/errors';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import type {
  NotificationPreferencesResponse,
  NotificationPreferencesUpdateRequest,
} from '@api/types';

/**
 * The Notifications card on Settings: the unread badge and inbox entry point,
 * and the preferences form.
 *
 * **There is no push field anywhere on this card.** The server's own
 * `NotificationPreferencesResponse` carries none — no `pushEnabled`, no
 * device list — and this app polls {@link useUnreadNotificationCount} on
 * foreground and on an interval instead of registering for push (owner
 * decision, 2026-09-03: no push until a direct APNs/FCM path exists).
 */
export function NotificationsCard() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  const unread = useUnreadNotificationCount();
  const preferences = useNotificationPreferences();
  const update = useUpdateNotificationPreferences();

  const [inAppEnabled, setInAppEnabled] = useState(true);
  const [emailEnabled, setEmailEnabled] = useState(false);
  const [emailAddress, setEmailAddress] = useState('');
  const [weeklyDigestEnabled, setWeeklyDigestEnabled] = useState(true);
  const [subscriptionAlertEnabled, setSubscriptionAlertEnabled] = useState(true);
  const [syncFailureAlertEnabled, setSyncFailureAlertEnabled] = useState(true);
  const [budgetAlertThresholdPercent, setBudgetAlertThresholdPercent] = useState('90');
  const [saveError, setSaveError] = useState<unknown>(null);

  const loaded = preferences.data !== undefined;

  // Seed the form during render, the same pattern `AiSettingsCard` uses: an
  // effect that ran seven setters would flash the defaults for one render pass
  // before the real row arrived.
  const [seededFrom, setSeededFrom] = useState<NotificationPreferencesResponse | null>(null);
  if (preferences.data !== undefined && preferences.data !== seededFrom) {
    const row = preferences.data;
    setSeededFrom(row);
    setInAppEnabled(row.inAppEnabled);
    setEmailEnabled(row.emailEnabled);
    setEmailAddress(row.emailAddress ?? '');
    setWeeklyDigestEnabled(row.weeklyDigestEnabled);
    setSubscriptionAlertEnabled(row.subscriptionAlertEnabled);
    setSyncFailureAlertEnabled(row.syncFailureAlertEnabled);
    setBudgetAlertThresholdPercent(String(row.budgetAlertThresholdPercent));
  }

  const loadFailure =
    !loaded && preferences.error !== null && preferences.error !== undefined
      ? toApiFailure(preferences.error, 'Your notification preferences could not be loaded.')
      : null;

  const saveFailure: ApiFailure | null =
    saveError == null ? null : toApiFailure(saveError, 'Those preferences could not be saved.');

  const save = () => {
    if (!loaded) return;
    setSaveError(null);
    // `Number('')` is 0, so a cleared field must be told apart from a typed 0
    // here: blank means "leave the stored threshold alone", never "0 %".
    const rawPercent = budgetAlertThresholdPercent.trim();
    const percent = rawPercent === '' ? Number.NaN : Number(rawPercent);
    const body: NotificationPreferencesUpdateRequest = {
      inAppEnabled,
      emailEnabled,
      // An explicit null clears the stored address — the only way to unset it.
      emailAddress: emailAddress.trim() === '' ? null : emailAddress.trim(),
      weeklyDigestEnabled,
      subscriptionAlertEnabled,
      syncFailureAlertEnabled,
      ...(Number.isInteger(percent) ? { budgetAlertThresholdPercent: percent } : {}),
    };
    update.mutate(body, { onError: (err) => setSaveError(err) });
  };

  return (
    <View testID="settings-notifications-section">
      <MnEyebrow style={styles.section}>Notifications</MnEyebrow>
      <MnCard style={styles.card}>
        <View style={styles.inboxRow}>
          <View>
            <Text style={[styles.label, { color: theme.ink2 }]}>Inbox</Text>
            {unread.data !== undefined && (
              <Text
                style={[styles.note, { color: theme.ink3 }]}
                testID="settings-notifications-unread"
              >
                {unread.data.unread > 0
                  ? `${format.number(unread.data.unread)} unread`
                  : 'All caught up'}
              </Text>
            )}
          </View>
          <MnBtn
            label="Open inbox"
            variant="secondary"
            accessibilityLabel={
              unread.data !== undefined && unread.data.unread > 0
                ? `Open inbox, ${unread.data.unread} unread`
                : 'Open inbox'
            }
            onPress={() => router.push('/(tabs)/net-worth/notifications')}
            testID="settings-notifications-open-inbox"
          />
        </View>

        {!loaded && loadFailure !== null && (
          <Text
            style={[styles.note, { color: theme.red }]}
            accessibilityRole="alert"
            testID="settings-notifications-load-error"
          >
            {loadFailure.message}
          </Text>
        )}
        {!loaded && loadFailure === null && (
          <Text
            style={[styles.note, { color: theme.ink3 }]}
            testID="settings-notifications-loading"
          >
            Loading your notification preferences…
          </Text>
        )}

        {loaded && (
          <>
            <MnToggle
              label="In-app notifications"
              value={inAppEnabled}
              onToggle={setInAppEnabled}
              testID="settings-notifications-in-app"
            />
            <MnToggle
              label="Weekly digest"
              value={weeklyDigestEnabled}
              onToggle={setWeeklyDigestEnabled}
              testID="settings-notifications-weekly-digest"
            />
            <MnToggle
              label="Subscription price changes"
              value={subscriptionAlertEnabled}
              onToggle={setSubscriptionAlertEnabled}
              testID="settings-notifications-subscription-alerts"
            />
            <MnToggle
              label="Sync failures"
              value={syncFailureAlertEnabled}
              onToggle={setSyncFailureAlertEnabled}
              testID="settings-notifications-sync-alerts"
            />

            <MnToggle
              label="Email notifications"
              value={emailEnabled}
              onToggle={setEmailEnabled}
              testID="settings-notifications-email-enabled"
            />
            <Text style={[styles.note, { color: theme.ink3 }]}>
              The email channel is off on this install until an operator turns it on; enabling it
              here does nothing until then.
            </Text>
            <TextInput
              value={emailAddress}
              onChangeText={setEmailAddress}
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="email-address"
              placeholder="Your sign-in address (others are saved but not mailed)"
              placeholderTextColor={theme.ink3}
              accessibilityLabel="Notification email address"
              style={[styles.input, { borderColor: theme.line, color: theme.ink }]}
              testID="settings-notifications-email-address"
            />

            <Text style={[styles.label, { color: theme.ink2 }]}>Budget alert threshold (%)</Text>
            <Text style={[styles.note, { color: theme.ink3 }]}>
              Told when an envelope reaches this share of its allocation. Default 90.
            </Text>
            <TextInput
              value={budgetAlertThresholdPercent}
              onChangeText={setBudgetAlertThresholdPercent}
              keyboardType="number-pad"
              accessibilityLabel="Budget alert threshold percent"
              style={[styles.input, { borderColor: theme.line, color: theme.ink }]}
              testID="settings-notifications-threshold"
            />

            <MnBtn
              label="Save notification preferences"
              loading={update.isPending}
              onPress={save}
              style={styles.rowBtn}
              testID="settings-notifications-save"
            />

            {saveFailure !== null && (
              <Text
                style={[styles.note, { color: theme.red }]}
                accessibilityRole="alert"
                testID="settings-notifications-error"
              >
                {saveFailure.message}
              </Text>
            )}
          </>
        )}
      </MnCard>
    </View>
  );
}

const styles = StyleSheet.create({
  section: { marginTop: Space.s4 },
  card: { marginBottom: Space.s4, gap: Space.s2 },
  inboxRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  label: {
    fontFamily: FontFamily.bodySemi,
    fontSize: FontSize.xs,
    letterSpacing: 1,
    marginTop: Space.s3,
    textTransform: 'uppercase',
  },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginTop: Space.s1 },
  input: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Space.s3,
    marginTop: Space.s2,
    minHeight: 44,
  },
  rowBtn: { marginTop: Space.s3 },
});
