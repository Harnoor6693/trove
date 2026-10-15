/**
 * The one registration form, shared by `/register` and `/invite/:token`.
 *
 * `POST /v1/auth/register` (§4.1) takes
 * `{email, password, displayName, currency, country, timezone, inviteToken?}`.
 * Currency and country are set here and never again — every historical
 * transaction, budget and export assumes a stable reporting currency (§4.2) — so
 * the country choice is presented plainly and its consequences are spelled out.
 *
 * The timezone defaults from the browser (gap P2-15) and is submitted explicitly
 * rather than inferred server-side from the container clock.
 */
import { useState } from 'react'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { register } from '@api/auth'
import { COUNTRY_PROFILES, countryProfile, type CountryCode } from '@api/types'
import { ApiError } from '@api/client'
import { useAuthStore } from '@stores/authStore'
import { codeFromError, messageFor } from '@pages/Login/errorCopy'
import styles from '@styles/auth.module.css'

/** §1.5 — 12–128 characters, no composition rules, offline top-10k check server-side. */
const MIN_PASSWORD = 12
const MAX_PASSWORD = 128

function browserTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

export interface RegisterFormProps {
  inviteToken?: string
  onDone: () => void | Promise<void>
}

export function RegisterForm({ inviteToken, onDone }: RegisterFormProps) {
  const hydrate = useAuthStore((s) => s.hydrate)
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [country, setCountry] = useState<CountryCode>('CA')
  const [submitting, setSubmitting] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const profile = countryProfile(country)
  const passwordTooShort = password.length > 0 && password.length < MIN_PASSWORD

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setFormError(null)
    setFieldErrors({})
    if (password.length < MIN_PASSWORD) {
      setFieldErrors({ password: `Use at least ${MIN_PASSWORD} characters.` })
      return
    }
    setSubmitting(true)
    try {
      await register({
        email,
        password,
        displayName,
        currency: profile.currency,
        country: profile.country,
        timezone: browserTimezone(),
        ...(inviteToken ? { inviteToken } : {}),
      })
      await hydrate()
      await onDone()
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors.length > 0) {
        setFieldErrors(
          Object.fromEntries(err.fieldErrors.map((f) => [f.field, f.message]))
        )
      }
      setFormError(messageFor(codeFromError(err)))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      {formError && (
        <p className={styles.error} role="alert">
          {formError}
        </p>
      )}
      <form className={styles.form} onSubmit={(e) => void onSubmit(e)}>
        <QlInput
          label="Your name"
          name="displayName"
          autoComplete="name"
          required
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          error={fieldErrors.displayName}
        />
        <QlInput
          label="Email"
          name="email"
          type="email"
          autoComplete="username"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          error={fieldErrors.email}
        />
        <QlInput
          label="Password"
          name="password"
          type="password"
          autoComplete="new-password"
          required
          minLength={MIN_PASSWORD}
          maxLength={MAX_PASSWORD}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={fieldErrors.password ?? (passwordTooShort ? `Use at least ${MIN_PASSWORD} characters.` : undefined)}
          hint={`At least ${MIN_PASSWORD} characters. Length is the only rule.`}
        />

        <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
          <legend style={{ fontSize: 'var(--font-sm)', fontWeight: 500, color: 'var(--ink-2)' }}>
            Where you file taxes
          </legend>
          {COUNTRY_PROFILES.map((p) => (
            <label
              key={p.country}
              style={{ display: 'flex', gap: 'var(--space-2)', alignItems: 'center', marginTop: 'var(--space-2)' }}
            >
              <input
                type="radio"
                name="country"
                value={p.country}
                checked={country === p.country}
                onChange={() => setCountry(p.country)}
              />
              <span>
                {p.label} — reports in {p.currency}
              </span>
            </label>
          ))}
        </fieldset>
        <p className={styles.notice} style={{ marginBottom: 0 }}>
          This sets the currency every balance, budget and export is reported in. It cannot be
          changed later.
        </p>

        <QlButton type="submit" variant="primary" loading={submitting}>
          Create account
        </QlButton>
      </form>
    </>
  )
}
