/**
 * Webhook subscriptions — a SECURITY SURFACE (§4.9/§7, step 203).
 *
 * `createWebhook`'s `targetUrl` is checked by the server's own egress policy;
 * a refusal is 400 `outbound-url-rejected` and is shown in the SERVER's own
 * words (`error.detail`), never invented copy, and this form does not
 * pre-validate the URL beyond the browser's own `type="url"`.
 *
 * **`WebhookResponse` never carries the secret, at creation or otherwise** —
 * confirmed against the schema; `hasSecret` (always `true`) is the whole of
 * what the API says about it. So there is nothing to "show once" from the
 * server. What this form does instead: after a successful create, it reminds
 * the member — from the value THEY JUST TYPED, held in this component's own
 * state — that Trove will not show it again. The reminder is cleared the
 * moment the dialog closes.
 *
 * The secret is ALSO never written to the shared React Query mutation cache:
 * `useCreateWebhookMutation`'s `saveWebhook` keeps it out of `variables`
 * entirely (a ref, cleared in `onSettled`) precisely because the mutation
 * cache outlives this component's own state for its gc window — see that
 * hook's own comment.
 */
import { useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { QlModal } from '@components/ui/QlModal/QlModal'
import { QlBadge } from '@components/ui/QlBadge/QlBadge'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { useDialogFocus } from '@hooks/useDialogFocus'
import { useCreateWebhookMutation, useDeleteWebhookMutation, useWebhooks } from '@hooks/useWebhooks'
import type { ApiError } from '@api/client'
import type { WebhookResponse } from '@api/types'
import styles from './Settings.module.css'

function webhooksErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'outbound-url-rejected':
      return error.detail ?? 'That address was refused by the server.'
    case 'validation-error':
      return error.fieldErrors[0]?.message ?? error.detail ?? 'Some of those details were refused.'
    case 'conflict':
      return error.detail ?? 'You already have the maximum number of webhook subscriptions.'
    case 'not-found':
      return 'That subscription is not there any more. Reload the page.'
    case 'forbidden':
    case 'csrf-header-missing':
      return 'That request was refused. Reload the page and try again.'
    case 'rate-limited':
      return 'Too many requests just now. Wait a few seconds, then try again.'
    default:
      return error.isNetworkError
        ? 'Trove could not reach the server. Check your connection and try again.'
        : 'That did not go through.'
  }
}

function CreateWebhookDialog({ onClose }: { onClose: () => void }) {
  const create = useCreateWebhookMutation()
  const ref = useDialogFocus<HTMLDivElement>(true)
  const [targetUrl, setTargetUrl] = useState('')
  const [secret, setSecret] = useState('')
  // The one-time reminder, built from what was JUST TYPED — never re-read
  // from the server or the query cache, which by construction cannot hold it.
  const [justCreatedSecret, setJustCreatedSecret] = useState<string | null>(null)

  function submit(e: React.FormEvent) {
    e.preventDefault()
    create.saveWebhook(
      { targetUrl },
      secret,
      { onSuccess: () => setJustCreatedSecret(secret) }
    )
  }

  if (justCreatedSecret !== null) {
    return (
      <QlModal isOpen onClose={onClose} title="Webhook created">
        <div className={styles.section} data-testid="webhook-secret-reminder">
          <p className={styles.note}>
            Your webhook is saved. Trove does not store a copy you can read back — the secret you
            just entered will not be shown again, here or anywhere else. If you lose it, delete
            this subscription and create a new one.
          </p>
          <div className={styles.actions}>
            <QlButton variant="primary" onClick={onClose}>
              Done
            </QlButton>
          </div>
        </div>
      </QlModal>
    )
  }

  return (
    <QlModal isOpen onClose={onClose} title="Add a webhook">
      <div ref={ref} tabIndex={-1} className={styles.section} data-testid="create-webhook-dialog">
        <form className={styles.section} onSubmit={submit}>
          <QlInput
            label="Target URL"
            type="url"
            value={targetUrl}
            placeholder="https://hooks.example.test/trove"
            onChange={(e) => setTargetUrl(e.target.value)}
            hint="An absolute https address. Checked by the server before it is saved, and again before every delivery."
            error={create.error?.fieldError('targetUrl')}
          />
          <QlInput
            label="Signing secret"
            type="password"
            autoComplete="off"
            value={secret}
            onChange={(e) => setSecret(e.target.value)}
            hint="At least 16 characters. Stored encrypted; Trove will not show it back to you after this."
            error={create.error?.fieldError('secret')}
          />
          {create.isError &&
            !create.error.fieldError('targetUrl') &&
            !create.error.fieldError('secret') && (
              <p className={styles.error} role="alert" data-testid="webhook-create-error">
                {webhooksErrorCopy(create.error)}
              </p>
            )}
          <div className={styles.actions}>
            <QlButton type="button" variant="ghost" onClick={onClose}>
              Cancel
            </QlButton>
            <QlButton type="submit" variant="primary" loading={create.isPending}>
              Create webhook
            </QlButton>
          </div>
        </form>
      </div>
    </QlModal>
  )
}

function DeleteWebhookDialog({
  webhook,
  onClose,
}: {
  webhook: WebhookResponse
  onClose: () => void
}) {
  const remove = useDeleteWebhookMutation()
  return (
    <QlModal isOpen onClose={onClose} title="Delete this webhook?">
      <div className={styles.section} data-testid="delete-webhook-dialog">
        <p className={styles.note}>
          Deliveries to {webhook.targetUrl} will stop, along with every delivery-log row for this
          subscription.
        </p>
        {remove.isError && (
          <p className={styles.error} role="alert">
            {webhooksErrorCopy(remove.error)}
          </p>
        )}
        <div className={styles.actions}>
          <QlButton variant="ghost" onClick={onClose}>
            Keep it
          </QlButton>
          <QlButton
            variant="danger"
            loading={remove.isPending}
            onClick={() => remove.mutate(webhook.id, { onSuccess: onClose })}
          >
            Delete
          </QlButton>
        </div>
      </div>
    </QlModal>
  )
}

export function WebhooksSection() {
  const webhooks = useWebhooks()
  const [showCreate, setShowCreate] = useState(false)
  const [toDelete, setToDelete] = useState<WebhookResponse | null>(null)
  const rows = webhooks.data ?? []

  return (
    <QlCard>
      <div className={styles.section}>
        <h2 className={styles.sectionTitle}>Webhooks</h2>
        <p className={styles.note}>
          Have Trove POST to your own endpoint when something happens on your ledger. Each
          delivery is signed with your secret so your endpoint can verify it came from Trove.
        </p>

        {webhooks.isPending && <QlSkeleton data-testid="webhooks-skeleton" />}
        {webhooks.isError && (
          <p className={styles.error} role="alert">
            {webhooksErrorCopy(webhooks.error)}
          </p>
        )}
        {webhooks.data && rows.length === 0 && (
          <EmptyState title="No webhooks yet">
            <p>Add one to have Trove notify your own systems.</p>
          </EmptyState>
        )}
        {webhooks.data && rows.length > 0 && (
          <ul className={styles.inviteList} data-testid="webhooks-list">
            {rows.map((hook) => (
              <li key={hook.id} className={styles.invite}>
                <span>
                  <span className={styles.inviteUrl}>{hook.targetUrl}</span>
                  <br />
                  <span className={styles.label}>
                    <QlBadge variant={hook.enabled ? 'sage' : 'neutral'}>
                      {hook.enabled ? 'Active' : 'Disabled'}
                    </QlBadge>{' '}
                    {hook.eventTypes.length === 0 ? 'every event' : hook.eventTypes.join(', ')}
                  </span>
                </span>
                <QlButton variant="ghost" size="sm" onClick={() => setToDelete(hook)}>
                  Delete
                </QlButton>
              </li>
            ))}
          </ul>
        )}

        <div className={styles.actions}>
          <QlButton variant="primary" onClick={() => setShowCreate(true)}>
            Add webhook
          </QlButton>
        </div>
      </div>

      {showCreate && <CreateWebhookDialog onClose={() => setShowCreate(false)} />}
      {toDelete && <DeleteWebhookDialog webhook={toDelete} onClose={() => setToDelete(null)} />}
    </QlCard>
  )
}
