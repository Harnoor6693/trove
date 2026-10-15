/**
 * Coach — one conversation with the member's OWN AI endpoint, streamed.
 *
 * This replaces a twenty-line placeholder, and it deliberately does not resurrect
 * what was there before the collapse: that implementation opened an
 * `EventSource` against `GET /api/coach/stream?q=` and waited for a named `done`
 * event. Neither the route nor the event exists. `EventSource` also cannot POST,
 * cannot carry a body, and cannot send the `X-Requested-With` and CSRF headers
 * this API demands, so it was never going to work against the real contract.
 *
 * Four things on this page are contract, not taste:
 *
 *  - **The answer is TEXT and is never interpreted.** It is rendered into a plain
 *    text node, with `white-space: pre-wrap` to honour its newlines. There is no
 *    `dangerouslySetInnerHTML` on this page, no markdown renderer and no HTML
 *    sanitiser — adding one would be adding a way for model output to become
 *    markup. No id, category, link or command is taken from it either.
 *  - **One user turn goes on the wire, never the transcript.** The transcript
 *    below is for the reader; the server rebuilds the history itself.
 *  - **When AI is off the coach is not broken.** `getAiSettings` says whether an
 *    endpoint is configured, so the unconfigured case is an explicit state with a
 *    link to the settings section — not a 409 dressed up as a failure.
 *  - **A 429 is "slow down", not a failure and never a logout.**
 *
 * Accessibility: the transcript is a `role="log"`, whose implicit
 * `aria-relevant="additions"` announces a turn when it is added and does NOT
 * re-read the thread as each token lands. The token-by-token growth is therefore
 * silent, and a separate `role="status"` line announces "answering" and then the
 * finished answer once — which is what a screen reader user actually wants from
 * a stream.
 */
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlModal } from '@components/ui/QlModal/QlModal'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { useUserFormat } from '@hooks/useUserFormat'
import { useDialogFocus } from '@hooks/useDialogFocus'
import { useAiSettings } from '@hooks/useAiSettings'
import {
  useChatHistory,
  useChatSuggestions,
  useCoachChat,
  useDeleteChatHistoryMutation,
  useInsights,
} from '@hooks/useCoach'
import { AI_SETTINGS_HASH, aiErrorCopy, TRUNCATED_STREAM_COPY } from './errorCopy'
import type { ApiError } from '@api/client'
import type { AiSettingsResponse, ChatTurnRole } from '@api/types'
import styles from './Coach.module.css'

/** `ChatTurnRequest.content` is capped at 4000 characters by the contract. */
const QUESTION_MAX = 4000

/** One page of stored turns is enough to rehydrate the conversation on screen. */
const HISTORY_PAGE = { page: 0, limit: 50 } as const

interface ShownTurn {
  key: string
  role: ChatTurnRole
  content: string
  streaming: boolean
}

/**
 * Whether the coach can actually be called.
 *
 * `enabled`, an endpoint and a chat model are what `createChatCompletion` needs;
 * without them the route answers 409 `ai-disabled`, which is a state to render,
 * not an error to report. The privacy tier is a separate, softer gate: the server
 * refuses a `CLOUD_OK` member with 403 before opening any connection, so the page
 * says so up front rather than letting them type a question into a refusal.
 */
function coachReadiness(settings: AiSettingsResponse | undefined) {
  if (!settings) return { configured: false, tierBlocks: false }
  return {
    configured: settings.enabled && settings.endpointUrl !== null && settings.model !== null,
    tierBlocks: settings.privacyTier === 'CLOUD_OK',
  }
}

function SettingsLink({ children }: { children: React.ReactNode }) {
  return <Link to={AI_SETTINGS_HASH}>{children}</Link>
}

function AiErrorNotice({ error, testId }: { error: ApiError; testId: string }) {
  const copy = aiErrorCopy(error)
  return (
    <div data-testid={testId}>
      <p className={copy.isRateLimit ? styles.status : styles.error} role="alert">
        {copy.message}
      </p>
      {copy.linksToSettings && (
        <p className={styles.hint}>
          <SettingsLink>Open your AI endpoint settings</SettingsLink>
        </p>
      )}
    </div>
  )
}

/* --------------------------------------------------------------- insights */

function InsightsPanel() {
  const insights = useInsights({ page: 0, limit: 10 })
  const format = useUserFormat()

  return (
    <QlCard>
      <div className={styles.stack}>
        <h2 className={styles.sectionTitle}>Weekly insights</h2>
        <p className={styles.note}>
          A short note about your own week, written once a week for the days Monday to Sunday in
          your own time zone. Reading them here does not mark them as delivered.
        </p>
        {insights.isPending && <QlSkeleton rows={2} data-testid="insights-loading" />}
        {insights.isError && <AiErrorNotice error={insights.error} testId="insights-error" />}
        {insights.data && insights.data.rows.length === 0 && (
          <p className={styles.note} data-testid="insights-empty">
            No insights yet. They are written for members whose AI endpoint is switched on, once a
            week.
          </p>
        )}
        {insights.data && insights.data.rows.length > 0 && (
          <ul className={styles.insightList} data-testid="insights-list">
            {insights.data.rows.map((note) => (
              <li key={note.id} className={styles.insight}>
                <span className={styles.insightWeek}>
                  Week of {format.formatDate(note.weekStart)}
                </span>
                {/* Model output: rendered, never interpreted. */}
                <span className={styles.insightBody}>{note.content}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </QlCard>
  )
}

/* ------------------------------------------------------------- clear dialog */

function ClearHistoryDialog({
  sessionId,
  onClose,
  onCleared,
}: {
  sessionId: string | null
  onClose: () => void
  onCleared: () => void
}) {
  const clear = useDeleteChatHistoryMutation()
  const ref = useDialogFocus<HTMLDivElement>(true)

  return (
    <QlModal isOpen onClose={onClose} title="Erase your coach history?">
      <div ref={ref} tabIndex={-1} className={styles.stack} data-testid="clear-history-dialog">
        <p className={styles.note}>
          {sessionId
            ? 'This erases this conversation and every message in it. Your other conversations are untouched.'
            : 'This erases every conversation you have had with the coach, and every message in them. They are yours alone — nobody else in your household can read them, and nobody else loses anything.'}
        </p>
        <p className={styles.note}>
          Your ledger, your receipts and your weekly insights are not affected. This cannot be
          undone.
        </p>
        {clear.isError && <AiErrorNotice error={clear.error} testId="clear-history-error" />}
        <div className={styles.actions}>
          <QlButton
            variant="primary"
            loading={clear.isPending}
            onClick={() => {
              clear.mutate(sessionId, {
                onSuccess: () => {
                  onCleared()
                  onClose()
                },
              })
            }}
          >
            {sessionId ? 'Erase this conversation' : 'Erase everything'}
          </QlButton>
          <QlButton variant="ghost" onClick={onClose}>
            Keep it
          </QlButton>
        </div>
      </div>
    </QlModal>
  )
}

/* -------------------------------------------------------------------- page */

export default function Coach() {
  const settings = useAiSettings()
  const suggestions = useChatSuggestions()
  const history = useChatHistory(HISTORY_PAGE)

  /**
   * The conversation to continue: the most recently used one, which the history
   * route returns first when no `sessionId` narrows it. Stored turns are RENDERED
   * from the query rather than copied into the chat machine — an effect that
   * copies query data into state is a cascading render, and this way a cleared
   * history disappears the moment the query invalidates.
   */
  const resumed = useMemo(() => {
    const rows = history.data?.rows ?? []
    const sessionId = rows[0]?.sessionId ?? null
    const turns = sessionId ? rows.filter((r) => r.sessionId === sessionId) : []
    return { sessionId, turns }
  }, [history.data])

  const chat = useCoachChat({ resumeSessionId: resumed.sessionId })
  const [question, setQuestion] = useState('')
  const [clearOpen, setClearOpen] = useState(false)

  const { configured, tierBlocks } = coachReadiness(settings.data)

  /**
   * Local turns win once this session has produced any; before that, the stored
   * ones — UNLESS the member has asked for a new conversation.
   *
   * `chat.detached` is what makes "New conversation" mean something. Without it
   * the fallback would re-render the conversation the member just left behind,
   * the control would look inert, and — worse — the erase dialog would say
   * "everything" (it reads `chat.sessionId`, which `reset` nulls) while the
   * screen still showed exactly one conversation. A destructive action must not
   * describe a scope wider than what is in front of the person confirming it.
   */
  const shownTurns: ShownTurn[] =
    chat.transcript.length > 0
      ? chat.transcript
      : chat.detached
        ? []
        : resumed.turns.map((t) => ({
            key: t.id,
            role: t.role,
            content: t.content,
            streaming: false,
          }))

  /**
   * There is history on the server even when this screen is showing an empty new
   * conversation, so "Erase history" stays reachable after "New conversation" —
   * the alternative hides the only control that clears it.
   */
  const hasStoredHistory = (history.data?.rows.length ?? 0) > 0

  const canSend = question.trim() !== '' && !chat.isStreaming && configured

  function send() {
    if (!canSend) return
    const asked = question
    setQuestion('')
    void chat.ask(asked)
  }

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.heading}>Coach</h1>
          <p className={styles.lede}>
            Questions about your own household&rsquo;s ledger, answered by the AI endpoint you
            configured. Trove sends aggregates &mdash; this month&rsquo;s totals, your envelope
            allocations, your subscription run rate &mdash; and never an account number, an access
            token or a receipt image.
          </p>
        </div>
        {(shownTurns.length > 0 || hasStoredHistory) && (
          <div className={styles.actions}>
            {shownTurns.length > 0 && (
              <QlButton variant="ghost" onClick={() => chat.reset()}>
                New conversation
              </QlButton>
            )}
            <QlButton variant="ghost" onClick={() => setClearOpen(true)}>
              Erase history
            </QlButton>
          </div>
        )}
      </header>

      {settings.isPending && <QlSkeleton rows={2} data-testid="coach-settings-loading" />}

      {settings.isError && (
        <QlCard>
          <p className={styles.error} role="alert" data-testid="coach-settings-error">
            Trove could not read your AI settings, so it cannot tell whether the coach is
            configured. Try again in a moment.
          </p>
        </QlCard>
      )}

      {settings.data && !configured && (
        <QlCard>
          <EmptyState
            title="The coach needs your own AI endpoint"
            data-testid="coach-not-configured"
            actions={
              <SettingsLink>
                <QlButton variant="primary">Set up your AI endpoint</QlButton>
              </SettingsLink>
            }
          >
            <p>
              Trove has no AI of its own and never sends your ledger anywhere you have not named.
              Point it at an OpenAI-compatible endpoint you control &mdash; a model on your own
              machine is the intended setup &mdash; choose a chat model, and turn it on. Until then
              the coach cannot answer, and nothing on this page is fabricated to look as though it
              could.
            </p>
          </EmptyState>
        </QlCard>
      )}

      {settings.data && configured && tierBlocks && (
        <QlCard>
          <div className={styles.stack} data-testid="coach-tier-warning">
            <p className={styles.error} role="status">
              Your privacy setting will refuse these questions. A coach question is built from your
              household&rsquo;s ledger, so it counts as sensitive, and sensitive payloads are only
              sent to an endpoint you have marked local.
            </p>
            <p className={styles.hint}>
              Change the privacy tier to &ldquo;Local only&rdquo; in{' '}
              <SettingsLink>your AI endpoint settings</SettingsLink>.
            </p>
          </div>
        </QlCard>
      )}

      <QlCard>
        <div className={styles.stack}>
          <h2 className={styles.sectionTitle}>Conversation</h2>

          {history.isPending && <QlSkeleton rows={2} data-testid="coach-history-loading" />}

          {shownTurns.length === 0 ? (
            <EmptyState title="Ask the coach something" data-testid="coach-empty">
              <p>
                Answers are text and are never acted on: nothing here can categorise a transaction,
                open a link or change your ledger.
              </p>
            </EmptyState>
          ) : (
            /* `role="log"` announces an ADDED turn and does not re-read the
               thread as tokens land. */
            <div
              className={styles.transcript}
              role="log"
              aria-label="Coach conversation"
              data-testid="coach-transcript"
            >
              {shownTurns.map((turn) => (
                <div
                  key={turn.key}
                  className={`${styles.turn} ${turn.role === 'USER' ? styles.turnUser : ''}`}
                  data-testid={turn.role === 'USER' ? 'coach-turn-user' : 'coach-turn-assistant'}
                >
                  <span className={styles.role}>{turn.role === 'USER' ? 'You' : 'Coach'}</span>
                  <p
                    className={`${styles.bubble} ${
                      turn.role === 'USER' ? styles.bubbleUser : styles.bubbleAssistant
                    }`}
                  >
                    {/* Plain text. Never markup, never a renderer, never sanitised
                        HTML — the contract says the answer is text. */}
                    {turn.content}
                    {turn.streaming && <span className={styles.caret} aria-hidden="true" />}
                  </p>
                </div>
              ))}
            </div>
          )}

          {/* Announced once per state change, rather than once per token. */}
          <p className={styles.srOnly} role="status" data-testid="coach-live-status">
            {chat.isStreaming
              ? 'The coach is answering.'
              : chat.endedBy === 'done'
                ? 'The coach has finished answering.'
                : ''}
          </p>

          {chat.error && <AiErrorNotice error={chat.error} testId="coach-error" />}

          {chat.streamError && (
            <AiErrorNotice error={chat.streamError} testId="coach-stream-error" />
          )}

          {chat.endedBy === 'truncated' && (
            <p className={styles.error} role="alert" data-testid="coach-truncated">
              {TRUNCATED_STREAM_COPY}
            </p>
          )}

          {chat.endedBy === 'aborted' && (
            <p className={styles.status} role="status" data-testid="coach-aborted">
              You stopped that answer. What arrived is kept.
            </p>
          )}

          <div className={styles.composer}>
            <label className={styles.role} htmlFor="coach-question">
              Your question
            </label>
            <textarea
              id="coach-question"
              className={styles.textarea}
              value={question}
              maxLength={QUESTION_MAX}
              disabled={!configured}
              aria-describedby="coach-question-counter"
              placeholder={
                configured
                  ? 'Ask about this month, a category, a subscription…'
                  : 'Configure your AI endpoint to ask a question'
              }
              onChange={(e) => setQuestion(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) send()
              }}
            />
            <span className={styles.counter} id="coach-question-counter">
              {question.length} of {QUESTION_MAX} characters. Press Ctrl or Cmd and Enter to send.
            </span>
            <div className={styles.actions}>
              <QlButton variant="primary" disabled={!canSend} onClick={send}>
                Ask the coach
              </QlButton>
              {chat.isStreaming && (
                <QlButton variant="ghost" onClick={() => chat.stop()}>
                  Stop answering
                </QlButton>
              )}
            </div>
          </div>

          {suggestions.data && suggestions.data.length > 0 && (
            <div className={styles.stack}>
              <span className={styles.role}>Suggestions</span>
              <ul className={styles.suggestions} data-testid="coach-suggestions">
                {suggestions.data.map((text) => (
                  <li key={text}>
                    <QlButton
                      variant="ghost"
                      size="sm"
                      disabled={!configured || chat.isStreaming}
                      onClick={() => setQuestion(text)}
                    >
                      {text}
                    </QlButton>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </QlCard>

      <InsightsPanel />

      {clearOpen && (
        <ClearHistoryDialog
          sessionId={chat.sessionId}
          onClose={() => setClearOpen(false)}
          onCleared={() => chat.reset()}
        />
      )}
    </div>
  )
}
