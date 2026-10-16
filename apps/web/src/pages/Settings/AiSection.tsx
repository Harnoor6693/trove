/**
 * The AI endpoint section of Settings — the member's OWN endpoint, key, models,
 * privacy tier and timeouts.
 *
 * **THE ONE RULE THAT CANNOT BE BROKEN: the API key is never echoed.** The server
 * stores it AES-GCM-encrypted and never returns it in any form —
 * `AiSettingsResponse` has no field for it, only `hasApiKey`. So:
 *
 *  - the field is write-only and starts empty on every render;
 *  - the section shows WHETHER a key is stored, from `hasApiKey`, and never a
 *    value and never a masked value it invented (a row of dots that is not the
 *    length of the real key is a lie about the member's own secret);
 *  - the typed value lives in this component's state, is sent once, and is
 *    cleared the moment the save succeeds. It never reaches the QUERY cache,
 *    which by construction cannot hold one — `AiSettingsResponse` has no field
 *    for it — and it is deliberately NOT a mutation variable either: TanStack
 *    Query keeps `variables` on a settled mutation and keeps the `Mutation`
 *    object itself in the mutation cache for its gc window, so a key passed
 *    that way would stay readable from a state dump for minutes after the save.
 *    It goes to `saveSettings`' second argument and lives in a ref for the
 *    length of one request instead;
 *  - `deleteAiApiKey` is how a key is removed. A BLANK `apiKey` is 400
 *    `validation-error` rather than a silent clear, deliberately, so the form
 *    omits the field entirely when nothing was typed.
 *
 * **`PUT /v1/ai/settings` is a REPLACE, not a patch.** Every field except
 * `apiKey` takes its value from the body and an omitted nullable one is written
 * as null, so the form sends the WHOLE shape from its own state — which is
 * seeded from the saved settings — rather than only what changed.
 *
 * The model picker is populated from `GET /v1/ai/models`, which needs a saved
 * `endpointUrl` and nothing else — not `enabled`, and pointedly not a `model`.
 * That is why the member chooses from the endpoint's real list instead of typing
 * a model id.
 */
import { useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { QlBadge } from '@components/ui/QlBadge/QlBadge'
import { useUserFormat } from '@hooks/useUserFormat'
import {
  useAiModels,
  useAiSettings,
  useDeleteAiApiKeyMutation,
  useTestAiConnectionMutation,
  useUpdateAiSettingsMutation,
} from '@hooks/useAiSettings'
import { aiSettingsErrorCopy } from './aiErrorCopy'
import type { AiSettingsSave } from '@hooks/useAiSettings'
import type { AiSettingsResponse, PrivacyTier } from '@api/types'
import { WHOLE_NUMBER_ERROR, wholeNumber } from './wholeNumber'
import styles from './Settings.module.css'

/** The two tiers, with what each one actually does to a coach question. */
const TIERS: { value: PrivacyTier; label: string; note: string }[] = [
  {
    value: 'LOCAL_ONLY',
    label: 'Local only',
    note: 'Treat this endpoint as a private model on hardware you control. Sensitive payloads — which is every coach question, because they are built from your ledger — may be sent to it. This is what the coach needs.',
  },
  {
    value: 'CLOUD_OK',
    label: 'Somebody else’s server',
    note: 'Treat this endpoint as a third party. Sensitive payloads are held back, so the coach is refused before any connection is opened. Receipts and other non-sensitive work still run.',
  },
]

interface FormState {
  enabled: boolean
  endpointUrl: string
  model: string
  visionModel: string
  allowPrivateEndpoint: boolean
  privacyTier: PrivacyTier
  connectTimeoutMs: string
  readTimeoutMs: string
  /** Write-only, never rendered back, cleared on a successful save. */
  apiKey: string
}

function formFrom(settings: AiSettingsResponse): FormState {
  return {
    enabled: settings.enabled,
    endpointUrl: settings.endpointUrl ?? '',
    model: settings.model ?? '',
    visionModel: settings.visionModel ?? '',
    allowPrivateEndpoint: settings.allowPrivateEndpoint,
    privacyTier: settings.privacyTier,
    connectTimeoutMs: String(settings.connectTimeoutMs),
    readTimeoutMs: String(settings.readTimeoutMs),
    apiKey: '',
  }
}

/**
 * The whole shape the REPLACE needs, built from the form's own state —
 * EXCLUDING the key, which never travels as a mutation variable. The key goes
 * to `saveSettings`' second argument instead, and absent is what leaves the
 * stored one alone (blank is a 400, not a silent clear).
 */
function bodyFrom(form: FormState): AiSettingsSave {
  return {
    enabled: form.enabled,
    allowPrivateEndpoint: form.allowPrivateEndpoint,
    privacyTier: form.privacyTier,
    endpointUrl: form.endpointUrl.trim() === '' ? null : form.endpointUrl.trim(),
    model: form.model.trim() === '' ? null : form.model.trim(),
    visionModel: form.visionModel.trim() === '' ? null : form.visionModel.trim(),
    connectTimeoutMs: wholeNumber(form.connectTimeoutMs) ?? 0,
    readTimeoutMs: wholeNumber(form.readTimeoutMs) ?? 0,
  }
}

/** Rendered inside the loaded section, so the form's initial state is real data. */
function AiForm({ settings }: { settings: AiSettingsResponse }) {
  const [form, setForm] = useState<FormState>(() => formFrom(settings))
  const [saved, setSaved] = useState(false)
  const [tried, setTried] = useState(false)

  const save = useUpdateAiSettingsMutation()
  const forget = useDeleteAiApiKeyMutation()
  const test = useTestAiConnectionMutation()
  // Needs only a saved endpoint. Asking before one exists is a guaranteed 409.
  const models = useAiModels({ enabled: settings.endpointUrl !== null })
  const format = useUserFormat()

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setSaved(false)
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  const timeoutError = (value: string) =>
    tried && wholeNumber(value) === null ? WHOLE_NUMBER_ERROR : undefined

  function submit() {
    setSaved(false)
    setTried(true)
    if (wholeNumber(form.connectTimeoutMs) === null || wholeNumber(form.readTimeoutMs) === null) return
    // The key travels OUT OF BAND, never as a mutation variable: TanStack Query
    // keeps `variables` on a settled mutation and the Mutation object lives on
    // in the mutation cache for its gc window, so a key passed here would be
    // readable long after the save. `null` means "nothing typed — leave the
    // stored key alone".
    save.saveSettings(bodyFrom(form), form.apiKey === '' ? null : form.apiKey, {
      onSuccess: (next) => {
        // The typed value is dropped from this component the moment it has been
        // sent; the hook's ref is cleared by its own `onSettled`.
        setForm(formFrom(next))
        setSaved(true)
      },
    })
  }

  const tier = TIERS.find((t) => t.value === form.privacyTier) ?? TIERS[0]

  return (
    <div className={styles.section}>
      <h2 className={styles.sectionTitle} id="ai-endpoint">
        AI endpoint
      </h2>
      <p className={styles.note}>
        Trove has no AI of its own. Point it at an OpenAI-compatible endpoint you control — a model
        on your own machine is the intended setup — and it is yours alone: nobody else in your
        household can see or use it, administrators included.
      </p>

      <label className={styles.label} htmlFor="ai-enabled">
        <input
          id="ai-enabled"
          type="checkbox"
          checked={form.enabled}
          onChange={(e) => set('enabled', e.target.checked)}
        />{' '}
        Turn AI features on
      </label>
      <p className={styles.note}>
        Turning it on needs both an endpoint and a chat model. Nothing in Trove requires AI, and it
        is off on every install until you do this.
      </p>

      <QlInput
        label="Endpoint URL"
        value={form.endpointUrl}
        inputMode="url"
        placeholder="https://ai.example.test/v1"
        onChange={(e) => set('endpointUrl', e.target.value)}
        hint="An absolute https address, with no credentials before the host."
        error={save.error?.fieldError('endpointUrl')}
      />

      <label className={styles.label} htmlFor="ai-allow-private">
        <input
          id="ai-allow-private"
          type="checkbox"
          checked={form.allowPrivateEndpoint}
          onChange={(e) => set('allowPrivateEndpoint', e.target.checked)}
        />{' '}
        Allow a private or loopback address
      </label>
      <p className={styles.note}>
        Your half of the opt-in for a model on your own network. This install has to permit it as
        well; setting it here alone does not make a LAN address reachable.
      </p>

      {/* WRITE-ONLY. The value is never read back — the server does not have a
          route that returns it, and no masked stand-in is invented here. */}
      <QlInput
        label="API key"
        type="password"
        autoComplete="off"
        value={form.apiKey}
        placeholder={settings.hasApiKey ? 'A key is stored' : 'No key stored'}
        onChange={(e) => set('apiKey', e.target.value)}
        hint="Stored encrypted and never shown again, here or anywhere else. Leave this empty to keep the key you already have."
        error={save.error?.fieldError('apiKey')}
      />
      <div className={styles.row}>
        <span className={styles.label}>Stored key</span>
        <span className={styles.value} data-testid="ai-has-key">
          <QlBadge variant={settings.hasApiKey ? 'sage' : 'neutral'}>
            {settings.hasApiKey ? 'One is stored' : 'None'}
          </QlBadge>
        </span>
      </div>
      {settings.hasApiKey && (
        <div className={styles.actions}>
          <QlButton variant="ghost" loading={forget.isPending} onClick={() => forget.mutate()}>
            Forget the stored key
          </QlButton>
        </div>
      )}

      {/* Populated from the endpoint's own list rather than typed. */}
      <label className={styles.label} htmlFor="ai-model">
        Chat model
      </label>
      {models.data && models.data.length > 0 ? (
        <select
          id="ai-model"
          className={styles.select}
          value={form.model}
          onChange={(e) => set('model', e.target.value)}
          data-testid="ai-model-select"
        >
          <option value="">Not chosen</option>
          {models.data.map((m) => (
            <option key={m.id} value={m.id}>
              {m.id}
              {m.ownedBy ? ` · ${m.ownedBy}` : ''}
            </option>
          ))}
        </select>
      ) : (
        <QlInput
          label="Chat model"
          value={form.model}
          onChange={(e) => set('model', e.target.value)}
          hint="Save an endpoint address and Trove will offer the models it serves."
          error={save.error?.fieldError('model')}
        />
      )}
      {models.isError && (
        <p className={styles.error} role="alert" data-testid="ai-models-error">
          {aiSettingsErrorCopy(models.error)}
        </p>
      )}

      <label className={styles.label} htmlFor="ai-vision-model">
        Receipt-reading model
      </label>
      {models.data && models.data.length > 0 ? (
        <select
          id="ai-vision-model"
          className={styles.select}
          value={form.visionModel}
          onChange={(e) => set('visionModel', e.target.value)}
          data-testid="ai-vision-model-select"
        >
          <option value="">None — fill receipt fields in by hand</option>
          {models.data.map((m) => (
            <option key={m.id} value={m.id}>
              {m.id}
            </option>
          ))}
        </select>
      ) : (
        <QlInput
          label="Receipt-reading model"
          value={form.visionModel}
          onChange={(e) => set('visionModel', e.target.value)}
          hint="Optional. Without one, receipts fall back to the manual path rather than failing."
        />
      )}

      <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
        <legend className={styles.label}>How far your data may travel</legend>
        {TIERS.map((t) => (
          <label
            key={t.value}
            style={{
              display: 'flex',
              gap: 'var(--space-2)',
              alignItems: 'center',
              marginTop: 'var(--space-2)',
            }}
          >
            <input
              type="radio"
              name="privacyTier"
              value={t.value}
              checked={form.privacyTier === t.value}
              onChange={() => set('privacyTier', t.value)}
            />
            <span>{t.label}</span>
          </label>
        ))}
      </fieldset>
      <p className={styles.note} data-testid="ai-tier-note">
        {tier.note}
      </p>

      <QlInput
        label="Connect timeout (milliseconds)"
        inputMode="numeric"
        value={form.connectTimeoutMs}
        onChange={(e) => set('connectTimeoutMs', e.target.value)}
        error={timeoutError(form.connectTimeoutMs) ?? save.error?.fieldError('connectTimeoutMs')}
      />
      <QlInput
        label="Read timeout (milliseconds)"
        inputMode="numeric"
        value={form.readTimeoutMs}
        onChange={(e) => set('readTimeoutMs', e.target.value)}
        hint="A local model on modest hardware legitimately needs longer than a cloud one."
        error={timeoutError(form.readTimeoutMs) ?? save.error?.fieldError('readTimeoutMs')}
      />

      {save.isError && (
        <p className={styles.error} role="alert" data-testid="ai-save-error">
          {aiSettingsErrorCopy(save.error)}
        </p>
      )}
      {saved && (
        <p className={styles.status} role="status" data-testid="ai-saved">
          Saved.
        </p>
      )}

      <div className={styles.actions}>
        <QlButton variant="primary" loading={save.isPending} onClick={submit}>
          Save AI settings
        </QlButton>
        <QlButton variant="ghost" loading={test.isPending} onClick={() => test.mutate()}>
          Test the connection
        </QlButton>
      </div>

      {test.isError && (
        <p className={styles.error} role="alert" data-testid="ai-test-error">
          {aiSettingsErrorCopy(test.error)}
        </p>
      )}
      {test.data && (
        <p className={styles.status} role="status" data-testid="ai-test-ok">
          Reached {test.data.endpointHost}. It listed {test.data.modelCount} models
          {test.data.visionModelPresent === false
            ? ', but not the receipt-reading model you named — receipts will fall back to the manual path.'
            : '.'}
        </p>
      )}
      {settings.lastTestAt !== null && (
        <p className={styles.hint} data-testid="ai-last-test">
          Last tested {format.formatDateTime(settings.lastTestAt)} —{' '}
          {settings.lastTestOk ? 'it answered' : 'it did not answer'}.
        </p>
      )}
    </div>
  )
}

export function AiSection() {
  const settings = useAiSettings()

  return (
    <QlCard>
      {settings.isPending && (
        <div className={styles.section}>
          <h2 className={styles.sectionTitle}>AI endpoint</h2>
          <p className={styles.note} role="status">
            Loading your AI settings…
          </p>
        </div>
      )}
      {settings.isError && (
        <div className={styles.section}>
          <h2 className={styles.sectionTitle}>AI endpoint</h2>
          <p className={styles.error} role="alert" data-testid="ai-settings-error">
            {aiSettingsErrorCopy(settings.error)}
          </p>
        </div>
      )}
      {/* Deliberately NOT keyed on the row's `updatedAt`: remounting on every
          write would discard both the "Saved" confirmation and anything the
          member had typed since. The form re-seeds itself from the SERVER's
          answer in the save's own `onSuccess` instead, which is the moment the
          stored shape is actually known. */}
      {settings.data && <AiForm settings={settings.data} />}
    </QlCard>
  )
}
