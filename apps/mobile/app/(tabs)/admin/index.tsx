import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnPill } from '@components/primitives/MnPill/MnPill';
import { MnToggle } from '@components/ui/MnToggle/MnToggle';
import { AdminOnly } from '@components/mobile/AdminOnly/AdminOnly';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import {
  useAdminSettings,
  useClearHouseholdMemberTotp,
  useDeleteHouseholdMember,
  useHouseholdMembers,
  usePasswordResetLink,
  useRestoreHouseholdMember,
  useUnlockHouseholdMember,
  useUpdateAdminSettings,
} from '@hooks/useAdmin';
import { toApiFailure, type ApiFailure } from '@api/errors';
import { ErrorSlug, type AdminUserResponse } from '@api/types';
import { useUserFormat } from '@lib/useUserFormat';
import { useAuthStore } from '@stores/authStore';

/**
 * The admin console — members and household settings.
 *
 * **The gate is the server's.** `AdminOnly` renders the 403 `forbidden` these
 * routes answer a MEMBER with; the card on Settings that hides the entry point
 * is tidiness, not a control.
 *
 * Three refusals this screen exists to explain rather than swallow:
 *
 *  - **409 `conflict` on delete** — the household's last `ADMIN`, or a member
 *    already inside the deletion grace. Asking again would only restart their
 *    restore window.
 *  - **409 `conflict` on restore** — never deleted, or the grace has passed.
 *    There is nothing left to restore; invite them again after the purge.
 *  - **403 `step-up-required` on unlock and clear-TOTP for a SELF target.** An
 *    admin route never acts on the admin's own security state, because
 *    `POST /v1/auth/totp/disable` demands the password *and* a live code and
 *    an admin who could clear their own factor from here would need neither.
 *    Those two actions are therefore not offered on the caller's own row, and
 *    the row says why.
 *
 * **The reset link.** This install has no SMTP by default, so password reset
 * is an admin minting a one-hour single-use link and handing it over out of
 * band. `POST /v1/auth/password/reset-request` answers `{url, expiresAt}` and
 * that response is the only time the link is readable — it is shown once,
 * selectable, and dropped from the mutation the moment the admin dismisses it.
 * It is never cached, never logged and never persisted.
 */
export default function AdminMembersScreen() {
  const theme = useTheme();
  const router = useRouter();
  const format = useUserFormat();
  const myUserId = useAuthStore((s) => s.me?.userId ?? null);

  const members = useHouseholdMembers();
  const settings = useAdminSettings();
  const saveSettings = useUpdateAdminSettings();

  const remove = useDeleteHouseholdMember();
  const restore = useRestoreHouseholdMember();
  const unlock = useUnlockHouseholdMember();
  const clearTotp = useClearHouseholdMemberTotp();
  const resetLink = usePasswordResetLink();

  const [notice, setNotice] = useState<string | null>(null);

  /** The last refusal for this row, whichever action produced it. */
  function refusalFor(id: string): ApiFailure | null {
    for (const action of [remove, restore, unlock, clearTotp] as const) {
      if (action.error != null && action.variables === id) {
        return toApiFailure(action.error, 'That action was refused.');
      }
    }
    return null;
  }

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: theme.paper }]} testID="admin-screen">
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text style={[styles.title, { color: theme.ink }]}>Admin</Text>

        <AdminOnly error={members.error ?? settings.error}>
          <View style={styles.links}>
            <MnBtn
              testID="admin-invites-link"
              label="Invitations"
              variant="secondary"
              style={styles.grow}
              onPress={() => router.push('/(tabs)/admin/invites')}
            />
            <MnBtn
              testID="admin-audit-link"
              label="Audit log"
              variant="secondary"
              style={styles.grow}
              onPress={() => router.push('/(tabs)/admin/audit')}
            />
          </View>

          {notice !== null && (
            <Text testID="admin-notice" style={[styles.note, { color: theme.sage }]}>
              {notice}
            </Text>
          )}

          {/* ------------------------------------------------------- settings */}
          <MnEyebrow style={styles.section}>Sign-ups</MnEyebrow>
          <MnCard style={styles.card}>
            <MnToggle
              testID="admin-registration-toggle"
              label="Anyone may sign up"
              value={settings.data?.registrationOpen ?? false}
              onToggle={(value) => saveSettings.mutate({ registrationOpen: value })}
            />
            <Text style={[styles.note, { color: theme.ink3 }]}>
              Off means invitation only, which is how a Trove server ships. The sign-in page reads
              this flag, so the change takes effect immediately.
            </Text>
            {saveSettings.error != null && (
              <Text
                testID="admin-settings-error"
                accessibilityRole="alert"
                style={[styles.note, { color: theme.red }]}
              >
                {toApiFailure(saveSettings.error, 'Could not save that setting.').message}
              </Text>
            )}
          </MnCard>

          {/* -------------------------------------------------------- members */}
          <MnEyebrow style={styles.section}>Members</MnEyebrow>
          <MnCard style={styles.card}>
            {members.isLoading && (
              <Text style={[styles.note, { color: theme.ink3 }]} testID="admin-members-loading">
                Loading…
              </Text>
            )}
            {members.error != null && (
              <Text
                testID="admin-members-error"
                accessibilityRole="alert"
                style={[styles.note, { color: theme.red }]}
              >
                {toApiFailure(members.error, 'Could not load your household.').message}
              </Text>
            )}
            {members.data?.map((member) => (
              <MemberRow
                key={member.id}
                member={member}
                isSelf={member.id === myUserId}
                formattedCreatedAt={format.date(member.createdAt, 'medium')}
                failure={refusalFor(member.id)}
                busy={
                  (remove.isPending && remove.variables === member.id) ||
                  (restore.isPending && restore.variables === member.id) ||
                  (unlock.isPending && unlock.variables === member.id) ||
                  (clearTotp.isPending && clearTotp.variables === member.id)
                }
                onDelete={() => remove.mutate(member.id)}
                onRestore={() => restore.mutate(member.id)}
                onUnlock={() =>
                  unlock.mutate(member.id, {
                    onSuccess: () => setNotice(`${member.displayName} can sign in again.`),
                  })
                }
                onClearTotp={() =>
                  clearTotp.mutate(member.id, {
                    onSuccess: () =>
                      setNotice(`${member.displayName}’s second factor has been removed.`),
                  })
                }
                onResetLink={() => resetLink.mutate({ email: member.email })}
              />
            ))}
          </MnCard>

          {/* ---------------------------------------------------- reset link */}
          {resetLink.data !== undefined && (
            <MnCard style={styles.card} testID="admin-reset-link">
              <Text style={[styles.label, { color: theme.ink3 }]}>Password reset link</Text>
              <Text selectable style={[styles.mono, { color: theme.ink }]} testID="admin-reset-url">
                {resetLink.data.url}
              </Text>
              <Text style={[styles.note, { color: theme.ink3 }]}>
                Single use, and it stops working {format.date(resetLink.data.expiresAt, 'dateTime')}
                . This is the only time it is readable — hand it over directly, not by email.
              </Text>
              <MnBtn
                testID="admin-reset-done"
                label="Done"
                variant="secondary"
                fullWidth
                style={styles.rowBtn}
                onPress={() => resetLink.reset()}
              />
            </MnCard>
          )}
          {resetLink.error != null && (
            <Text
              testID="admin-reset-error"
              accessibilityRole="alert"
              style={[styles.note, { color: theme.red }]}
            >
              {toApiFailure(resetLink.error, 'Could not mint a reset link.').message}
            </Text>
          )}
        </AdminOnly>

        <MnBtn
          testID="admin-back-btn"
          label="Back to settings"
          variant="ghost"
          fullWidth
          style={styles.section}
          onPress={() => router.back()}
        />
      </ScrollView>
    </SafeAreaView>
  );
}

/** Copy for the per-row refusals these routes really emit. */
function rowRefusal(failure: ApiFailure): string {
  if (failure.code === ErrorSlug.stepUpRequired) {
    return 'An admin can’t change their own security settings from here. Use the account screens, which ask for your password and a code.';
  }
  if (failure.code === ErrorSlug.conflict) {
    return failure.message;
  }
  if (failure.code === ErrorSlug.notFound) {
    return 'That member is no longer in your household.';
  }
  return failure.message;
}

function MemberRow({
  member,
  isSelf,
  formattedCreatedAt,
  failure,
  busy,
  onDelete,
  onRestore,
  onUnlock,
  onClearTotp,
  onResetLink,
}: {
  member: AdminUserResponse;
  isSelf: boolean;
  formattedCreatedAt: string;
  failure: ApiFailure | null;
  busy: boolean;
  onDelete: () => void;
  onRestore: () => void;
  onUnlock: () => void;
  onClearTotp: () => void;
  onResetLink: () => void;
}) {
  const theme = useTheme();
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  return (
    <View style={[styles.row, { borderColor: theme.line }]} testID={`admin-member-${member.id}`}>
      <Text style={[styles.value, { color: theme.ink }]}>{member.displayName}</Text>
      <Text style={[styles.note, { color: theme.ink3 }]}>{member.email}</Text>
      <View style={styles.pills}>
        <MnPill label={member.role} variant={member.role === 'ADMIN' ? 'sage' : 'muted'} />
        <MnPill
          testID={`admin-member-${member.id}-state`}
          label={member.active ? 'Active' : 'Inactive'}
          variant={member.active ? 'sage' : 'muted'}
        />
        {member.totpEnabled && <MnPill label="2FA" variant="sage" />}
      </View>
      <Text style={[styles.note, { color: theme.ink3 }]}>Joined {formattedCreatedAt}</Text>

      {failure !== null && (
        <Text
          testID={`admin-member-${member.id}-error`}
          accessibilityRole="alert"
          style={[styles.note, { color: theme.red }]}
        >
          {rowRefusal(failure)}
        </Text>
      )}

      <View style={styles.actions}>
        {member.active ? (
          confirmingDelete ? (
            <MnBtn
              label="Confirm removal"
              variant="danger"
              loading={busy}
              style={styles.grow}
              testID={`admin-member-${member.id}-delete-confirm`}
              onPress={onDelete}
            />
          ) : (
            <MnBtn
              label="Remove"
              variant="danger"
              style={styles.grow}
              testID={`admin-member-${member.id}-delete`}
              onPress={() => setConfirmingDelete(true)}
            />
          )
        ) : (
          <MnBtn
            label="Restore"
            variant="secondary"
            loading={busy}
            style={styles.grow}
            testID={`admin-member-${member.id}-restore`}
            onPress={onRestore}
          />
        )}
        <MnBtn
          label="Reset link"
          variant="ghost"
          style={styles.grow}
          testID={`admin-member-${member.id}-reset-link`}
          onPress={onResetLink}
        />
      </View>

      {isSelf ? (
        <Text
          style={[styles.note, { color: theme.ink3 }]}
          testID={`admin-member-${member.id}-self`}
        >
          Unlocking an account and removing its second factor are not offered for your own — an
          admin route never acts on the admin&rsquo;s own security state. Change yours from
          Settings, which asks for your password and a current code.
        </Text>
      ) : (
        <View style={styles.actions}>
          <MnBtn
            label="Unlock"
            variant="ghost"
            style={styles.grow}
            testID={`admin-member-${member.id}-unlock`}
            onPress={onUnlock}
          />
          {member.totpEnabled && (
            <MnBtn
              label="Clear 2FA"
              variant="ghost"
              style={styles.grow}
              testID={`admin-member-${member.id}-clear-totp`}
              onPress={onClearTotp}
            />
          )}
        </View>
      )}

      {!member.active && (
        <Text
          style={[styles.note, { color: theme.ink3 }]}
          testID={`admin-member-${member.id}-grace`}
        >
          Removed. You can restore this account for seven days from when it was removed; after that
          it is erased and their address stays reserved until the erasure runs.
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  content: { padding: Space.s4, paddingBottom: Space.s16 },
  title: { fontFamily: FontFamily.displaySemi, fontSize: FontSize.xl, marginBottom: Space.s3 },
  section: { marginTop: Space.s4 },
  card: { marginBottom: Space.s4 },
  links: { flexDirection: 'row', gap: Space.s2 },
  grow: { flex: 1, minHeight: 44 },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginTop: Space.s2 },
  label: {
    fontFamily: FontFamily.bodySemi,
    fontSize: FontSize.xs,
    letterSpacing: 1,
    textTransform: 'uppercase',
  },
  mono: {
    fontFamily: FontFamily.mono,
    fontSize: FontSize.sm,
    marginTop: Space.s2,
    borderRadius: Radius.md,
  },
  value: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.md },
  row: { borderTopWidth: 1, paddingTop: Space.s3, marginTop: Space.s3 },
  pills: { flexDirection: 'row', gap: Space.s2, marginTop: Space.s2, flexWrap: 'wrap' },
  actions: { flexDirection: 'row', gap: Space.s2, marginTop: Space.s3 },
  rowBtn: { marginTop: Space.s3 },
});
