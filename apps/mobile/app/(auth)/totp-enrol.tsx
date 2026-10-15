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
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { confirmTotpEnrolment, startTotpEnrolment } from '@api/auth';
import { toApiFailure } from '@api/errors';
import { logError } from '@lib/logError';
import type { TotpSetup } from '@api/types';

/**
 * TOTP enrolment — `POST /v1/auth/totp/setup` then `/confirm` (§4.1, gap P2-6).
 *
 * Three steps, in the order the server requires:
 *  1. Re-authenticate with the current password. Enrolment is a step-up
 *     operation; a stolen unlocked phone must not be able to add a second
 *     factor the owner does not hold (§1.5).
 *  2. Add the secret to an authenticator app and confirm one live code.
 *  3. Record the ten single-use recovery codes, which are shown exactly once
 *     and stored SHA-256-hashed server-side.
 */
export default function TotpEnrolScreen() {
  const theme = useTheme();
  const router = useRouter();

  const [password, setPassword] = useState('');
  const [setup, setSetup] = useState<TotpSetup | null>(null);
  const [code, setCode] = useState('');
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const inputStyle = [
    styles.input,
    { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
  ];

  const begin = async () => {
    setError(null);
    setBusy(true);
    try {
      setSetup(await startTotpEnrolment(password));
      setPassword('');
    } catch (err) {
      logError('TOTP_SETUP_FAILED', err);
      setError(toApiFailure(err, 'Could not start two-factor setup.').message);
    } finally {
      setBusy(false);
    }
  };

  const confirm = async () => {
    setError(null);
    setBusy(true);
    try {
      const confirmed = await confirmTotpEnrolment(code.trim());
      setRecoveryCodes(confirmed.recoveryCodes);
      setCode('');
    } catch (err) {
      logError('TOTP_CONFIRM_FAILED', err);
      setError(toApiFailure(err, 'That code didn’t work.').message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <KeyboardAvoidingView
      style={[styles.flex, { backgroundColor: theme.paper }]}
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      testID="totp-enrol-screen"
    >
      <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
        <Text style={[styles.title, { color: theme.ink }]}>Two-factor authentication</Text>

        {error !== null && (
          <Text testID="totp-enrol-error" style={[styles.error, { color: theme.red }]}>
            {error}
          </Text>
        )}

        {recoveryCodes !== null ? (
          <MnCard testID="totp-enrol-recovery-codes">
            <Text style={[styles.subtitle, { color: theme.ink }]}>
              Save these recovery codes now
            </Text>
            <Text style={[styles.note, { color: theme.ink3 }]}>
              Each one works once, and they are not shown again.
            </Text>
            <View style={styles.codes}>
              {recoveryCodes.map((recoveryCode) => (
                <Text key={recoveryCode} style={[styles.code, { color: theme.ink }]}>
                  {recoveryCode}
                </Text>
              ))}
            </View>
            <MnBtn
              testID="totp-enrol-done-btn"
              label="Done"
              fullWidth
              onPress={() => router.back()}
            />
          </MnCard>
        ) : setup === null ? (
          <View testID="totp-enrol-password-step">
            <Text style={[styles.subtitle, { color: theme.ink3 }]}>
              Confirm your password to begin.
            </Text>
            <TextInput
              testID="totp-enrol-password-input"
              style={inputStyle}
              placeholder="Current password"
              placeholderTextColor={theme.ink3}
              value={password}
              onChangeText={setPassword}
              secureTextEntry
              accessibilityLabel="Current password"
            />
            <MnBtn
              testID="totp-enrol-begin-btn"
              label="Continue"
              fullWidth
              loading={busy}
              onPress={begin}
            />
          </View>
        ) : (
          <View testID="totp-enrol-confirm-step">
            <Text style={[styles.subtitle, { color: theme.ink3 }]}>
              Add this secret to your authenticator app, then enter the code it shows.
            </Text>
            <MnCard style={styles.card}>
              <Text
                testID="totp-enrol-secret"
                selectable
                style={[styles.code, { color: theme.ink }]}
              >
                {setup.secret}
              </Text>
            </MnCard>
            <TextInput
              testID="totp-enrol-code-input"
              style={inputStyle}
              placeholder="123456"
              placeholderTextColor={theme.ink3}
              value={code}
              onChangeText={setCode}
              autoCapitalize="none"
              autoComplete="one-time-code"
              accessibilityLabel="Two-factor code"
            />
            <MnBtn
              testID="totp-enrol-confirm-btn"
              label="Turn on two-factor"
              fullWidth
              loading={busy}
              onPress={confirm}
            />
          </View>
        )}

        <TouchableOpacity
          testID="totp-enrol-cancel-link"
          style={styles.linkRow}
          onPress={() => router.back()}
          accessibilityRole="link"
          accessibilityLabel="Cancel two-factor setup"
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
    marginBottom: Space.s4,
  },
  subtitle: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
    textAlign: 'center',
    marginBottom: Space.s4,
  },
  card: { marginBottom: Space.s3 },
  input: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Space.s4,
    marginBottom: Space.s3,
  },
  codes: { marginVertical: Space.s3 },
  code: { fontFamily: FontFamily.mono, fontSize: FontSize.md, textAlign: 'center' },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, textAlign: 'center' },
  error: { fontFamily: FontFamily.body, fontSize: FontSize.sm, marginBottom: Space.s3 },
  linkRow: { marginTop: Space.s4, alignItems: 'center' },
  link: { fontFamily: FontFamily.body, fontSize: FontSize.sm },
});
