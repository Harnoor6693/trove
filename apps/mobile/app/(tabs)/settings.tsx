import React, { useEffect, useState } from 'react';
import { Linking, StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { MnToggle } from '@components/ui/MnToggle/MnToggle';
import { AiSettingsCard } from '@components/mobile/AiSettingsCard/AiSettingsCard';
import { CategoriesRulesCard } from '@components/mobile/CategoriesRulesCard/CategoriesRulesCard';
import { AdminCard } from '@components/mobile/AdminCard/AdminCard';
import { DataCard } from '@components/mobile/DataCard/DataCard';
import { NotificationsCard } from '@components/mobile/NotificationsCard/NotificationsCard';
import { WebhooksCard } from '@components/mobile/WebhooksCard/WebhooksCard';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { changePassword, disableTotp } from '@api/auth';
import { fetchMe, fetchSecurityEvents, updateMe } from '@api/me';
import { fetchPreferences, updatePreferences } from '@api/preferences';
import { fetchServerMeta } from '@api/meta';
import { toApiFailure } from '@api/errors';
import { ErrorSlug, type LocaleCode } from '@api/types';
import { appVersion } from '@api/meta';
import { logError } from '@lib/logError';
import { useUserFormat } from '@lib/useUserFormat';
import { useAuthStore } from '@stores/authStore';
import {
  APP_LOCK_TIMEOUT_CHOICES,
  useUiStore,
  type AppLockTimeout,
  type ThemePreference,
} from '@stores/uiStore';
import { FormScroll } from '@components/ui/FormScroll/FormScroll';

/**
 * `locale` is a closed set on the server — §4.2 states `locale ∈ {en-CA, en-US}`
 * — so it is a two-way choice, not the free-text field this screen used to
 * carry. A typed `fr-CA` would have been PUT straight at a 400.
 */
const LOCALES: ReadonlyArray<{ value: LocaleCode; label: string }> = [
  { value: 'en-CA', label: 'English (Canada)' },
  { value: 'en-US', label: 'English (US)' },
];

/** Copy for the mutation slugs §4.1 commits to; the server's `detail` is the fallback. */
const MUTATION_ERROR_COPY: Record<string, string> = {
  [ErrorSlug.stepUpRequired]:
    'That change needs a fresh sign-in. Enter your password and, if two-factor is on, a current code.',
  [ErrorSlug.passwordPolicy]:
    'That password was rejected. Use 12–128 characters and avoid common passwords.',
};

/**
 * Settings.
 *
 * Structure follows §4: **Server** (the connection this app is paired with),
 * **Account** (`/v1/auth/me`, `PUT /v1/me`, `POST /v1/auth/password`,
 * `/v1/auth/totp`), **Preferences** (`/v1/me/preferences`), **Security** (the
 * device app lock plus `GET /v1/me/security-events`), **Data**, **About**.
 *
 * Three contract details worth stating at the call site:
 *
 *  - `currency` and `country` are **read-only**. They live on `users`, are set
 *    once at registration and are deliberately not user-editable, because
 *    every historical transaction, budget and export assumes a stable
 *    reporting currency (§4.2).
 *  - `timezone` is written through `PUT /v1/me` (identity) and `locale`
 *    through `PUT /v1/me/preferences`, matching the field lists in §4.2.
 *  - **Data export and account deletion are wired now** (step 215). Both are
 *    step-up operations, so each lives on its own stack screen behind a
 *    button — `app/(tabs)/account/export` and `app/(tabs)/account/delete` —
 *    rather than as a form on a screen the member scrolls past. Deletion uses
 *    `POST /v1/me/delete`, the twin of `DELETE /v1/me`, because a DELETE that
 *    carries a body is undefined by RFC 9110 and intermediaries drop it.
 *
 * Every store read below selects a primitive. The object-returning selectors
 * this screen used to carry (`(s) => ({user: s.user, clearAuth: s.clearAuth})`)
 * built a new object on every call and re-rendered forever under zustand v5,
 * whose default equality is `Object.is`.
 */
/** The public repository — the AGPL-3.0 section 13 source offer. */
const SOURCE_CODE_URL = 'https://github.com/Harnoor6693/trove';

export default function SettingsScreen() {
  const theme = useTheme();
  const router = useRouter();
  const qc = useQueryClient();
  const format = useUserFormat();

  const serverBaseUrl = useAuthStore((s) => s.server?.baseUrl ?? null);
  const storedServerVersion = useAuthStore((s) => s.server?.meta?.version ?? null);
  const setMe = useAuthStore((s) => s.setMe);
  const setPreferences = useAuthStore((s) => s.setPreferences);
  const signOut = useAuthStore((s) => s.signOut);
  const disconnectServer = useAuthStore((s) => s.disconnectServer);

  const themePreference = useUiStore((s) => s.theme);
  const setThemePreference = useUiStore((s) => s.setTheme);
  const appLockEnabled = useUiStore((s) => s.appLockEnabled);
  const setAppLockEnabled = useUiStore((s) => s.setAppLockEnabled);
  const appLockTimeoutMinutes = useUiStore((s) => s.appLockTimeoutMinutes);
  const setAppLockTimeoutMinutes = useUiStore((s) => s.setAppLockTimeoutMinutes);

  const meQuery = useQuery({ queryKey: ['me'], queryFn: fetchMe });
  const preferencesQuery = useQuery({ queryKey: ['preferences'], queryFn: fetchPreferences });
  const securityEventsQuery = useQuery({
    queryKey: ['security-events'],
    queryFn: fetchSecurityEvents,
  });
  const serverMetaQuery = useQuery({ queryKey: ['server-meta'], queryFn: fetchServerMeta });

  const me = meQuery.data ?? null;
  const preferences = preferencesQuery.data ?? null;

  useEffect(() => {
    if (me !== null) setMe(me);
  }, [me, setMe]);
  useEffect(() => {
    if (preferences !== null) setPreferences(preferences);
  }, [preferences, setPreferences]);

  // Editable fields are **draft ?? server value**, not state seeded from an
  // effect. Copying a query result into state inside `useEffect` overwrites
  // whatever the user has already typed or tapped in the window between first
  // paint and the response — that is how a locale choice was silently reverted
  // — and it is the cascading-render pattern `react-hooks/set-state-in-effect`
  // exists to catch. A draft of `null` means "not edited: follow the server";
  // a successful save clears it so the field tracks the server again.
  const [displayNameDraft, setDisplayNameDraft] = useState<string | null>(null);
  const [timezoneDraft, setTimezoneDraft] = useState<string | null>(null);
  const [localeDraft, setLocaleDraft] = useState<LocaleCode | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const displayName = displayNameDraft ?? me?.displayName ?? '';
  // `/v1/me/preferences` is the authoritative read for `timezone` (§4.2);
  // `PUT /v1/me` is where it is written.
  const timezone = timezoneDraft ?? preferences?.timezone ?? me?.timezone ?? '';
  // `null` until the server has said which locale this user is on — no chip is
  // selected and Save is disabled until then, rather than showing a guess.
  const locale = localeDraft ?? preferences?.locale ?? null;

  function report(message: string) {
    setNotice(message);
    setError(null);
  }
  function fail(err: unknown, fallback: string) {
    logError('SETTINGS_MUTATION_FAILED', err);
    setNotice(null);
    const failure = toApiFailure(err, fallback);
    setError(MUTATION_ERROR_COPY[failure.code ?? ''] ?? failure.message);
  }

  const saveIdentity = useMutation({
    mutationFn: () => updateMe({ displayName: displayName.trim(), timezone: timezone.trim() }),
    onSuccess: (next) => {
      setMe(next);
      setDisplayNameDraft(null);
      setTimezoneDraft(null);
      void qc.invalidateQueries({ queryKey: ['me'] });
      report('Saved.');
    },
    onError: (err) => fail(err, 'Could not save your profile.'),
  });

  const saveLocale = useMutation({
    mutationFn: () => {
      if (locale === null) throw new Error('Preferences have not loaded yet.');
      return updatePreferences({ locale });
    },
    onSuccess: (next) => {
      setPreferences(next);
      setLocaleDraft(null);
      void qc.invalidateQueries({ queryKey: ['preferences'] });
      report('Saved.');
    },
    onError: (err) => fail(err, 'Could not save your preferences.'),
  });

  // --- password ------------------------------------------------------------
  const [showPasswordForm, setShowPasswordForm] = useState(false);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');

  const submitPassword = useMutation({
    mutationFn: () => changePassword({ currentPassword, newPassword }),
    onSuccess: () => {
      setCurrentPassword('');
      setNewPassword('');
      setShowPasswordForm(false);
      // §4.1: a password change revokes every other session.
      report('Password changed. Other devices have been signed out.');
    },
    onError: (err) => fail(err, 'Could not change your password.'),
  });

  // --- TOTP ----------------------------------------------------------------
  const [showTotpForm, setShowTotpForm] = useState(false);
  const [totpPassword, setTotpPassword] = useState('');
  const [totpCode, setTotpCode] = useState('');

  const submitDisableTotp = useMutation({
    mutationFn: () => disableTotp(totpPassword, totpCode),
    onSuccess: () => {
      setTotpPassword('');
      setTotpCode('');
      setShowTotpForm(false);
      void qc.invalidateQueries({ queryKey: ['me'] });
      report('Two-factor authentication is off.');
    },
    onError: (err) => fail(err, 'Could not turn off two-factor authentication.'),
  });

  const inputStyle = [
    styles.input,
    { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
  ];
  const labelStyle = [styles.label, { color: theme.ink3 }];
  const valueStyle = [styles.value, { color: theme.ink }];

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID="settings-screen">
      <FormScroll contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        {notice !== null && (
          <Text testID="settings-notice" style={[styles.notice, { color: theme.sage }]}>
            {notice}
          </Text>
        )}
        {error !== null && (
          <Text testID="settings-error" style={[styles.notice, { color: theme.red }]}>
            {error}
          </Text>
        )}

        {/* ---------------------------------------------------------- server */}
        <MnEyebrow>Server</MnEyebrow>
        <MnCard style={styles.card}>
          <Text style={labelStyle}>Connected to</Text>
          <Text testID="settings-server-url" style={valueStyle}>
            {serverBaseUrl ?? 'Not connected'}
          </Text>
          <Text style={labelStyle}>Server version</Text>
          <Text testID="settings-server-version" style={valueStyle}>
            {serverMetaQuery.data?.version ?? storedServerVersion ?? 'Unknown'}
          </Text>
          <MnBtn
            testID="settings-change-server-btn"
            label="Change server"
            variant="secondary"
            fullWidth
            style={styles.rowBtn}
            onPress={() => router.push('/(auth)/connect')}
          />
          <MnBtn
            testID="settings-disconnect-btn"
            label="Disconnect"
            variant="danger"
            fullWidth
            style={styles.rowBtn}
            onPress={() => {
              void disconnectServer().then(() => router.replace('/(auth)/connect'));
            }}
          />
        </MnCard>

        {/* --------------------------------------------------------- account */}
        <MnEyebrow style={styles.section}>Account</MnEyebrow>
        <MnCard style={styles.card}>
          <Text style={labelStyle}>Email</Text>
          <Text testID="settings-email" style={valueStyle}>
            {me?.email ?? '—'}
          </Text>

          <Text style={labelStyle}>Name</Text>
          <TextInput
            testID="settings-display-name-input"
            style={inputStyle}
            value={displayName}
            onChangeText={setDisplayNameDraft}
            autoCapitalize="words"
            accessibilityLabel="Display name"
          />

          <Text style={labelStyle}>Timezone</Text>
          <TextInput
            testID="settings-timezone-input"
            style={inputStyle}
            value={timezone}
            onChangeText={setTimezoneDraft}
            autoCapitalize="none"
            autoCorrect={false}
            accessibilityLabel="Timezone"
          />

          <MnBtn
            testID="settings-save-identity-btn"
            label="Save"
            fullWidth
            style={styles.rowBtn}
            loading={saveIdentity.isPending}
            onPress={() => saveIdentity.mutate()}
          />

          <MnBtn
            testID="settings-change-password-btn"
            label={showPasswordForm ? 'Cancel password change' : 'Change password'}
            variant="secondary"
            fullWidth
            style={styles.rowBtn}
            onPress={() => setShowPasswordForm((open) => !open)}
          />
          {showPasswordForm && (
            <View testID="settings-password-form">
              <TextInput
                testID="settings-current-password-input"
                style={inputStyle}
                placeholder="Current password"
                placeholderTextColor={theme.ink3}
                value={currentPassword}
                onChangeText={setCurrentPassword}
                secureTextEntry
                accessibilityLabel="Current password"
              />
              <TextInput
                testID="settings-new-password-input"
                style={inputStyle}
                placeholder="New password"
                placeholderTextColor={theme.ink3}
                value={newPassword}
                onChangeText={setNewPassword}
                secureTextEntry
                accessibilityLabel="New password"
              />
              <MnBtn
                testID="settings-submit-password-btn"
                label="Update password"
                fullWidth
                loading={submitPassword.isPending}
                onPress={() => submitPassword.mutate()}
              />
            </View>
          )}

          <View style={styles.inlineRow}>
            <Text style={valueStyle}>Two-factor authentication</Text>
            <MnPill
              testID="settings-totp-state"
              label={me?.totpEnabled === true ? 'On' : 'Off'}
              variant={me?.totpEnabled === true ? 'sage' : 'muted'}
            />
          </View>
          {me?.totpEnabled === true ? (
            <>
              <MnBtn
                testID="settings-totp-disable-btn"
                label={showTotpForm ? 'Cancel' : 'Turn off two-factor'}
                variant="secondary"
                fullWidth
                style={styles.rowBtn}
                onPress={() => setShowTotpForm((open) => !open)}
              />
              {showTotpForm && (
                <View testID="settings-totp-form">
                  <TextInput
                    testID="settings-totp-password-input"
                    style={inputStyle}
                    placeholder="Current password"
                    placeholderTextColor={theme.ink3}
                    value={totpPassword}
                    onChangeText={setTotpPassword}
                    secureTextEntry
                    accessibilityLabel="Current password for two-factor"
                  />
                  <TextInput
                    testID="settings-totp-code-input"
                    style={inputStyle}
                    placeholder="Current 6-digit code"
                    placeholderTextColor={theme.ink3}
                    value={totpCode}
                    onChangeText={setTotpCode}
                    autoCapitalize="characters"
                    accessibilityLabel="Two-factor code"
                  />
                  <MnBtn
                    testID="settings-totp-submit-btn"
                    label="Turn off"
                    variant="danger"
                    fullWidth
                    loading={submitDisableTotp.isPending}
                    onPress={() => submitDisableTotp.mutate()}
                  />
                </View>
              )}
            </>
          ) : (
            <MnBtn
              testID="settings-totp-enrol-btn"
              label="Set up two-factor"
              variant="secondary"
              fullWidth
              style={styles.rowBtn}
              onPress={() => router.push('/(auth)/totp-enrol')}
            />
          )}
        </MnCard>

        {/* ----------------------------------------------------- preferences */}
        <MnEyebrow style={styles.section}>Preferences</MnEyebrow>
        <MnCard style={styles.card}>
          <Text style={labelStyle}>Country and currency</Text>
          <Text testID="settings-currency" style={valueStyle}>
            {/* Guarded on the fields, not just on `me`: §4.1's `/v1/auth/me`
                table does not list `currency`/`country` (§4.2 and the session
                contract do), so a server that implements only the narrow body
                must render a dash here, never "undefined · undefined". */}
            {me?.country && me?.currency ? `${me.country} · ${me.currency}` : '—'}
          </Text>
          <Text style={[styles.note, { color: theme.ink3 }]}>
            Fixed when your account was created — every transaction, budget and export is reported
            in this currency.
          </Text>

          <Text style={labelStyle}>Locale</Text>
          <View style={styles.choices}>
            {LOCALES.map((option) => (
              <ChoiceChip
                key={option.value}
                testID={`settings-locale-${option.value}`}
                label={option.label}
                selected={locale === option.value}
                onPress={() => setLocaleDraft(option.value)}
              />
            ))}
          </View>
          <Text testID="settings-format-sample" style={[styles.note, { color: theme.ink3 }]}>
            {`Amounts look like ${format.money('1234.50')}`}
          </Text>
          <MnBtn
            testID="settings-save-locale-btn"
            label="Save locale"
            fullWidth
            style={styles.rowBtn}
            disabled={locale === null}
            loading={saveLocale.isPending}
            onPress={() => saveLocale.mutate()}
          />
        </MnCard>

        {/* ------------------------------------------------------ appearance */}
        <MnEyebrow style={styles.section}>Appearance</MnEyebrow>
        <MnCard style={styles.card}>
          <View style={styles.choices}>
            {(['system', 'light', 'dark'] as const).map((option) => (
              <ChoiceChip
                key={option}
                testID={`settings-theme-${option}`}
                label={option}
                selected={themePreference === option}
                onPress={() => void setThemePreference(option as ThemePreference)}
              />
            ))}
          </View>
        </MnCard>

        {/* -------------------------------------------------------- security */}
        <MnEyebrow style={styles.section}>Security</MnEyebrow>
        <MnCard style={styles.card}>
          <MnToggle
            testID="settings-app-lock-toggle"
            label="Require unlock"
            value={appLockEnabled}
            onToggle={(value) => void setAppLockEnabled(value)}
          />
          <Text style={[styles.note, { color: theme.ink3 }]}>
            Trove locks on launch, and after this long in the background.
          </Text>
          <View style={styles.choices}>
            {APP_LOCK_TIMEOUT_CHOICES.map((minutes) => (
              <ChoiceChip
                key={minutes}
                testID={`settings-app-lock-timeout-${minutes}`}
                label={minutes === 0 ? 'Immediately' : `${minutes} min`}
                selected={appLockTimeoutMinutes === minutes}
                onPress={() => void setAppLockTimeoutMinutes(minutes as AppLockTimeout)}
              />
            ))}
          </View>

          <Text style={[labelStyle, styles.section]}>Recent activity</Text>
          {securityEventsQuery.data === undefined ? (
            <Text style={[styles.note, { color: theme.ink3 }]} testID="settings-events-empty">
              {securityEventsQuery.isError ? 'Could not load recent activity.' : 'Loading…'}
            </Text>
          ) : (
            securityEventsQuery.data.slice(0, 5).map((event) => (
              <View key={event.id} style={styles.inlineRow} testID={`settings-event-${event.id}`}>
                <Text style={valueStyle}>{event.type}</Text>
                <Text style={[styles.note, { color: theme.ink3 }]}>
                  {format.date(event.at, 'dateTime')}
                </Text>
              </View>
            ))
          )}
        </MnCard>

        {/* --------------------------------------------------------------- ai
            Step 214, family D. The card is a component of its own
            (`src/components/mobile/AiSettingsCard`) rather than 300 lines
            inline: this screen is appended to by families C and E after this
            one, so the change here stays a single line, and `app/` is outside
            the coverage glob while `src/` is not. */}
        <AiSettingsCard />

        {/* ------------------------------------------------ categories/rules
            Step 212, family B. Same single-component-append pattern as
            `AiSettingsCard` above — the screens themselves live under the
            Budgets tab's stack, so this card is just the way in. */}
        <CategoriesRulesCard />

        {/* ------------------------------------------------------ notifications
            Step 213, family C. Same single-component-append pattern: the
            inbox itself is a screen under the Net Worth tab's stack, and this
            card is the way in, plus the preferences form and the unread
            badge. No push field anywhere — this app polls, it does not
            register for one. */}
        <NotificationsCard />

        {/* ----------------------------------------------------------- webhooks
            Step 213, family C. The secret is never echoed by the server, so
            this card never holds one past the moment it is typed. */}
        <WebhooksCard />

        {/* ------------------------------------------------------------ data
            Step 215, family E. This replaces the `settings-data-deferred`
            placeholder: `POST /v1/me/export` and `POST /v1/me/delete` are both
            live now. Both are step-up operations, so each is a stack screen of
            its own behind a button rather than a form on a screen the member
            scrolls past — which also keeps the credential off this screen. */}
        <DataCard />

        {/* --------------------------------------------------------- admin
            Step 215, family E. Visible only to an ADMIN, and that gate is
            cosmetic: every route behind it is ADMIN-scoped server-side and a
            MEMBER who reaches the console sees a clean 403 state. */}
        <AdminCard />

        {/* ----------------------------------------------------------- about */}
        <MnEyebrow style={styles.section}>About</MnEyebrow>
        <MnCard style={styles.card}>
          <Text style={labelStyle}>App version</Text>
          <Text testID="settings-app-version" style={valueStyle}>
            {appVersion()}
          </Text>
          {/* AGPL-3.0 section 13: a visible offer of the source to every user. */}
          <TouchableOpacity
            testID="settings-source-code"
            accessibilityRole="link"
            accessibilityLabel="Source code, opens GitHub"
            onPress={() => void Linking.openURL(SOURCE_CODE_URL)}
            style={styles.sourceRow}
          >
            <Text style={labelStyle}>Source code</Text>
            <Text style={[valueStyle, { color: theme.sage }]}>github.com/Harnoor6693/trove</Text>
            <Text style={labelStyle}>Trove is free software under the AGPL-3.0.</Text>
          </TouchableOpacity>
        </MnCard>

        <MnBtn
          testID="sign-out-btn"
          label="Sign Out"
          variant="danger"
          fullWidth
          style={styles.signOut}
          onPress={() => {
            void signOut().then(() => router.replace('/(auth)/login'));
          }}
        />
      </FormScroll>
    </SafeAreaView>
  );
}

function ChoiceChip({
  label,
  selected,
  onPress,
  testID,
}: {
  label: string;
  selected: boolean;
  onPress: () => void;
  testID: string;
}) {
  const theme = useTheme();
  return (
    <TouchableOpacity
      testID={testID}
      onPress={onPress}
      accessibilityRole="radio"
      accessibilityState={{ selected }}
      accessibilityLabel={label}
      style={[
        styles.chip,
        {
          borderColor: selected ? theme.sage : theme.line,
          backgroundColor: selected ? theme.sageSoft : 'transparent',
        },
      ]}
    >
      <Text style={[styles.chipText, { color: selected ? theme.sage : theme.ink2 }]}>{label}</Text>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, paddingBottom: Space.s16 },
  card: { marginBottom: Space.s4 },
  section: { marginTop: Space.s4 },
  label: {
    fontFamily: FontFamily.bodySemi,
    fontSize: FontSize.xs,
    letterSpacing: 1,
    marginTop: Space.s3,
    textTransform: 'uppercase',
  },
  value: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginTop: Space.s1 },
  notice: { fontFamily: FontFamily.body, fontSize: FontSize.sm, marginBottom: Space.s3 },
  input: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Space.s3,
    marginTop: Space.s2,
  },
  rowBtn: { marginTop: Space.s3 },
  inlineRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: Space.s3,
  },
  choices: { flexDirection: 'row', flexWrap: 'wrap', gap: Space.s2, marginTop: Space.s2 },
  chip: {
    paddingHorizontal: Space.s3,
    paddingVertical: Space.s2,
    borderRadius: Radius.md,
    borderWidth: 1,
  },
  chipText: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
  signOut: { marginTop: Space.s8 },
  sourceRow: { marginTop: Space.s3, minHeight: 44 },
});
