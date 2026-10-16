/**
 * First run — three ungated steps.
 *
 * Rebuilt to the product-designer recommendation in
 * `docs/recovery/04-REVIEWS-AND-SCOPE.md`:
 *
 *  - `onboardingCompleted` is written at the **end of step 1**. From that moment
 *    nobody can be locked out of the app, because `AuthGuard`'s onboarding
 *    redirect can no longer fire. Everything after step 1 is progressive
 *    disclosure, not a gate.
 *  - Every step continues without a server round-trip succeeding first. Defect I2
 *    was step 3's Continue being `disabled={!mutation.isSuccess}` against
 *    SimpleFIN — a paid hosted service — with no skip path and no logout button,
 *    which locked a tokenless self-hoster inside setup permanently.
 *  - The Deployment step is gone (it asked a question whose answer changed
 *    nothing, and offered a "Cloud" option a self-hosted product does not have)
 *    and so is the First Budget step (a first budget with zero transactions is a
 *    number the user invents about themselves, and §5's trust rule forbids it).
 *  - The footer carries "Restart setup" and "Sign out" on every step.
 */
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { usePreferences } from '@hooks/usePreferences'
import { useUpdatePreferencesMutation } from '@hooks/useUpdatePreferencesMutation'
import { exchangeErrorCopy, useExchangeSetupTokenMutation } from '@hooks/useExchangeSetupTokenMutation'
import { useLogout } from '@hooks/useLogout'
import { useAuthStore } from '@stores/authStore'
import { countryProfile, type LocaleCode } from '@api/types'
import styles from './Onboarding.module.css'

const STEPS = ['Your setup', 'Connect data', 'Done'] as const

function browserTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

/* ---------------------------------------------------------------- step 1 */

interface StepOneProps {
  onComplete: (values: { timezone: string; locale: LocaleCode }) => Promise<void>
  saving: boolean
  error: string | null
}

function StepOne({ onComplete, saving, error }: StepOneProps) {
  const user = useAuthStore((s) => s.user)
  const prefs = usePreferences()
  const [timezone, setTimezone] = useState(prefs.data?.timezone ?? browserTimezone())
  const [locale, setLocale] = useState<LocaleCode>(
    prefs.data?.locale ?? (user ? countryProfile(user.country).locale : 'en-CA')
  )

  // Country and currency were chosen at registration and are fixed afterwards
  // (§4.2): every historical amount is denominated in them. They are shown, not
  // offered, and there is no endpoint that would change them.
  const profile = user ? countryProfile(user.country) : null

  return (
    <>
      <h1 className={styles.title}>Let's set up Trove</h1>
      <p className={styles.subtitle}>
        Three short steps. After this one you can leave and come back whenever you like.
      </p>

      {profile && (
        <div className={styles.fixedRow} data-testid="onboarding-country">
          <span className={styles.fixedLabel}>Country and reporting currency</span>
          <span className={styles.fixedValue}>
            {profile.label} · {profile.currency}
          </span>
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
        <QlInput
          label="Time zone"
          name="timezone"
          value={timezone}
          onChange={(e) => setTimezone(e.target.value)}
          hint="Used for every “today” and “this month” boundary. Defaulted from your browser."
        />

        <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
          <legend style={{ fontSize: 'var(--font-sm)', fontWeight: 500, color: 'var(--ink-2)' }}>
            Date and number format
          </legend>
          {(['en-CA', 'en-US'] as const).map((l) => (
            <label
              key={l}
              style={{ display: 'flex', gap: 'var(--space-2)', alignItems: 'center', marginTop: 'var(--space-2)' }}
            >
              <input
                type="radio"
                name="locale"
                value={l}
                checked={locale === l}
                onChange={() => setLocale(l)}
              />
              <span>
                {l === 'en-CA' ? 'Canadian English (2026-09-03)' : 'US English (Sep 3, 2026)'}
              </span>
            </label>
          ))}
        </fieldset>
      </div>

      {error && (
        <p className={styles.errorHint} role="alert" style={{ marginTop: 'var(--space-4)' }}>
          {error}
        </p>
      )}

      <div className={styles.actions} style={{ marginTop: 'var(--space-8)' }}>
        <QlButton
          variant="primary"
          loading={saving}
          onClick={() => void onComplete({ timezone, locale })}
        >
          Continue
        </QlButton>
      </div>
    </>
  )
}

/* ---------------------------------------------------------------- step 2 */

function StepTwo({ onNext }: { onNext: () => void }) {
  const [setupToken, setSetupToken] = useState('')
  const mutation = useExchangeSetupTokenMutation()
  // A settled mutation keeps its `variables` — and so the raw token — in the
  // shared MutationCache after this step unmounts. Dropped on the way out, as on
  // the Accounts dialog.
  const reset = mutation.reset
  useEffect(() => reset, [reset])

  return (
    <>
      <h1 className={styles.title}>Connect your data</h1>
      <p className={styles.subtitle}>
        Both of these are optional. Trove works without them — you can add accounts any time from
        Accounts.
      </p>

      <div className={styles.connectCard}>
        <strong>Import a file</strong>
        <p style={{ fontSize: 'var(--font-sm)', color: 'var(--ink-2)' }}>
          CSV, OFX or QFX exported from your bank. Nothing leaves your server.
        </p>
        <EmptyState
          title="Import a statement from Accounts"
          data-testid="onboarding-import-pending"
        >
          <p>
            Importing is an action on an account, so it lives on the Accounts screen: use{' '}
            <strong>Add an account</strong> there, say that you will import CSV or OFX statements
            into it, and the <strong>Import a file</strong> control appears on that account&rsquo;s
            row. Nothing is written until you have seen the preview.
          </p>
        </EmptyState>
        <div className={styles.skipRow}>
          <button type="button" className={styles.linkish} onClick={onNext}>
            Skip for now
          </button>
        </div>
      </div>

      <div className={styles.connectCard}>
        <strong>Connect a bank with SimpleFIN</strong>
        <p style={{ fontSize: 'var(--font-sm)', color: 'var(--ink-2)' }}>
          Paste the setup token from your SimpleFIN Bridge account.
        </p>
        {/* The same single-use credential as the Accounts dialog, handled the
            same way: never rendered in the clear, never offered to the password
            manager, and dropped from state the moment the call settles — see
            `useExchangeSetupTokenMutation`, whose own contract is that no caller
            keeps it after that. One flow, one set of rules. */}
        <QlInput
          label="Setup token"
          name="setupToken"
          type="password"
          autoComplete="off"
          value={setupToken}
          onChange={(e) => setSetupToken(e.target.value)}
        />
        {mutation.isSuccess && (
          <p className={styles.success} role="status">
            Linked {mutation.data.accountIds.length} account
            {mutation.data.accountIds.length === 1 ? '' : 's'}.
          </p>
        )}
        {mutation.isError && (
          <p className={styles.errorHint} role="alert">
            {exchangeErrorCopy(mutation.error)}
          </p>
        )}
        <div style={{ display: 'flex', gap: 'var(--space-3)', justifyContent: 'space-between' }}>
          <QlButton
            variant="primary"
            loading={mutation.isPending}
            disabled={setupToken.trim().length === 0}
            onClick={() =>
              mutation.mutate(
                { sourceKind: 'SIMPLEFIN', setupToken: setupToken.trim() },
                { onSettled: () => setSetupToken('') }
              )
            }
          >
            Connect
          </QlButton>
          <button type="button" className={styles.linkish} onClick={onNext}>
            Skip for now
          </button>
        </div>
      </div>

      <div className={styles.actions} style={{ marginTop: 'var(--space-8)' }}>
        <QlButton variant="primary" onClick={onNext}>
          Continue
        </QlButton>
      </div>
    </>
  )
}

/* ---------------------------------------------------------------- step 3 */

function StepThree({ onFinish }: { onFinish: () => void }) {
  return (
    <>
      <h1 className={styles.title}>You're set up</h1>
      <p className={styles.subtitle}>Trove is yours to use now.</p>

      <ul className={styles.checklist} data-testid="finish-setup-checklist">
        <li className={styles.checkItem}>
          <span className={styles.checkMark} aria-hidden="true">
            [x]
          </span>
          <span>Account created, currency and time zone set</span>
        </li>
        <li className={styles.checkItem}>
          <span className={styles.checkPending} aria-hidden="true">
            [ ]
          </span>
          <span>Connect an account or import a file</span>
        </li>
        <li className={styles.checkItem}>
          <span className={styles.checkPending} aria-hidden="true">
            [ ]
          </span>
          <span>Set up a budget once you have some transactions to base it on</span>
        </li>
        <li className={styles.checkItem}>
          <span className={styles.checkPending} aria-hidden="true">
            [ ]
          </span>
          <span>Turn on the AI coach in Settings if you want it (it is off by default)</span>
        </li>
      </ul>

      <div className={styles.actions}>
        <QlButton variant="primary" onClick={onFinish}>
          Go to Today
        </QlButton>
      </div>
    </>
  )
}

/* ----------------------------------------------------------------- shell */

export default function Onboarding() {
  const navigate = useNavigate()
  const logout = useLogout()
  const [step, setStep] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const update = useUpdatePreferencesMutation()

  async function completeStepOne(values: { timezone: string; locale: LocaleCode }) {
    setError(null)
    try {
      // The whole point of the redesign: onboardingCompleted goes true here, at
      // the end of step 1, not at the end of the flow.
      await update.mutateAsync({
        timezone: values.timezone,
        locale: values.locale,
        onboardingCompleted: true,
      })
      setStep(1)
    } catch {
      setError('Could not save your settings. Check the server and try again.')
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.chrome}>
        <span className={styles.stepCounter}>
          Step {step + 1} of {STEPS.length} — {STEPS[step]}
        </span>
      </div>
      <div className={styles.progressBar}>
        <div
          className={styles.progressFill}
          style={{ width: `${((step + 1) / STEPS.length) * 100}%` }}
        />
      </div>

      <div className={styles.content}>
        {step === 0 && (
          <StepOne onComplete={completeStepOne} saving={update.isPending} error={error} />
        )}
        {step === 1 && <StepTwo onNext={() => setStep(2)} />}
        {step === 2 && <StepThree onFinish={() => navigate('/', { replace: true })} />}
      </div>

      {/* Two of the five non-negotiable escape hatches, on every step. */}
      <div className={styles.footerBar}>
        <button type="button" className={styles.linkish} onClick={() => setStep(0)}>
          Restart setup
        </button>
        <button type="button" className={styles.linkish} onClick={() => void logout()}>
          Sign out
        </button>
      </div>
    </div>
  )
}
