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
import { loginWithTotp } from '@api/auth';
import { fetchMe } from '@api/me';
import { fetchPreferences } from '@api/preferences';
import { isUnauthorized, toApiFailure } from '@api/errors';
import { logError } from '@lib/logError';
import { useAuthStore } from '@stores/authStore';

/**
 * Second factor — `POST /v1/auth/login/totp` (§4.1 / §1.5).
 *
 * The `challengeToken` handed over from the login screen is a 60-second JWT
 * with `typ=totp_challenge`; the server rejects it on every other route, so it
 * cannot be used as an access token if it leaks. It is passed as a navigation
 * param and never persisted.
 *
 * The same field accepts a 6-digit code or one of the ten single-use recovery
 * codes, so the input is not digit-constrained.
 */
export default function TotpScreen() {
  const theme = useTheme();
  const router = useRouter();
  const params = useLocalSearchParams<{ challengeToken?: string }>();
  const challengeToken = typeof params.challengeToken === 'string' ? params.challengeToken : '';

  const onAuthenticated = useAuthStore((s) => s.onAuthenticated);
  const setMe = useAuthStore((s) => s.setMe);
  const setPreferences = useAuthStore((s) => s.setPreferences);

  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async () => {
    setError(null);
    if (challengeToken === '') {
      setError('That sign-in attempt expired. Start again.');
      return;
    }
    setLoading(true);
    try {
      const session = await loginWithTotp({ challengeToken, code: code.trim() });
      onAuthenticated(session.user);
      try {
        setMe(await fetchMe());
        setPreferences(await fetchPreferences());
      } catch (err) {
        logError('TOTP_PROFILE_LOAD_FAILED', err);
      }
      router.replace('/(tabs)/');
    } catch (err) {
      if (isUnauthorized(err)) {
        setError('That code didn’t work. Codes expire after 30 seconds.');
      } else {
        logError('TOTP_LOGIN_FAILED', err);
        setError(toApiFailure(err, 'Verification failed. Try again.').message);
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <KeyboardAvoidingView
      style={[styles.flex, { backgroundColor: theme.paper }]}
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      testID="totp-screen"
    >
      <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
        <Text style={[styles.title, { color: theme.ink }]}>Two-factor code</Text>
        <Text style={[styles.subtitle, { color: theme.ink3 }]}>
          Enter the 6-digit code from your authenticator app, or one of your recovery codes.
        </Text>

        <TextInput
          testID="totp-code-input"
          style={[
            styles.input,
            { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
          ]}
          placeholder="123456"
          placeholderTextColor={theme.ink3}
          value={code}
          onChangeText={setCode}
          autoCapitalize="characters"
          autoCorrect={false}
          autoComplete="one-time-code"
          accessibilityLabel="Two-factor code"
        />

        {error !== null && (
          <Text testID="totp-error-text" style={[styles.error, { color: theme.red }]}>
            {error}
          </Text>
        )}

        <MnBtn
          testID="totp-submit-btn"
          label="Verify"
          onPress={handleSubmit}
          loading={loading}
          fullWidth
        />

        <TouchableOpacity
          testID="totp-cancel-link"
          style={styles.linkRow}
          onPress={() => router.replace('/(auth)/login')}
          accessibilityRole="link"
          accessibilityLabel="Cancel and return to sign in"
        >
          <Text style={[styles.link, { color: theme.ink3 }]}>Cancel</Text>
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
    fontFamily: FontFamily.mono,
    fontSize: FontSize.lg,
    letterSpacing: 4,
    textAlign: 'center',
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Space.s4,
    marginBottom: Space.s3,
  },
  error: { fontFamily: FontFamily.body, fontSize: FontSize.sm, marginBottom: Space.s2 },
  linkRow: { marginTop: Space.s4, alignItems: 'center' },
  link: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
});
