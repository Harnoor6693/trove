import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { FontFamily, FontSize, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useAuthStore } from '@stores/authStore';

/**
 * The **Administration** card on Settings — step 215, family E.
 *
 * The role comes from the server's own `GET /v1/auth/me` (`MeResponse.role`),
 * held in the auth store, and **this gate is cosmetic**. Hiding a card is a
 * tidiness measure, not a control: every route behind it is `ADMIN`-scoped
 * server-side and answers a MEMBER with 403 `forbidden`, and the console
 * renders that refusal as a clean state rather than an error. A client-side
 * role check that were the only gate would be a role check an attacker edits
 * out of their own copy of the app.
 *
 * Rendered as `null` for a MEMBER, and for the window before `me` has loaded —
 * showing an admin entry point optimistically and then snatching it away is
 * worse than showing it a second late.
 */
export function AdminCard() {
  const theme = useTheme();
  const router = useRouter();
  // A primitive selector: a selector that builds an object re-renders forever
  // under zustand v5, whose default equality is `Object.is`.
  const role = useAuthStore((s) => s.me?.role ?? null);

  if (role !== 'ADMIN') return null;

  return (
    <View testID="settings-admin-section">
      <MnEyebrow style={styles.section}>Administration</MnEyebrow>
      <MnCard style={styles.card}>
        <Text style={[styles.note, { color: theme.ink3 }]}>
          Members, invitations, whether this server accepts sign-ups, and the record of what
          changed. Only administrators of this household can open these.
        </Text>
        <MnBtn
          testID="settings-admin-console-btn"
          label="Open admin console"
          variant="secondary"
          fullWidth
          style={styles.rowBtn}
          onPress={() => router.push('/(tabs)/admin')}
        />
      </MnCard>
    </View>
  );
}

const styles = StyleSheet.create({
  section: { marginTop: Space.s4 },
  card: { marginBottom: Space.s4 },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginTop: Space.s1 },
  rowBtn: { marginTop: Space.s3 },
});
