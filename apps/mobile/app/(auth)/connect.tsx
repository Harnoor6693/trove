import React, { useState } from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
} from 'react-native';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnToggle } from '@components/ui/MnToggle/MnToggle';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { normaliseServerUrl } from '@api/serverUrl';
import { probeServer } from '@api/meta';
import { useAuthStore } from '@stores/authStore';
import { devDefaultBaseUrl } from '@api/serverConfig';

/**
 * First launch, and "Change server" from Settings.
 *
 * Every Trove user runs their own `trove-core` on their own domain, so a
 * sideloaded binary cannot ship a baked-in origin (readiness gap P4-19). This
 * screen is where the app learns its base URL, and it will not persist one
 * until three things hold:
 *
 *  1. The address normalises and passes the TLS policy (`serverUrl.ts`).
 *  2. `GET /api/v1/status` answers with a Trove-shaped body (§4.2).
 *  3. That server's version is one this app build speaks
 *     (`compatibility.ts`).
 *
 * `EXPO_PUBLIC_API_BASE_URL` is only a development convenience: it
 * pre-populates the field under `__DEV__` and is empty in release profiles.
 */
export default function ConnectScreen() {
  const theme = useTheme();
  const router = useRouter();
  const connectServer = useAuthStore((s) => s.connectServer);
  const currentBaseUrl = useAuthStore((s) => s.server?.baseUrl ?? null);

  const [address, setAddress] = useState(currentBaseUrl ?? devDefaultBaseUrl() ?? '');
  const [allowInsecureLan, setAllowInsecureLan] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const handleConnect = async () => {
    setError(null);
    const validated = normaliseServerUrl(address, allowInsecureLan);
    if (!validated.ok) {
      setError(validated.message);
      return;
    }

    setBusy(true);
    try {
      const probe = await probeServer(validated.url);
      if (!probe.ok) {
        setError(probe.message);
        return;
      }
      await connectServer({
        baseUrl: validated.url,
        allowInsecureLan: validated.scheme === 'http',
        meta: probe.meta,
      });
      router.replace('/(auth)/login');
    } finally {
      setBusy(false);
    }
  };

  return (
    <KeyboardAvoidingView
      style={[styles.flex, { backgroundColor: theme.paper }]}
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      testID="connect-screen"
      // Fabric would otherwise mount the content as a sibling drawn over this
      // view, and Detox on iOS then reports the screen hidden (step 27.7).
      collapsable={false}
    >
      <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
        <Text style={[styles.wordmark, { color: theme.ink }]}>trove</Text>
        <Text style={[styles.title, { color: theme.ink }]}>Connect to your server</Text>
        <Text style={[styles.subtitle, { color: theme.ink3 }]}>
          Trove runs on a server you host. Enter its address to get started.
        </Text>

        <TextInput
          testID="connect-url-input"
          style={[
            styles.input,
            { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
          ]}
          placeholder="trove.example.com"
          placeholderTextColor={theme.ink3}
          value={address}
          onChangeText={setAddress}
          keyboardType="url"
          autoCapitalize="none"
          autoCorrect={false}
          inputMode="url"
          accessibilityLabel="Trove server address"
        />

        <MnCard style={styles.card}>
          <MnToggle
            testID="connect-allow-http-toggle"
            label="Allow http on my network"
            value={allowInsecureLan}
            onToggle={setAllowInsecureLan}
          />
          <Text style={[styles.note, { color: theme.ink3 }]}>
            Only for addresses on your own network (localhost, 192.168.x.x, .local). Trove always
            requires https for a public server.
          </Text>
        </MnCard>

        {error !== null && (
          <Text testID="connect-error-text" style={[styles.error, { color: theme.red }]}>
            {error}
          </Text>
        )}

        <MnBtn
          testID="connect-submit-btn"
          label="Connect"
          onPress={handleConnect}
          loading={busy}
          fullWidth
        />

        <Text style={[styles.footnote, { color: theme.ink3 }]}>
          Self-signed certificates aren’t supported. Use Let’s Encrypt or a Tailscale certificate.
        </Text>
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
    marginBottom: Space.s6,
  },
  input: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Space.s4,
    marginBottom: Space.s4,
  },
  card: { marginBottom: Space.s4 },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginTop: Space.s2 },
  error: { fontFamily: FontFamily.body, fontSize: FontSize.sm, marginBottom: Space.s3 },
  footnote: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.xs,
    textAlign: 'center',
    marginTop: Space.s6,
  },
});
