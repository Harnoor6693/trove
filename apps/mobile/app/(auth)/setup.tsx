import React, { useState } from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnRadioCard } from '@components/ui/MnRadioCard/MnRadioCard';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { previewInvite, register } from '@api/auth';
import { tokenFromLink } from '@api/paths';
import { fetchMe } from '@api/me';
import { fetchPreferences } from '@api/preferences';
import { fieldError as fieldErrorOf, toApiFailure } from '@api/errors';
import { deviceTimeZone, useUserFormat } from '@lib/useUserFormat';
import { logError } from '@lib/logError';
import { useAuthStore } from '@stores/authStore';
import {
  ErrorSlug,
  type CountryCode,
  type CurrencyCode,
  type FieldErrorItem,
  type InvitePreviewResponse,
} from '@api/types';

/**
 * Account creation — `POST /v1/auth/register` (§4.1).
 *
 * Three things changed from the onboarding wizard this replaces (§4.13):
 *
 *  - It **reaches the server**. The old flow wrote a currency into SecureStore
 *    and never told anyone, so a mobile-onboarded user had no server-side
 *    currency at all.
 *  - **CAD and USD only.** GBP and EUR are gone (owner decision), and the
 *    region choice sets `currency` and `country` together — they are identity
 *    fields, fixed at registration and not editable afterwards (§4.2).
 *  - The bank-channel step (email forwarding was cut with IMAP ingestion) and
 *    the "Bank alerts" notification-permission step (cut with push) are both
 *    gone. Connecting an account happens after sign-up, from Accounts.
 *
 * Registration is closed by default (§1.5), so the invite token field is here
 * for the copy/paste invite links an admin generates.
 */

interface RegionOption {
  country: CountryCode;
  currency: CurrencyCode;
  label: string;
  description: string;
}

const REGIONS: ReadonlyArray<RegionOption> = [
  { country: 'CA', currency: 'CAD', label: 'Canada', description: 'Canadian dollars (CAD)' },
  { country: 'US', currency: 'USD', label: 'United States', description: 'US dollars (USD)' },
];

/**
 * Copy for the registration slugs §4.1 commits to. The server's own `detail`
 * is the fallback; these four are rewritten because the server phrases them
 * for an operator and this screen has to phrase them for the person holding
 * the phone.
 */
const REGISTER_ERROR_COPY: Record<string, string> = {
  [ErrorSlug.registrationClosed]:
    'This server is invite-only. Ask an admin for an invite link and paste it below.',
  [ErrorSlug.inviteInvalid]: 'That invite link isn’t valid. Ask your admin for a new one.',
  [ErrorSlug.inviteExpired]: 'That invite link has expired. Ask your admin for a new one.',
  [ErrorSlug.passwordPolicy]:
    'That password was rejected. Use 12–128 characters and avoid common passwords.',
};

export default function SetupScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  // `trove://setup?token=…` — the invite deep link. expo-router maps the app
  // scheme onto this route's search params, so it needs no linking code here.
  const params = useLocalSearchParams<{ token?: string }>();
  const onAuthenticated = useAuthStore((s) => s.onAuthenticated);
  const setMe = useAuthStore((s) => s.setMe);
  const setPreferences = useAuthStore((s) => s.setPreferences);

  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [inviteLink, setInviteLink] = useState(
    typeof params.token === 'string' ? params.token : ''
  );
  const [region, setRegion] = useState<RegionOption>(REGIONS[0]);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldErrorItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [preview, setPreview] = useState<InvitePreviewResponse | null>(null);

  const inviteToken = tokenFromLink(inviteLink);

  /**
   * Check the invitation before it is spent — `GET /v1/auth/invites/{token}`.
   *
   * On blur rather than on every keystroke: the member pastes a link, they do
   * not type one, and a per-character check would ask the server about eighty
   * near-misses of a live credential on the way to the real one.
   *
   * The route is **always 200** and the verdict is in the body, never in the
   * status — an unknown, revoked, used and expired token all answer
   * `{valid: false, email: null, expiresAt: null}` — so this branches on
   * `valid`, never on a caught error. A failed *request* (offline, 429) leaves
   * the preview unset: registration will still tell the truth, and a red
   * "invalid" caused by a dropped connection would send the member back to
   * their admin for a link that was fine.
   */
  const previewToken = async () => {
    if (inviteToken === '') {
      setPreview(null);
      return;
    }
    try {
      setPreview(await previewInvite(inviteToken));
    } catch {
      // Silent, and deliberately NOT logged. The token is a live credential in
      // the request PATH (`GET /v1/auth/invites/{token}`) and `sanitise()`
      // keeps the path verbatim, so logging a failed preview would write a
      // still-redeemable invitation into logcat/os_log on exactly the failures
      // that are not the member's fault (offline, 429, 5xx). The screen
      // already has its state, and registration still tells the truth.
      setPreview(null);
    }
  };

  const handleSubmit = async () => {
    setError(null);
    setFieldErrors([]);
    if (password.length < 12) {
      // Mirrors the server policy (12–128 characters, no composition rules,
      // §1.5) so the user is not bounced by a round trip.
      setError('Choose a password of at least 12 characters.');
      return;
    }
    setLoading(true);
    try {
      const session = await register({
        email: email.trim(),
        password,
        displayName: displayName.trim(),
        currency: region.currency,
        country: region.country,
        timezone: deviceTimeZone(),
        ...(inviteToken === '' ? {} : { inviteToken }),
      });
      onAuthenticated(session.user);
      try {
        setMe(await fetchMe());
        setPreferences(await fetchPreferences());
      } catch (err) {
        logError('REGISTER_PROFILE_LOAD_FAILED', err);
      }
      router.replace('/(tabs)/');
    } catch (err) {
      logError('REGISTER_FAILED', err);
      const failure = toApiFailure(err, 'Could not create your account.');
      setError(REGISTER_ERROR_COPY[failure.code ?? ''] ?? failure.message);
      setFieldErrors(failure.fieldErrors);
    } finally {
      setLoading(false);
    }
  };

  const fieldError = (field: string): string | null =>
    fieldErrorOf(
      {
        status: null,
        code: null,
        message: '',
        requestId: null,
        fieldErrors,
        retryAfterSeconds: null,
      },
      field
    );

  const inputStyle = [
    styles.input,
    { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
  ];

  return (
    <KeyboardAvoidingView
      style={[styles.flex, { backgroundColor: theme.paper }]}
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      testID="onboarding-screen"
      // Fabric would otherwise mount the content as a sibling drawn over this
      // view, and Detox on iOS then reports the screen hidden (step 27.7).
      collapsable={false}
    >
      <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
        <Text style={[styles.title, { color: theme.ink }]}>Set up your account</Text>
        <Text style={[styles.subtitle, { color: theme.ink3 }]}>
          This creates your account on the server you connected to.
        </Text>

        <MnEyebrow>You</MnEyebrow>
        <TextInput
          testID="setup-name-input"
          style={inputStyle}
          placeholder="Your name"
          placeholderTextColor={theme.ink3}
          value={displayName}
          onChangeText={setDisplayName}
          autoCapitalize="words"
          accessibilityLabel="Your name"
        />
        <TextInput
          testID="setup-email-input"
          style={inputStyle}
          placeholder="Email"
          placeholderTextColor={theme.ink3}
          value={email}
          onChangeText={setEmail}
          keyboardType="email-address"
          autoCapitalize="none"
          autoCorrect={false}
          accessibilityLabel="Email"
        />
        <TextInput
          testID="setup-password-input"
          style={inputStyle}
          placeholder="Password (12 characters or more)"
          placeholderTextColor={theme.ink3}
          value={password}
          onChangeText={setPassword}
          secureTextEntry
          accessibilityLabel="Password"
        />

        <MnEyebrow style={styles.section}>Region</MnEyebrow>
        <View testID="setup-region-options">
          {REGIONS.map((option) => (
            <MnRadioCard
              key={option.country}
              testID={`region-option-${option.country}`}
              label={option.label}
              description={option.description}
              selected={region.country === option.country}
              onSelect={() => setRegion(option)}
              style={styles.radio}
            />
          ))}
        </View>
        <Text style={[styles.note, { color: theme.ink3 }]}>
          Your currency is fixed once your account exists — every transaction, budget and export is
          reported in it.
        </Text>

        <MnEyebrow style={styles.section}>Invite</MnEyebrow>
        <TextInput
          testID="setup-invite-input"
          style={inputStyle}
          placeholder="Invite link or token (if your server requires one)"
          placeholderTextColor={theme.ink3}
          value={inviteLink}
          onChangeText={(value) => {
            setInviteLink(value);
            // The verdict belongs to the token that produced it.
            setPreview(null);
          }}
          onBlur={() => {
            void previewToken();
          }}
          autoCapitalize="none"
          autoCorrect={false}
          accessibilityLabel="Invite link or token"
        />

        {/* `previewInvite` is always 200 and puts the verdict in the body, so
            the branch is on `valid` — an unknown, revoked, used or expired
            token are one answer and the copy must not pretend to tell them
            apart. A bound invitation names the address it is bound to,
            because registering with a different one is refused. */}
        {preview !== null &&
          (preview.valid ? (
            <Text
              testID="setup-invite-valid"
              // React Native has no `status` role — `alert` is the only role
              // it offers that is announced when the text appears, and it is
              // what every other dynamic status line in this family uses.
              accessibilityRole="alert"
              style={[styles.note, { color: theme.sage }]}
            >
              {preview.email === null
                ? `This invitation is good until ${format.date(preview.expiresAt, 'medium')}.`
                : `This invitation is for ${preview.email} — sign up with that address. Good until ${format.date(preview.expiresAt, 'medium')}.`}
            </Text>
          ) : (
            <Text
              testID="setup-invite-invalid"
              accessibilityRole="alert"
              style={[styles.error, { color: theme.red }]}
            >
              That invitation can’t be used. Ask your admin for a new one.
            </Text>
          ))}

        {(['email', 'password', 'displayName', 'inviteToken'] as const).map((field) => {
          const message = fieldError(field);
          return message === null ? null : (
            <Text
              key={field}
              testID={`setup-field-error-${field}`}
              style={[styles.error, { color: theme.red }]}
            >
              {message}
            </Text>
          );
        })}

        {error !== null && (
          <Text testID="setup-error-text" style={[styles.error, { color: theme.red }]}>
            {error}
          </Text>
        )}

        <MnBtn
          testID="setup-submit-btn"
          label="Create account"
          onPress={handleSubmit}
          loading={loading}
          fullWidth
          style={styles.section}
        />

        <TouchableOpacity
          testID="setup-back-link"
          style={styles.linkRow}
          onPress={() => router.replace('/(auth)/login')}
          accessibilityRole="link"
          accessibilityLabel="Back to sign in"
        >
          <Text style={[styles.link, { color: theme.ink3 }]}>Back to sign in</Text>
        </TouchableOpacity>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  container: { flexGrow: 1, justifyContent: 'center', padding: Space.s6 },
  title: {
    fontFamily: FontFamily.displaySemi,
    fontSize: FontSize.xl,
    textAlign: 'center',
    marginBottom: Space.s2,
  },
  subtitle: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
    textAlign: 'center',
    marginBottom: Space.s6,
  },
  input: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Space.s4,
    marginBottom: Space.s3,
  },
  section: { marginTop: Space.s4 },
  radio: { marginBottom: Space.s2 },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginTop: Space.s1 },
  error: { fontFamily: FontFamily.body, fontSize: FontSize.sm, marginTop: Space.s2 },
  linkRow: { marginTop: Space.s4, alignItems: 'center' },
  link: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
});
