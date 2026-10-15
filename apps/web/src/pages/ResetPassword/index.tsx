/**
 * Set a new password from a reset link — `/reset-password/:token`.
 *
 * **This route did not exist** (recorded debt: handoff 15 §8, "no client route
 * yet — 20b"). The server mails — or rather, hands an admin — a link of exactly
 * the form `<publicUrl>/reset-password/{token}`, and until now that link landed
 * on the SPA's 404 page.
 *
 * ## Where the link comes from, which is not where you would guess
 *
 * `POST /v1/auth/password/reset-request` is **`x-trove-required-role: ADMIN`**.
 * From its own description: *"Password reset here is not an email, because most
 * self-hosted installs have no mail server. It is an admin minting a one-hour
 * single-use link and handing it over out of band. That is also why the route is
 * admin-scoped rather than public: a public route that mints reset links, with
 * no second channel to deliver them through, would simply be a reset oracle."*
 *
 * So there is no self-service "email me a link" form anywhere in this app, and
 * building one would have meant building a screen the contract refuses to serve.
 * The login page points the member at an administrator; the minting control
 * lives in Settings › Household members, where an admin can read the link once.
 * The page below is the other end: **public, outside `AuthGuard`**, because the
 * whole point is that the member cannot sign in.
 *
 * ## What the redeem route does and does not say
 *
 * Unknown, expired and already-used tokens get the **identical** 400
 * `reset-invalid`, deliberately, so the route cannot be used to test guesses —
 * which is why the copy below covers all three at once rather than claiming to
 * know which one happened. The password policy is checked BEFORE the token is
 * consumed, so a refused password leaves the link usable and the member can try
 * again; the copy says so. On success every session of that account is revoked
 * and this browser's cookies are cleared, so the only honest next step is the
 * sign-in page.
 */
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { redeemPasswordResetLink } from '@api/auth'
import { useCredentialMutation } from '@hooks/useCredentialMutation'
import { resetErrorCopy } from './errorCopy'
import type { PasswordResetRequest } from '@api/types'
import styles from '@styles/auth.module.css'

export default function ResetPassword() {
  const { token } = useParams<{ token: string }>()
  const navigate = useNavigate()
  const [password, setPassword] = useState('')
  const [done, setDone] = useState(false)

  // BOTH halves of this body are credentials: the new password, and the reset
  // token itself, which is a bearer credential for setting this account's
  // password until it expires. Passed the ordinary way they would be the
  // mutation's `variables` and would stay readable from
  // `queryClient.getMutationCache().getAll()` for the gc window after this page
  // unmounted — so this surface uses the same out-of-band hook as every other
  // credential-collecting screen in the family.
  const redeem = useCredentialMutation<void, PasswordResetRequest>(redeemPasswordResetLink, {
    onSuccess: () => {
      // The password is never kept a moment longer than the call it was made for.
      setPassword('')
      setDone(true)
    },
  })

  function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!token) return
    redeem.submit({ token, password })
  }

  if (!token) {
    return (
      <div className={styles.page}>
        <div className={styles.card}>
          <div className={styles.logo}>Trove</div>
          <p className={styles.error} role="alert">
            That reset link is incomplete. Ask an administrator for a new one.
          </p>
          <p className={styles.footnote}>
            <Link to="/login">Back to sign in</Link>
          </p>
        </div>
      </div>
    )
  }

  if (done) {
    return (
      <div className={styles.page}>
        <div className={styles.card}>
          <div className={styles.logo}>Trove</div>
          <p className={styles.notice} role="status">
            Your password is set. Every other session on this account has been signed out, and any
            sign-in lock has been cleared. Sign in with your new password.
          </p>
          <QlButton variant="primary" onClick={() => navigate('/login', { replace: true })}>
            Go to sign in
          </QlButton>
        </div>
      </div>
    )
  }

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <div className={styles.logo}>Trove</div>
        <p className={styles.lede}>Choose a new password.</p>
        <p className={styles.notice} role="status">
          This link works once and expires an hour after an administrator created it.
        </p>

        {/* Rendered for EVERY failure, including a policy refusal: the inline
            field error says what was wrong with the password, this says what it
            means for the link. */}
        {redeem.isError && (
          <p className={styles.error} role="alert" data-testid="reset-error">
            {resetErrorCopy(redeem.error)}
          </p>
        )}

        <form className={styles.form} onSubmit={submit}>
          <QlInput
            label="New password"
            name="password"
            type="password"
            autoComplete="new-password"
            autoFocus
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={redeem.error?.fieldError('password')}
            hint="Your link stays usable if this one is refused, so you can try another."
          />
          <QlButton type="submit" variant="primary" loading={redeem.isPending}>
            Set password
          </QlButton>
        </form>

        <p className={styles.footnote}>
          <Link to="/login">Back to sign in</Link>
        </p>
      </div>
    </div>
  )
}
