/**
 * Settings.
 *
 * Three things landed here in 20a, and the member's own AI endpoint in 204:
 *
 *  1. **A sign-out control.** Defect I2 — `authStore.logout` was fully implemented
 *     and called by nothing; there was no way out of the app at all.
 *  2. **Timezone and locale** (gap P2-15). The reporting currency is shown but not
 *     editable: §4.2 fixes `currency`/`country` at registration because every
 *     historical amount is denominated in them.
 *  3. **Household invites** for admins (§4.1, gap P2-7) — single-use, 72-hour
 *     links, no SMTP, copied by hand. Step 205 fixed the copy-link control: an
 *     invite's `token` and `url` are NULL once it is accepted, revoked or
 *     expired, and the list used to render that null as the literal text
 *     "null" beside a Revoke button for a link that no longer existed.
 *
 * Step 205 also appended the three sections the "Your data" placeholder
 * promised: account security (password, TOTP, security events), the data export
 * and self-deletion, and the household admin console.
 *
 * The theme toggle is gone. It wrote real, persisted state that literally nothing
 * read, and no stylesheet in the repo has a `prefers-color-scheme` or
 * `data-theme` rule (defect I6): a control that appears to work and does nothing
 * is worse than its absence.
 */
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { useAuthStore } from '@stores/authStore'
import { usePreferences } from '@hooks/usePreferences'
import { useUpdatePreferencesMutation } from '@hooks/useUpdatePreferencesMutation'
import { useUserFormat } from '@hooks/useUserFormat'
import { useLogout } from '@hooks/useLogout'
import { updateProfile } from '@api/me'
import { createInvite, listInvites, revokeInvite } from '@api/invites'
import { AdminSection } from './AdminSection'
import { AiSection } from './AiSection'
import { DataSection } from './DataSection'
import { SecuritySection } from './SecuritySection'
import { NotificationsSection } from './NotificationsSection'
import { WebhooksSection } from './WebhooksSection'
import { countryProfile, type LocaleCode } from '@api/types'
import type { ApiError } from '@api/client'
import styles from './Settings.module.css'

const invitesKey = ['admin', 'invites'] as const

function ProfileSection() {
  const user = useAuthStore((s) => s.user)
  const setUser = useAuthStore((s) => s.setUser)
  const prefs = usePreferences()
  const updatePrefs = useUpdatePreferencesMutation()
  const [displayName, setDisplayName] = useState(user?.displayName ?? '')
  const [timezone, setTimezone] = useState(prefs.data?.timezone ?? '')
  const [saved, setSaved] = useState(false)

  const profileMutation = useMutation({
    mutationFn: updateProfile,
    onSuccess: (me) => setUser(me),
  })

  const currencyProfile = user ? countryProfile(user.country) : null

  async function save() {
    setSaved(false)
    await profileMutation.mutateAsync({ displayName, timezone })
    // §4.2 lists `timezone` on both `PUT /v1/me` and `PUT /v1/me/preferences`.
    // The SPA reads it from preferences, so it is written there too until the
    // server names one of them as authoritative.
    await updatePrefs.mutateAsync({ timezone })
    setSaved(true)
  }

  return (
    <QlCard>
      <div className={styles.section}>
        <h2 className={styles.sectionTitle}>Profile</h2>
        <QlInput
          label="Display name"
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
        />
        <QlInput
          label="Time zone"
          value={timezone}
          onChange={(e) => setTimezone(e.target.value)}
          hint="Every “today” and “this month” boundary is computed from this."
        />
        <div className={styles.row}>
          <span className={styles.label}>Email</span>
          <span className={styles.value}>{user?.email ?? '—'}</span>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>Reporting currency</span>
          <span className={styles.value}>
            {currencyProfile ? `${currencyProfile.label} · ${currencyProfile.currency}` : '—'}
          </span>
        </div>
        <p className={styles.note}>
          Currency and country are fixed at sign-up. Every stored transaction, budget and export
          is denominated in them, so changing one later would silently reinterpret your history.
        </p>
        <div className={styles.actions}>
          <QlButton
            variant="primary"
            loading={profileMutation.isPending || updatePrefs.isPending}
            onClick={() => void save()}
          >
            Save profile
          </QlButton>
        </div>
        {saved && (
          <p className={styles.status} role="status">
            Saved.
          </p>
        )}
        {profileMutation.isError && (
          <p className={styles.error} role="alert">
            Could not save your profile.
          </p>
        )}
      </div>
    </QlCard>
  )
}

function FormatSection() {
  const prefs = usePreferences()
  const updatePrefs = useUpdatePreferencesMutation()
  const format = useUserFormat()

  return (
    <QlCard>
      <div className={styles.section}>
        <h2 className={styles.sectionTitle}>Dates and numbers</h2>
        <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
          <legend className={styles.label}>Format</legend>
          {(['en-CA', 'en-US'] as const).map((l: LocaleCode) => (
            <label
              key={l}
              style={{ display: 'flex', gap: 'var(--space-2)', alignItems: 'center', marginTop: 'var(--space-2)' }}
            >
              <input
                type="radio"
                name="locale"
                value={l}
                checked={prefs.data?.locale === l}
                onChange={() => updatePrefs.mutate({ locale: l })}
              />
              <span>{l === 'en-CA' ? 'Canadian English' : 'US English'}</span>
            </label>
          ))}
        </fieldset>
        <div className={styles.row}>
          <span className={styles.label}>Example date</span>
          <span className={styles.value}>{format.formatDate('2026-09-03')}</span>
        </div>
      </div>
    </QlCard>
  )
}

/** Exported so the spent-invite fix has its own test, like every other section here. */
export function HouseholdSection() {
  const user = useAuthStore((s) => s.user)
  const queryClient = useQueryClient()
  const isAdmin = user?.role === 'ADMIN'
  const format = useUserFormat()

  const invites = useQuery({
    queryKey: invitesKey,
    queryFn: listInvites,
    enabled: isAdmin,
    retry: false,
  })

  const create = useMutation<Awaited<ReturnType<typeof createInvite>>, ApiError, void>({
    mutationFn: () => createInvite({}),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: invitesKey }),
  })
  const revoke = useMutation<void, ApiError, string>({
    mutationFn: revokeInvite,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: invitesKey }),
  })

  if (!isAdmin) {
    return (
      <QlCard>
        <div className={styles.section}>
          <h2 className={styles.sectionTitle}>Household</h2>
          <p className={styles.note}>
            You share one ledger with everyone in this household. Only an administrator can invite
            new members.
          </p>
        </div>
      </QlCard>
    )
  }

  return (
    <QlCard>
      <div className={styles.section}>
        <h2 className={styles.sectionTitle}>Household invites</h2>
        <p className={styles.note}>
          Invite links are single-use and expire after 72 hours. There is no email server — copy
          the link and send it however you like.
        </p>
        <div className={styles.actions}>
          <QlButton variant="primary" loading={create.isPending} onClick={() => create.mutate()}>
            Create invite link
          </QlButton>
        </div>
        {invites.isError && (
          <p className={styles.error} role="alert">
            Could not load invites.
          </p>
        )}
        {invites.data && invites.data.length === 0 && (
          <p className={styles.note}>No invites yet.</p>
        )}
        {invites.data && invites.data.length > 0 && (
          <ul className={styles.inviteList}>
            {invites.data.map((invite) => {
              // `token` and `url` are populated only while the invitation can
              // still be redeemed, and go null the moment it is accepted,
              // revoked or expired — "still redeemable" is a question about NOW,
              // so the answer changes with the clock and not only with a write.
              // This list used to render `invite.url` unconditionally, which
              // handed the admin the literal text "null" on a spent invite and
              // offered a copy-link control for a link that no longer existed
              // (recorded debt, handoff 15 §8).
              const redeemable = invite.url !== null
              // `acceptedAt` is the one spent state the contract names. It does
              // NOT distinguish revoked from expired — both simply null the
              // token — so neither does this copy, rather than guessing from a
              // clock reading taken during render.
              const state = invite.acceptedAt
                ? `accepted ${format.formatDateTime(invite.acceptedAt)}`
                : 'no longer usable — revoked or expired'
              return (
                <li key={invite.id} className={styles.invite}>
                  <span>
                    {redeemable ? (
                      <span className={styles.inviteUrl}>{invite.url}</span>
                    ) : (
                      <span className={styles.label} data-testid="invite-spent">
                        {state}
                      </span>
                    )}
                    <br />
                    <span className={styles.label}>
                      {invite.email ? `for ${invite.email} · ` : 'open to anyone with the link · '}
                      {redeemable
                        ? `expires ${format.formatDateTime(invite.expiresAt)}`
                        : `created ${format.formatDateTime(invite.createdAt)}`}
                    </span>
                  </span>
                  {redeemable && (
                    <QlButton variant="ghost" size="sm" onClick={() => revoke.mutate(invite.id)}>
                      Revoke
                    </QlButton>
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </div>
    </QlCard>
  )
}

export default function Settings() {
  const logout = useLogout()

  return (
    <div className={styles.page}>
      <h1 className={styles.heading}>Settings</h1>

      <ProfileSection />
      <FormatSection />
      <HouseholdSection />
      {/* Step 204: the member's own AI endpoint. It is per MEMBER, not per
          household, which is why it sits below the household section rather than
          inside it — and the Coach links straight to its `#ai-endpoint` heading,
          because "the coach needs an endpoint" and "here is where you name one"
          are one journey. */}
      <AiSection />

      {/* Step 203: notification preferences and webhook subscriptions,
          appended after the AI section for the same reason families D and E
          append their own — this file is a shared seam and each family only
          adds to it. */}
      <NotificationsSection />
      <WebhooksSection />

      {/* Step 205 (family E): the account surface the placeholder above promised.
          Account security first — it is the section a member reaches for most —
          then the data export and self-deletion, then the admin console, which
          only an administrator sees. */}
      <SecuritySection />
      <DataSection />
      <AdminSection />

      <QlCard>
        <div className={styles.section}>
          <h2 className={styles.sectionTitle}>Session</h2>
          <div className={styles.actions}>
            <QlButton variant="ghost" onClick={() => void logout()}>
              Sign out
            </QlButton>
          </div>
        </div>
      </QlCard>
    </div>
  )
}
