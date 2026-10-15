/**
 * Accept a household invite — `/invite/:token`.
 *
 * §1.5/§4.1: an invite is a single-use, 72-hour link an admin mints with
 * `POST /v1/admin/invites` and hands over out of band (no SMTP required).
 * Accepting one is not an endpoint of its own — it is `POST /v1/auth/register`
 * with `inviteToken`, which is what pre-binds the new user's `household_id`.
 *
 * **What changed in step 205:** the file used to say "§4 defines no endpoint for
 * previewing an invite before registration, so this page cannot tell the visitor
 * whether the token is still valid until they submit". It does now:
 * `GET /v1/auth/invites/{token}` (`previewInvite`) is public and **always
 * answers 200** — the verdict is in the body, never in the status. Unknown,
 * revoked, used and expired tokens all return the identical
 * `{valid: false, email: null, expiresAt: null}`, one answer for all four, so the
 * route cannot be used to tell a real token from a near miss. This page says
 * exactly that much and no more: it does not guess which of the four happened.
 *
 * A bound invitation carries the address it is bound to; registration with a
 * different one is refused as `invite-invalid`. So a preview that names an
 * address is shown.
 *
 * This page renders no formatted date, deliberately: `useUserFormat` reads
 * `/v1/me/preferences`, and firing that from a PUBLIC page would put an
 * anonymous 401 through the client's refresh-and-expire path and bounce an
 * invited visitor to `/login`. The 72-hour life is a constant of the contract
 * and is stated as one.
 */
import { Navigate, useNavigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { previewInvite } from '@api/auth'
import { useAuthStore } from '@stores/authStore'
import { RegisterForm } from '@pages/Register/RegisterForm'
import { messageFor } from '@pages/Login/errorCopy'
import type { ApiError } from '@api/client'
import type { InvitePreviewResponse } from '@api/types'
import styles from '@styles/auth.module.css'

export default function Invite() {
  const { token } = useParams<{ token: string }>()
  const navigate = useNavigate()
  const status = useAuthStore((s) => s.status)

  const preview = useQuery<InvitePreviewResponse, ApiError>({
    queryKey: ['auth', 'invite-preview', token],
    queryFn: () => previewInvite(token as string),
    enabled: Boolean(token),
    retry: false,
  })

  if (status === 'authenticated') return <Navigate to="/" replace />

  // The route always answers 200, so `valid: false` is the refusal — a thrown
  // error here is the server being unreachable or refusing the whole request
  // (429), not a verdict on the token, and must not be read as one.
  const spent = preview.data?.valid === false

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <div className={styles.logo}>Trove</div>
        <p className={styles.lede}>You have been invited to a Trove household.</p>

        {!token || spent ? (
          <p className={styles.error} role="alert">
            {messageFor('invite_invalid')}
          </p>
        ) : (
          <>
            {preview.isPending && (
              <p className={styles.notice} role="status">
                Checking your invitation…
              </p>
            )}
            {preview.isError && (
              <p className={styles.notice} role="status">
                Trove could not check this invitation just now. You can still try to accept it.
              </p>
            )}
            {preview.data?.valid && (
              <p className={styles.notice} role="status">
                {preview.data.email
                  ? `This invitation is for ${preview.data.email}. Register with that address.`
                  : 'This invitation can be used by anyone holding the link.'}
{' '}
                It expires 72 hours after it was created. You will join the household that invited
                you and share its ledger.
              </p>
            )}
            {!spent && (
              <RegisterForm
                inviteToken={token}
                onDone={() => navigate('/onboarding', { replace: true })}
              />
            )}
          </>
        )}

        <p className={styles.footnote}>
          Already have an account? <a href="/login">Sign in</a>.
        </p>
      </div>
    </div>
  )
}
