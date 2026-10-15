import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { toApiFailure } from '@api/errors';
import { ErrorSlug } from '@api/types';

/**
 * The role gate for the admin console — **the server's gate, rendered**.
 *
 * Every `/v1/admin/*` route is `ADMIN`-scoped server-side and answers a MEMBER
 * with 403 `forbidden`. The card that hides the console entry point is
 * cosmetic; this is what happens when somebody reaches the screen anyway — a
 * deep link, a stale navigation state, a member whose role was changed while
 * the app was open. A clean state, not an error toast and not a spinner that
 * never resolves.
 *
 * `forbidden` is distinguished from `step-up-required`, which shares the 403:
 * on an admin route that one means a SELF target, which is a per-action
 * refusal on a screen the caller is entitled to see, not "you may not be
 * here".
 */
export function AdminOnly({ error, children }: { error: unknown; children: React.ReactNode }) {
  const theme = useTheme();
  if (error == null) return <>{children}</>;

  const failure = toApiFailure(error, 'Could not load this.');
  if (failure.status !== 403 || failure.code !== ErrorSlug.forbidden) return <>{children}</>;

  return (
    <View testID="admin-forbidden">
      <MnCard style={styles.card}>
        <Text
          accessibilityRole="alert"
          style={[styles.title, { color: theme.ink }]}
          testID="admin-forbidden-title"
        >
          Administrators only
        </Text>
        <Text style={[styles.note, { color: theme.ink3 }]}>
          This part of Trove is for your household&rsquo;s administrators. Ask one of them if you
          need something changed here.
        </Text>
      </MnCard>
    </View>
  );
}

const styles = StyleSheet.create({
  card: { marginBottom: Space.s4 },
  title: { fontFamily: FontFamily.bodySemi, fontSize: FontSize.md },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginTop: Space.s2 },
});
