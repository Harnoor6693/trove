/**
 * Self-serve registration.
 *
 * §1.5: registration is **closed** by default (`trove.auth.registration-open=false`)
 * and the first ADMIN is created by `FirstRunBootstrap` from `TROVE_ADMIN_EMAIL` /
 * `TROVE_ADMIN_PASSWORD` — not through this page. Everyone after that arrives by
 * invite, unless an admin flips `PUT /v1/admin/settings {registrationOpen}`.
 *
 * The gate is therefore `GET /v1/auth/providers → registrationOpen` (§4.1). §4
 * defines no "has an admin been created yet" endpoint and `/v1/status` is
 * explicitly detail-free, so `registrationOpen` is the only signal available;
 * see the ambiguities list in the step report.
 */
import { Navigate, useNavigate } from 'react-router-dom'
import { useAuthProviders } from '@hooks/useAuthProviders'
import { useAuthStore } from '@stores/authStore'
import { messageFor } from '@pages/Login/errorCopy'
import { RegisterForm } from './RegisterForm'
import styles from '@styles/auth.module.css'

export default function Register() {
  const navigate = useNavigate()
  const status = useAuthStore((s) => s.status)
  const providers = useAuthProviders()

  if (status === 'authenticated') return <Navigate to="/" replace />

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <div className={styles.logo}>Trove</div>
        <p className={styles.lede}>Create your account.</p>

        {providers.isPending && <p role="status">Checking whether sign-up is open…</p>}

        {providers.isError && (
          <p className={styles.error} role="alert">
            {messageFor('network')}
          </p>
        )}

        {providers.data && !providers.data.registrationOpen && (
          <p className={styles.notice} role="status" data-testid="registration-closed">
            {messageFor('registration_closed')}
          </p>
        )}

        {providers.data?.registrationOpen && (
          <RegisterForm onDone={() => navigate('/onboarding', { replace: true })} />
        )}

        <p className={styles.footnote}>
          Already have an account? <a href="/login">Sign in</a>.
        </p>
      </div>
    </div>
  )
}
