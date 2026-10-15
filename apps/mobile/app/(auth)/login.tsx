import React, { useEffect, useState } from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
} from 'react-native';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { login } from '@api/auth';
import { fetchAuthProviders } from '@api/meta';
import { fetchMe } from '@api/me';
import { fetchPreferences } from '@api/preferences';
import { isUnauthorized, toApiFailure } from '@api/errors';
import { ErrorSlug, isTotpChallenge } from '@api/types';
import { logError } from '@lib/logError';
import { useAuthStore } from '@stores/authStore';

/**
 * Local email/password sign-in against the connected server (§4.1).
 *
 * Bearer mode: no `X-Trove-Auth-Mode: cookie` header, so the response carries
 * `{accessToken, refreshToken, expiresIn, user}` — the `user` object exists
 * precisely so this screen does not need a second round trip to populate the
 * session (§1.5).
 *
 * When the account has TOTP enabled the server answers with a 60-second
 * `challengeToken` instead of a session, and the flow continues on
 * `(auth)/totp`.
 *
 * The biometric button that used to live here is gone: unlocking a stored
 * session is now the app lock's job (`src/security/AppLock.tsx`), which runs
 * above the router and is reachable on a cold start, unlike the old branch.
 *
 * **Single sign-on is named here, not offered** (step 215, family E).
 * `getAuthProviders` advertises `oidc.startUrl` as an absolute URL precisely
 * so a client with no page origin can use it — but `startOidc` is a 302 into
 * Spring Security's browser authorization flow, which finishes by setting
 * session **cookies** on the server's own public origin. Completing it needs
 * two things this app does not have: a system-browser session with a redirect
 * back into the app (`expo-auth-session` + `expo-web-browser`, neither of
 * which is in `package.json`, and this step may not add a dependency), and a
 * documented way for a *bearer* client to collect a token from a flow that
 * ends in a cookie.
 *
 * So the screen says the server offers a provider and what to do about it,
 * and there is deliberately **no button**. `Linking.openURL(startUrl)` would
 * open the provider in the OS browser and strand the member there holding a
 * session this app can never see — a half-flow that looks like it works is
 * worse than an honest sentence. Both gaps are recorded as openIssues.
 */
export default function LoginScreen() {
  const theme = useTheme();
  const router = useRouter();
  const onAuthenticated = useAuthStore((s) => s.onAuthenticated);
  const setMe = useAuthStore((s) => s.setMe);
  const setPreferences = useAuthStore((s) => s.setPreferences);
  const serverHost = useAuthStore((s) => s.server?.baseUrl ?? null);

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [registrationOpen, setRegistrationOpen] = useState(false);
  /** The provider's own name when one is configured, else null. Never a button — see the note above. */
  const [oidcName, setOidcName] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void fetchAuthProviders().then((providers) => {
      if (cancelled) return;
      setRegistrationOpen(providers.registrationOpen);
      setOidcName(
        providers.oidc.enabled ? (providers.oidc.displayName ?? 'your identity provider') : null
      );
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleLogin = async () => {
    setError(null);
    setLoading(true);
    try {
      const result = await login({ email: email.trim(), password });
      if (isTotpChallenge(result)) {
        router.push({
          pathname: '/(auth)/totp',
          params: { challengeToken: result.challengeToken },
        });
        return;
      }
      onAuthenticated(result.user);
      // Currency/locale/timezone drive every amount and date the app renders,
      // so they are loaded before the first screen paints.
      try {
        setMe(await fetchMe());
        setPreferences(await fetchPreferences());
      } catch (err) {
        logError('LOGIN_PROFILE_LOAD_FAILED', err);
      }
      router.replace('/(tabs)/');
    } catch (err) {
      if (isUnauthorized(err)) {
        // The server is enumeration-safe (§1.5): an unknown email, a wrong
        // password and a soft-locked account are indistinguishable on 401,
        // so this copy must stay the same for all three.
        setError('Invalid email or password.');
      } else {
        logError('LOGIN_FAILED', err);
        const failure = toApiFailure(err, 'Sign-in failed. Try again.');
        // `account-locked` is the 429 rate-limit path only (§4.1 slugs); it
        // says nothing about whether the account exists, so naming it is safe.
        setError(
          failure.code === ErrorSlug.accountLocked
            ? 'Too many sign-in attempts. Wait a few minutes and try again.'
            : failure.message
        );
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <KeyboardAvoidingView
      style={[styles.flex, { backgroundColor: theme.paper }]}
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      testID="login-screen"
      // Fabric would otherwise mount the content as a sibling drawn over this
      // view, and Detox on iOS then reports the screen hidden (step 27.7).
      collapsable={false}
    >
      <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
        <Text style={[styles.wordmark, { color: theme.ink }]}>trove</Text>
        <Text style={[styles.title, { color: theme.ink }]}>Sign in</Text>
        <Text style={[styles.subtitle, { color: theme.ink3 }]}>
          {serverHost ?? 'Your quiet financial ledger.'}
        </Text>

        <TextInput
          testID="login-email-input"
          style={[
            styles.input,
            { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
          ]}
          placeholder="Email"
          placeholderTextColor={theme.ink3}
          value={email}
          onChangeText={setEmail}
          keyboardType="email-address"
          autoCapitalize="none"
          autoCorrect={false}
        />
        <TextInput
          testID="login-password-input"
          style={[
            styles.input,
            { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
          ]}
          placeholder="Password"
          placeholderTextColor={theme.ink3}
          value={password}
          onChangeText={setPassword}
          secureTextEntry
        />

        {error !== null && (
          <Text testID="login-error-text" style={[styles.error, { color: theme.red }]}>
            {error}
          </Text>
        )}

        <MnBtn
          testID="login-submit-btn"
          label="Sign In"
          onPress={handleLogin}
          loading={loading}
          fullWidth
        />

        {registrationOpen ? (
          <TouchableOpacity
            testID="create-account-link"
            style={styles.linkRow}
            onPress={() => router.push('/(auth)/setup')}
            accessibilityRole="link"
            accessibilityLabel="Set up a new Trove account"
          >
            <Text style={[styles.link, { color: theme.ink3 }]}>
              New to Trove? Set up your account →
            </Text>
          </TouchableOpacity>
        ) : (
          <Text
            testID="registration-closed-note"
            style={[styles.link, styles.linkRow, { color: theme.ink3 }]}
          >
            This server is invite-only. Ask an admin for an invite link.
          </Text>
        )}

        {oidcName !== null && (
          <Text
            testID="login-oidc-note"
            style={[styles.link, styles.linkRow, { color: theme.ink3 }]}
          >
            This server also signs people in with {oidcName}. Trove for phones can’t complete that
            sign-in yet — finish it in a web browser, then sign in here with your email and
            password.
          </Text>
        )}

        <TouchableOpacity
          testID="reset-password-link"
          style={styles.linkRow}
          onPress={() => router.push('/(auth)/reset-password')}
          accessibilityRole="link"
          accessibilityLabel="Use a password reset link"
        >
          <Text style={[styles.link, { color: theme.ink3 }]}>
            Have a reset link from your admin?
          </Text>
        </TouchableOpacity>

        <TouchableOpacity
          testID="change-server-link"
          style={styles.linkRow}
          onPress={() => router.replace('/(auth)/connect')}
          accessibilityRole="link"
          accessibilityLabel="Connect to a different Trove server"
        >
          <Text style={[styles.link, { color: theme.ink3 }]}>Use a different server</Text>
        </TouchableOpacity>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  container: { flexGrow: 1, justifyContent: 'center', padding: Space.s6 },
  wordmark: {
    fontFamily: FontFamily.displaySemi,
    fontSize: FontSize.xxl,
    textAlign: 'center',
    letterSpacing: -1,
    marginBottom: Space.s2,
  },
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
    marginBottom: Space.s8,
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
