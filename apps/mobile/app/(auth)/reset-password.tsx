import React, { useState } from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
} from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { redeemPasswordResetLink } from '@api/auth';
import { fieldError as fieldErrorOf, isRateLimited, toApiFailure } from '@api/errors';
import { logError } from '@lib/logError';
import { tokenFromLink } from '@api/paths';
import { ErrorSlug } from '@api/types';

/**
 * Set a new password with an admin-minted reset link — `POST
 * /v1/auth/password/reset` (step 215, family E).
 *
 * **This product has no member-facing "forgot password".** A self-hosted
 * install has no SMTP on a default deployment, so the contract has no public
 * route that mails anything: `requestPasswordResetLink` is ADMIN-scoped and
 * answers the admin with `{url, expiresAt}` — the link itself, to hand over
 * out of band. A public minting route with no second channel to deliver
 * through would just be a reset oracle. So the only member-facing half is this
 * one: redeem a link somebody gave you.
 *
 * **Where the token comes from.** `url` is `{publicUrl}/reset-password/{token}`
 * — a web address, because the server has one public origin and no idea an app
 * exists. A member on a phone therefore arrives here one of two ways:
 *
 *  - the app's own scheme, `trove://reset-password?token=…`, which expo-router
 *    maps onto this route's search params with no linking code of its own; or
 *  - pasting what they were sent, which may be the whole `https://…` link or
 *    just the token. `tokenFromLink` takes the last path segment of anything
 *    URL-shaped, so both work and the member is not asked to do string surgery
 *    on a credential.
 *
 * **One answer for three failures.** Unknown, expired and already-spent tokens
 * are the identical 400 `reset-invalid` precisely so the route cannot be used
 * to test guesses — so the copy here must not distinguish them either, however
 * much more helpful "that link has expired" would read.
 *
 * The password policy is checked **before** the token is consumed, so a
 * refused password leaves the link usable: this screen keeps the token and
 * lets the member try a different password rather than sending them back to
 * their admin for a second link.
 *
 * On success every session of that account is revoked, so there is nothing to
 * sign in with — the screen lands on login rather than pretending otherwise.
 */

/** Mirrors the server policy (12–128 characters, no composition rules, §1.5). */
const MIN_PASSWORD_LENGTH = 12;

export default function ResetPasswordScreen() {
  const theme = useTheme();
  const router = useRouter();
  const params = useLocalSearchParams<{ token?: string }>();
  const linkedToken = typeof params.token === 'string' ? params.token : '';

  const [link, setLink] = useState(linkedToken);
  // A newer link opened while this screen is showing (an admin minted a fresh
  // one) replaces the stale token; adjusted during render, not in an effect.
  const [seededToken, setSeededToken] = useState(linkedToken);
  if (linkedToken !== seededToken) {
    setSeededToken(linkedToken);
    if (linkedToken !== '') setLink(linkedToken);
  }
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);

  const token = tokenFromLink(link);

  const handleSubmit = async () => {
    setError(null);
    setPasswordError(null);

    if (token === '') {
      setError('Paste the reset link your administrator sent you.');
      return;
    }
    if (password.length < MIN_PASSWORD_LENGTH) {
      setPasswordError(`Choose a password of at least ${MIN_PASSWORD_LENGTH} characters.`);
      return;
    }
    if (password !== confirmation) {
      setPasswordError('Those two passwords don’t match.');
      return;
    }

    setLoading(true);
    try {
      await redeemPasswordResetLink({ token, password });
      // The new password is not kept for a courtesy sign-in: the server has
      // just revoked every session of this account, so there is nothing to
      // sign in *with*, and holding a password in state past its one use is
      // exactly the habit this family exists to remove.
      setPassword('');
      setConfirmation('');
      setDone(true);
    } catch (err) {
      const failure = toApiFailure(err, 'That didn’t work. Try again.');
      if (isRateLimited(failure)) {
        // A 429 is a wait-and-retry state with a real number on it, not a
        // generic failure — and never a reason to end a session.
        const wait = failure.retryAfterSeconds;
        setError(
          wait === null
            ? 'Too many attempts. Wait a little and try again.'
            : `Too many attempts. Try again in ${wait} seconds.`
        );
      } else if (failure.code === ErrorSlug.resetInvalid) {
        // Unknown, expired and already-used are one answer on purpose. Saying
        // which would turn this route into a way to test guesses.
        setError('That reset link can’t be used. Ask your administrator for a new one.');
      } else if (failure.code === ErrorSlug.passwordPolicy) {
        // Checked before the token is consumed, so the link is still good.
        setPasswordError(
          `That password was rejected. Use ${MIN_PASSWORD_LENGTH}–128 characters and avoid common passwords. Your link still works.`
        );
      } else if (failure.code === ErrorSlug.validationError) {
        setPasswordError(fieldErrorOf(failure, 'password'));
        setError(fieldErrorOf(failure, 'token') ?? failure.message);
      } else {
        logError('PASSWORD_RESET_FAILED', err);
        setError(failure.message);
      }
    } finally {
      setLoading(false);
    }
  };

  if (done) {
    return (
      <ScrollView
        style={[styles.flex, { backgroundColor: theme.paper }]}
        contentContainerStyle={styles.container}
        testID="reset-password-done"
      >
        <Text style={[styles.title, { color: theme.ink }]}>Password changed</Text>
        <Text style={[styles.subtitle, { color: theme.ink3 }]}>
          Your new password is set and every device signed in to this account has been signed out.
          Sign in again with the new password.
        </Text>
        <MnBtn
          testID="reset-password-signin-btn"
          label="Sign in"
          onPress={() => router.replace('/(auth)/login')}
          fullWidth
        />
      </ScrollView>
    );
  }

  return (
    <KeyboardAvoidingView
      style={[styles.flex, { backgroundColor: theme.paper }]}
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      testID="reset-password-screen"
    >
      <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
        <Text style={[styles.title, { color: theme.ink }]}>Set a new password</Text>
        <Text style={[styles.subtitle, { color: theme.ink3 }]}>
          Trove doesn’t send password emails. An administrator of your household creates a reset
          link and gives it to you; it works once and lasts an hour.
        </Text>

        <TextInput
          testID="reset-password-link-input"
          style={[
            styles.input,
            { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
          ]}
          placeholder="Paste your reset link"
          placeholderTextColor={theme.ink3}
          value={link}
          onChangeText={setLink}
          autoCapitalize="none"
          autoCorrect={false}
          accessibilityLabel="Reset link"
        />

        <TextInput
          testID="reset-password-input"
          style={[
            styles.input,
            { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
          ]}
          placeholder="New password"
          placeholderTextColor={theme.ink3}
          value={password}
          onChangeText={setPassword}
          secureTextEntry
          autoComplete="new-password"
          accessibilityLabel="New password"
        />
        <TextInput
          testID="reset-password-confirm-input"
          style={[
            styles.input,
            { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
          ]}
          placeholder="Repeat new password"
          placeholderTextColor={theme.ink3}
          value={confirmation}
          onChangeText={setConfirmation}
          secureTextEntry
          autoComplete="new-password"
          accessibilityLabel="Repeat new password"
        />

        {passwordError !== null && (
          <Text testID="reset-password-field-error" style={[styles.error, { color: theme.red }]}>
            {passwordError}
          </Text>
        )}
        {error !== null && (
          <Text testID="reset-password-error-text" style={[styles.error, { color: theme.red }]}>
            {error}
          </Text>
        )}

        <MnBtn
          testID="reset-password-submit-btn"
          label="Set password"
          onPress={handleSubmit}
          loading={loading}
          fullWidth
        />

        <TouchableOpacity
          testID="reset-password-cancel-link"
          style={styles.linkRow}
          onPress={() => router.replace('/(auth)/login')}
          accessibilityRole="link"
          accessibilityLabel="Cancel and return to sign in"
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
    fontSize: FontSize.sm,
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
  error: { fontFamily: FontFamily.body, fontSize: FontSize.sm, marginBottom: Space.s2 },
  linkRow: { marginTop: Space.s4, alignItems: 'center' },
  link: { fontFamily: FontFamily.body, fontSize: FontSize.sm, textAlign: 'center' },
});
