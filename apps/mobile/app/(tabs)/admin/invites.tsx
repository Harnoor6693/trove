import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { AdminOnly } from '@components/mobile/AdminOnly/AdminOnly';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useCreateInvite, useInvites, useRevokeInvite } from '@hooks/useAdmin';
import { toApiFailure } from '@api/errors';
import type { InviteResponse } from '@api/types';
import { useUserFormat } from '@lib/useUserFormat';

/**
 * Invitations — `GET/POST /v1/admin/invites` and `DELETE /v1/admin/invites/{id}`.
 *
 * **`token` and `url` are nullable, and that is the whole point of this
 * screen's row.** They are populated only while an invitation can still be
 * redeemed and go null once it is accepted, revoked or expired — "still
 * redeemable" is a question about now, so the answer changes with the clock
 * and not only with a write. A row therefore renders its **state** and offers
 * the link only when there is one; it never prints the literal string "null"
 * and never offers a copy control on a spent invite.
 *
 * The list, not the creation response, is where a live link is read from — the
 * contract says so at `listInvites`, for exactly that reason.
 *
 * The body of a create is optional and `{}` is the normal case: an invite with
 * no email can be redeemed by anyone holding the link, one with an email is
 * bound to that address, and `role` defaults to `MEMBER`.
 */

type InviteState = 'Usable' | 'Accepted' | 'Expired or revoked';

/**
 * The state of an invitation, read the way the server computes it: `token` and
 * `url` are non-null exactly while it can still be redeemed.
 */
function inviteState(invite: InviteResponse): InviteState {
  if (invite.acceptedAt !== null) return 'Accepted';
  return invite.token !== null && invite.url !== null ? 'Usable' : 'Expired or revoked';
}

export default function AdminInvitesScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();

  const invites = useInvites();
  const create = useCreateInvite();
  const revoke = useRevokeInvite();

  const [email, setEmail] = useState('');
  const [asAdmin, setAsAdmin] = useState(false);

  return (
    <SafeAreaView
      style={[styles.safe, { backgroundColor: theme.paper }]}
      testID="admin-invites-screen"
    >
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text style={[styles.title, { color: theme.ink }]}>Invitations</Text>

        <AdminOnly error={invites.error}>
          <MnEyebrow style={styles.section}>Invite someone</MnEyebrow>
          <MnCard style={styles.card}>
            <Text style={[styles.note, { color: theme.ink3 }]}>
              An invitation lasts 72 hours. Leave the address blank and anyone holding the link can
              use it; fill it in and only that address can.
            </Text>
            <TextInput
              testID="admin-invite-email"
              style={[
                styles.input,
                { color: theme.ink, backgroundColor: theme.card, borderColor: theme.line },
              ]}
              placeholder="Email (optional)"
              placeholderTextColor={theme.ink3}
              value={email}
              onChangeText={setEmail}
              keyboardType="email-address"
              autoCapitalize="none"
              autoCorrect={false}
              accessibilityLabel="Invitation email address, optional"
            />
            <MnBtn
              testID="admin-invite-role-toggle"
              label={asAdmin ? 'Role: administrator' : 'Role: member'}
              variant="ghost"
              fullWidth
              style={styles.rowBtn}
              onPress={() => setAsAdmin((current) => !current)}
            />
            <MnBtn
              testID="admin-invite-create"
              label="Create invitation"
              fullWidth
              style={styles.rowBtn}
              loading={create.isPending}
              onPress={() =>
                create.mutate(
                  {
                    ...(email.trim() === '' ? {} : { email: email.trim() }),
                    ...(asAdmin ? { role: 'ADMIN' as const } : {}),
                  },
                  { onSuccess: () => setEmail('') }
                )
              }
            />
            {create.error != null && (
              <Text
                testID="admin-invite-create-error"
                accessibilityRole="alert"
                style={[styles.note, { color: theme.red }]}
              >
                {toApiFailure(create.error, 'Could not create that invitation.').message}
              </Text>
            )}
          </MnCard>

          <MnEyebrow style={styles.section}>This household&rsquo;s invitations</MnEyebrow>
          <MnCard style={styles.card}>
            {invites.isLoading && (
              <Text style={[styles.note, { color: theme.ink3 }]} testID="admin-invites-loading">
                Loading…
              </Text>
            )}
            {invites.data?.length === 0 && (
              <Text style={[styles.note, { color: theme.ink3 }]} testID="admin-invites-empty">
                No invitations yet.
              </Text>
            )}
            {invites.data?.map((invite) => {
              const state = inviteState(invite);
              return (
                <View
                  key={invite.id}
                  style={[styles.row, { borderColor: theme.line }]}
                  testID={`admin-invite-${invite.id}`}
                >
                  <View style={styles.pills}>
                    <MnPill
                      testID={`admin-invite-${invite.id}-state`}
                      label={state}
                      variant={state === 'Usable' ? 'sage' : 'muted'}
                    />
                    <MnPill label={invite.email ?? 'Anyone with the link'} variant="muted" />
                  </View>
                  <Text style={[styles.note, { color: theme.ink3 }]}>
                    Expires {format.date(invite.expiresAt, 'dateTime')}
                    {invite.createdByEmail === null ? '' : ` · from ${invite.createdByEmail}`}
                  </Text>

                  {/* The link is rendered only when the server sent one. A
                      spent invite has `url: null`, and printing that would put
                      the literal word "null" in front of an admin. */}
                  {invite.url !== null ? (
                    <>
                      <Text
                        selectable
                        style={[styles.mono, { color: theme.ink }]}
                        testID={`admin-invite-${invite.id}-url`}
                      >
                        {invite.url}
                      </Text>
                      <MnBtn
                        label="Revoke"
                        variant="danger"
                        fullWidth
                        style={styles.rowBtn}
                        loading={revoke.isPending && revoke.variables === invite.id}
                        testID={`admin-invite-${invite.id}-revoke`}
                        onPress={() => revoke.mutate(invite.id)}
                      />
                    </>
                  ) : (
                    <Text
                      style={[styles.note, { color: theme.ink3 }]}
                      testID={`admin-invite-${invite.id}-spent`}
                    >
                      {state === 'Accepted'
                        ? 'This invitation has been used, so its link no longer works.'
                        : 'This invitation is no longer usable, so there is no link to share.'}
                    </Text>
                  )}

                  {revoke.error != null && revoke.variables === invite.id && (
                    <Text
                      testID={`admin-invite-${invite.id}-error`}
                      accessibilityRole="alert"
                      style={[styles.note, { color: theme.red }]}
                    >
                      {toApiFailure(revoke.error, 'Could not revoke that invitation.').message}
                    </Text>
                  )}
                </View>
              );
            })}
          </MnCard>
        </AdminOnly>

        <MnBtn
          testID="admin-invites-back"
          label="Back"
          variant="ghost"
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
  title: { fontFamily: FontFamily.displaySemi, fontSize: FontSize.xl, marginBottom: Space.s3 },
  section: { marginTop: Space.s4 },
  card: { marginBottom: Space.s4 },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginTop: Space.s2 },
  mono: { fontFamily: FontFamily.mono, fontSize: FontSize.sm, marginTop: Space.s2 },
  input: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Space.s3,
    marginTop: Space.s2,
    minHeight: 44,
  },
  rowBtn: { marginTop: Space.s3, minHeight: 44 },
  row: { borderTopWidth: 1, paddingTop: Space.s3, marginTop: Space.s3 },
  pills: { flexDirection: 'row', gap: Space.s2, flexWrap: 'wrap' },
});
