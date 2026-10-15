import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { StepUpForm } from '@components/mobile/StepUpForm/StepUpForm';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useDeleteMyAccount } from '@hooks/useDataExport';
import { clearSession } from '@api/client';
import { toApiFailure } from '@api/errors';
import { ErrorSlug } from '@api/types';
import { signedOutState, useAuthStore } from '@stores/authStore';

/**
 * Delete your own account — `POST /v1/me/delete`.
 *
 * **The POST twin, not `DELETE /v1/me`.** A DELETE carrying a body is legal
 * but undefined by RFC 9110, and intermediaries drop it without saying so;
 * this body carries the step-up, so a dropped one turns an irreversible
 * decision into an unexplained 403.
 *
 * **Everything on this screen is sourced from the operation's own
 * description**, because a member cannot find any of it out afterwards:
 *
 *  - The account is disabled immediately and every session of it ends.
 *  - An administrator can restore it inside a seven-day grace.
 *  - After the grace it is purged for good, and the address stays reserved
 *    until that purge runs.
 *  - The **household keeps the shared ledger**, with this member's authorship
 *    removed; security-event history is kept with the user id removed.
 *  - What goes with the member: their password, second factor, identity links,
 *    sessions, reset links, AI settings, notification preferences and inbox,
 *    webhooks, coach conversations and insights, tax-line overrides, and every
 *    export run with its file.
 *
 * **No countdown and no member-facing restore.** Restore is the admin's route
 * (`POST /v1/admin/users/{id}/restore`); inventing a "you have seven days,
 * tap here" control would promise the member something this app cannot do.
 *
 * On success the app calls its own `clearSession()` — the access token, the
 * refresh token and the user leave the secure store — and lands on connect or
 * login. The server has already revoked every session, so there is nothing
 * left for a graceful sign-out to revoke.
 */
export default function DeleteAccountScreen() {
  const theme = useTheme();
  const router = useRouter();
  // `null` = not known yet: `me` is fetched best-effort and both `login.tsx`
  // and `AppLock` swallow a failed fetch. `false` there would hide the code
  // field from an enrolled member and strand them on a 403 they cannot answer.
  const totpEnabled = useAuthStore((s) => s.me?.totpEnabled ?? null);
  const serverConnected = useAuthStore((s) => s.server !== null);
  const remove = useDeleteMyAccount();

  const [confirmed, setConfirmed] = useState(false);
  // Same rule as the export screen: the refusal is copied out and the mutation
  // is reset, so the password does not outlive the request inside
  // `mutation.variables` while the member reads why it was refused.
  const [error, setError] = useState<unknown>(null);

  const failure = error == null ? null : toApiFailure(error, 'Could not delete your account.');
  // The household's last ADMIN is refused, and that is an explanation rather
  // than an error: give another member the admin role first.
  const lastAdmin = failure?.code === ErrorSlug.conflict;

  const submit = (body: Parameters<typeof remove.mutate>[0]) => {
    setError(null);
    remove.mutate(body, {
      onSuccess: async () => {
        // The server has already revoked every session of this account, so
        // there is nothing for a graceful sign-out to revoke — the right move
        // is to drop the tokens locally and leave.
        await clearSession();
        // The store's own signed-out transition, not a partial patch: a
        // `setState` that touched only `status` would merge, leaving the
        // deleted member's `user`, `me` and `preferences` in memory for the
        // next sign-in to render if its own profile fetch failed.
        useAuthStore.setState(signedOutState(useAuthStore.getState().server));
        router.replace(serverConnected ? '/(auth)/login' : '/(auth)/connect');
      },
      onError: (err) => setError(err),
      onSettled: () => remove.reset(),
    });
  };

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID="delete-screen">
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text style={[styles.title, { color: theme.ink }]}>Delete your account</Text>

        <MnEyebrow style={styles.section}>What happens</MnEyebrow>
        <MnCard style={styles.card}>
          <Text style={[styles.note, { color: theme.ink3 }]} testID="delete-immediate">
            Your account is disabled the moment you confirm, and every session of it ends — this one
            included.
          </Text>
          <Text style={[styles.note, { color: theme.ink3 }]} testID="delete-grace">
            An administrator of your household can restore it for seven days. After that it is
            erased permanently, and your email address stays reserved until that erasure runs.
          </Text>
          <Text style={[styles.note, { color: theme.ink3 }]} testID="delete-household-keeps">
            Your household keeps its shared ledger — transactions, accounts, categories, rules,
            budgets, envelopes, goals, net worth, subscriptions and receipts, images included — with
            your name removed from the rows that recorded who entered them.
          </Text>
          <Text style={[styles.note, { color: theme.ink3 }]} testID="delete-goes-with-you">
            Erased with you: your password, two-factor secret, sign-in links, AI settings,
            notification preferences and inbox, webhooks, coach conversations, your tax-line
            overrides, and every data export you have made.
          </Text>
        </MnCard>

        {!confirmed ? (
          <MnBtn
            testID="delete-begin-btn"
            label="I understand — continue"
            variant="secondary"
            fullWidth
            style={styles.section}
            onPress={() => setConfirmed(true)}
          />
        ) : (
          <>
            <MnEyebrow style={styles.section}>Confirm it&rsquo;s you</MnEyebrow>
            <MnCard style={styles.card}>
              <StepUpForm
                testID="delete-step-up"
                totpEnabled={totpEnabled}
                submitLabel="Delete my account"
                variant="danger"
                pending={remove.isPending}
                failure={failure}
                onSubmit={submit}
              />
              {lastAdmin && (
                <Text
                  testID="delete-last-admin"
                  accessibilityRole="alert"
                  style={[styles.note, { color: theme.red }]}
                >
                  You are your household&rsquo;s only administrator, so this account can&rsquo;t be
                  deleted yet — an install with no administrator has no way back in. Give another
                  member the admin role first, then try again.
                </Text>
              )}
            </MnCard>
          </>
        )}

        <MnBtn
          testID="delete-cancel-btn"
          label="Keep my account"
          fullWidth
          style={styles.section}
          onPress={() => router.back()}
        />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, paddingBottom: Space.s16 },
  title: { fontFamily: FontFamily.displaySemi, fontSize: FontSize.xl, marginBottom: Space.s2 },
  section: { marginTop: Space.s4 },
  card: { marginBottom: Space.s4 },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginTop: Space.s2 },
});
