import React, { useState } from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { ErrorSlug, type StepUpRequest } from '@api/types';
import { fieldError, type ApiFailure } from '@api/errors';

/**
 * The step-up form — one component for the two operations that take a
 * `StepUpRequest`: `POST /v1/me/export` and `POST /v1/me/delete`.
 *
 * **Step-up is a security mechanism, not a confirmation dialog.** Holding a
 * session is exactly the credential these two operations exist to distrust: a
 * stolen unlocked phone has one. So the member proves the password, and the
 * code as well where a second factor is enrolled.
 *
 * Three rules this component exists to keep in one place:
 *
 *  1. **The credential never outlives the request.** Both fields are cleared
 *     the instant `onSubmit` is called — not on success, on *submit* — so a
 *     403 leaves the member retyping from empty rather than leaving a password
 *     sitting in component state while they read the refusal. Nothing here is
 *     written to the secure store, put in a query key, logged, or passed to
 *     anything that snapshots.
 *  2. **The factor set is the account's.** `StepUpRequest` has both fields
 *     optional because the server resolves what to demand from what the
 *     account actually has; this form sends `code` only when TOTP is enrolled,
 *     and `password` only when one was typed, rather than sending empty
 *     strings the server would have to interpret.
 *  3. **403 `step-up-required` reads as "that was not accepted"**, never as a
 *     generic failure and never as a claim about which of the two was wrong —
 *     the server does not say, deliberately.
 */
export interface StepUpFormProps {
  /**
   * From `MeResponse.totpEnabled`: whether a code is part of this account's
   * factor set — or `null` when `me` has not loaded, which is a real state
   * here because the profile is fetched best-effort and a failed fetch is
   * swallowed. `null` OFFERS the field rather than hiding it: hiding it from
   * an enrolled member turns a 403 into a dead end with nowhere to type the
   * code, while offering it to a member without a second factor costs one
   * optional field they leave empty.
   */
  totpEnabled: boolean | null;
  submitLabel: string;
  variant?: 'primary' | 'danger';
  pending?: boolean;
  /** The normalised refusal from the last attempt, or null. */
  failure?: ApiFailure | null;
  onSubmit: (body: StepUpRequest) => void;
  testID: string;
}

/** Copy for the slugs these two operations can really emit. */
function refusalCopy(failure: ApiFailure): string {
  if (failure.code === ErrorSlug.stepUpRequired) {
    return 'That password or code was not accepted.';
  }
  if (failure.status === 429) {
    const seconds = failure.retryAfterSeconds;
    return seconds === null
      ? 'Too many attempts. Wait a moment and try again.'
      : `Too many attempts. Try again in ${seconds} seconds.`;
  }
  return failure.message;
}

export function StepUpForm({
  totpEnabled,
  submitLabel,
  variant = 'primary',
  pending = false,
  failure = null,
  onSubmit,
  testID,
}: StepUpFormProps) {
  const theme = useTheme();
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');

  // Shown unless we know there is no second factor.
  const codeOffered = totpEnabled !== false;

  const submit = () => {
    const body: StepUpRequest = {
      ...(password === '' ? {} : { password }),
      ...(codeOffered && code.trim() !== '' ? { code: code.trim() } : {}),
    };
    // Cleared before the request is even in flight: there is no state for a
    // refusal, a re-render or a snapshot to find it in.
    setPassword('');
    setCode('');
    onSubmit(body);
  };

  const inputStyle = [
    styles.input,
    { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
  ];

  return (
    <View testID={testID}>
      <Text style={[styles.label, { color: theme.ink3 }]}>Current password</Text>
      <TextInput
        testID={`${testID}-password`}
        style={inputStyle}
        placeholder="Current password"
        placeholderTextColor={theme.ink3}
        value={password}
        onChangeText={setPassword}
        secureTextEntry
        autoCapitalize="none"
        autoCorrect={false}
        accessibilityLabel="Current password"
      />
      {failure !== null && fieldError(failure, 'password') !== null && (
        <Text
          testID={`${testID}-password-error`}
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
        >
          {fieldError(failure, 'password')}
        </Text>
      )}

      {codeOffered && (
        <>
          <Text style={[styles.label, { color: theme.ink3 }]}>
            {totpEnabled === null ? 'Two-factor code (if you use one)' : 'Two-factor code'}
          </Text>
          <TextInput
            testID={`${testID}-code`}
            style={inputStyle}
            placeholder="123456"
            placeholderTextColor={theme.ink3}
            value={code}
            onChangeText={setCode}
            keyboardType="number-pad"
            autoComplete="one-time-code"
            textContentType="oneTimeCode"
            autoCapitalize="none"
            autoCorrect={false}
            accessibilityLabel="Two-factor code"
          />
        </>
      )}

      {failure !== null && (
        <Text
          testID={`${testID}-error`}
          accessibilityRole="alert"
          style={[styles.error, { color: theme.red }]}
        >
          {refusalCopy(failure)}
        </Text>
      )}

      <MnBtn
        testID={`${testID}-submit`}
        label={submitLabel}
        variant={variant}
        fullWidth
        style={styles.submit}
        loading={pending}
        disabled={password === '' && !(codeOffered && code.trim() !== '')}
        onPress={submit}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  label: {
    fontFamily: FontFamily.bodySemi,
    fontSize: FontSize.xs,
    letterSpacing: 1,
    marginTop: Space.s3,
    textTransform: 'uppercase',
  },
  input: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Space.s3,
    marginTop: Space.s2,
    minHeight: 44,
  },
  error: { fontFamily: FontFamily.body, fontSize: FontSize.sm, marginTop: Space.s2 },
  submit: { marginTop: Space.s4 },
});
