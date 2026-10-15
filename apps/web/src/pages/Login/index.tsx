/**
 * Sign in.
 *
 * Rebuilt for local accounts (§1.5): Authentik is no longer a hard dependency, so
 * the page is an email/password form first and an OIDC button only when
 * `GET /v1/auth/providers` says one is configured — the old page linked
 * `/v1/auth/start` unconditionally, which 404s the moment OIDC is optional.
 *
 * TOTP: §1.5/§4.1 — when the account has TOTP enabled, `POST /v1/auth/login`
 * answers with a 60-second `challengeToken` instead of a session, and the second
 * factor goes to `POST /v1/auth/login/totp`. That is a separate screen here, not a
 * hidden third field, because a recovery code is a legitimate answer too.
 *
 * **THE OIDC SECOND FACTOR (step 205, recorded blocker).** There are TWO ways
 * into that code form and this page used to know only one. A password login
 * hands the client a `challengeToken`. An OIDC sign-in by a member who has TOTP
 * enabled never goes through `POST /v1/auth/login` at all: the server redirects
 * the browser to `<publicUrl>/login?error=totp_required` and sets the HttpOnly
 * `__Host-trove_totp_challenge` cookie, which the SPA cannot read. The page
 * therefore renders the code form on that query parameter too, with NO token,
 * and posts `{code}` alone — `TotpLoginRequest.challengeToken` is optional
 * precisely so the cookie can carry it, and the server reads the cookie when the
 * field is absent. Sending `challengeToken: undefined` was previously impossible
 * here because the field was typed as mandatory and forced with a `!`.
 *
 * The OIDC entry point is `oidcStartUrl(providers)`: `GET /v1/auth/providers`
 * advertises an absolute `oidc.startUrl` and the advertised value is the
 * contract; the same-origin constant is only the fallback.
 */
import { useState } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { login, loginTotp, oidcStartUrl } from '@api/auth'
import { isTotpChallenge } from '@api/types'
import { useAuthProviders } from '@hooks/useAuthProviders'
import { useAuthStore } from '@stores/authStore'
import { SOURCE_CODE_URL } from '@/layouts/AppShell'
import { codeFromError, codeFromQuery, messageFor, type LoginErrorCode } from './errorCopy'
import styles from '@styles/auth.module.css'

export default function Login() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const status = useAuthStore((s) => s.status)
  const hydrate = useAuthStore((s) => s.hydrate)

  const providers = useAuthProviders()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [challengeToken, setChallengeToken] = useState<string | null>(null)
  const [code, setCode] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [errorCode, setErrorCode] = useState<LoginErrorCode | null>(null)

  const queryError = codeFromQuery(params.get('error'))
  const message = messageFor(errorCode ?? queryError)

  // The OIDC return leg for a member with a second factor. The challenge is in
  // an HttpOnly cookie, so there is nothing for this page to hold — the query
  // parameter alone is what says "ask for the code".
  const totpRequiredByOidc = queryError === 'totp_required'
  const showCodeForm = challengeToken !== null || totpRequiredByOidc

  if (status === 'authenticated') return <Navigate to="/" replace />

  async function finish() {
    // Re-probe /v1/auth/me so the store holds the full identity (role, currency,
    // household) rather than the trimmed user object the login body carries.
    await hydrate()
    navigate('/', { replace: true })
  }

  async function onSubmitPassword(e: React.FormEvent) {
    e.preventDefault()
    setErrorCode(null)
    setSubmitting(true)
    try {
      const result = await login({ email, password })
      if (isTotpChallenge(result)) {
        setChallengeToken(result.challengeToken)
        return
      }
      await finish()
    } catch (err) {
      setErrorCode(codeFromError(err, 'login'))
    } finally {
      setSubmitting(false)
    }
  }

  async function onSubmitTotp(e: React.FormEvent) {
    e.preventDefault()
    setErrorCode(null)
    setSubmitting(true)
    try {
      // Body first, cookie second — an explicit token always beats a stale
      // cookie from an abandoned attempt, which is the server's own rule. When
      // there is no token the field is OMITTED, not sent as null: the server
      // falls back to `__Host-trove_totp_challenge`.
      const result = await loginTotp(challengeToken ? { challengeToken, code } : { code })
      if (isTotpChallenge(result)) {
        setErrorCode('totp_invalid')
        return
      }
      await finish()
    } catch (err) {
      setErrorCode(codeFromError(err, 'totp'))
    } finally {
      setSubmitting(false)
    }
  }

  if (showCodeForm) {
    return (
      <div className={styles.page}>
        <div className={styles.card}>
          <div className={styles.logo}>Trove</div>
          <p className={styles.lede}>Enter the 6-digit code from your authenticator app.</p>
          {/* `totp_required` is an instruction, not a failure, so it is a status
              and not an alert. A real refusal still reads as an alert below. */}
          {errorCode === null && totpRequiredByOidc && (
            <p className={styles.notice} role="status">
              {messageFor('totp_required')}
            </p>
          )}
          {errorCode !== null && (
            <p className={styles.error} role="alert">
              {messageFor(errorCode)}
            </p>
          )}
          <form className={styles.form} onSubmit={(e) => void onSubmitTotp(e)}>
            <QlInput
              label="Authentication code"
              name="code"
              inputMode="text"
              autoComplete="one-time-code"
              autoFocus
              required
              value={code}
              onChange={(e) => setCode(e.target.value)}
              hint="A recovery code works here too."
            />
            <QlButton type="submit" variant="primary" loading={submitting}>
              Verify
            </QlButton>
          </form>
          <p className={styles.footnote}>
            <button
              type="button"
              className={styles.oidc}
              onClick={() => {
                setChallengeToken(null)
                setCode('')
                setErrorCode(null)
                // An OIDC-driven code form is held by the query string, not by
                // state, so clearing state alone would leave the member stuck
                // on it.
                if (totpRequiredByOidc) navigate('/login', { replace: true })
              }}
            >
              Use a different account
            </button>
          </p>
        </div>
      </div>
    )
  }

  const oidc = providers.data?.oidc
  const registrationOpen = providers.data?.registrationOpen ?? false

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <div className={styles.logo}>Trove</div>
        <p className={styles.lede}>Sign in to your household ledger.</p>

        {message && (
          <p className={styles.error} role="alert">
            {message}
          </p>
        )}
        {providers.isError && (
          <p className={styles.notice} role="status">
            {messageFor('network')}
          </p>
        )}

        <form className={styles.form} onSubmit={(e) => void onSubmitPassword(e)}>
          <QlInput
            label="Email"
            name="email"
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          <QlInput
            label="Password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <QlButton type="submit" variant="primary" loading={submitting}>
            Sign in
          </QlButton>
        </form>

        {oidc?.enabled && (
          <>
            <div className={styles.divider}>or</div>
            <a className={styles.oidc} href={oidcStartUrl(providers.data)}>
              Continue with {oidc.displayName ?? 'your identity provider'}
            </a>
          </>
        )}

        <p className={styles.footnote}>
          {/* There is deliberately no "forgot password" form here.
              `POST /v1/auth/password/reset-request` is `x-trove-required-role:
              ADMIN`: most self-hosted installs have no mail server, so a reset is
              an admin minting a one-hour single-use link and handing it over out
              of band. A public minting route with no delivery channel would just
              be a reset oracle. The admin's half of this lives in
              Settings › Household members. */}
          Forgotten your password? Ask an administrator of your household for a reset link.
        </p>

        <p className={styles.footnote}>
          {registrationOpen ? (
            <>
              No account yet? <Link to="/register">Create one</Link>.
            </>
          ) : (
            <>This Trove is invite-only. Ask an administrator for an invite link.</>
          )}
        </p>

        <p className={styles.footnote}>
          Trove is free software under the AGPL-3.0.{' '}
          <a href={SOURCE_CODE_URL} target="_blank" rel="noopener noreferrer">
            Source code
          </a>
        </p>
      </div>
    </div>
  )
}
