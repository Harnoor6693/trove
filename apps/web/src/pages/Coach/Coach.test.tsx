/**
 * Coach, by behaviour, against contract-shaped MSW handlers.
 *
 * The streamed cases drive the real reader over a real `text/event-stream` body
 * — `mswState.chatStreamChunks` is written verbatim, one `enqueue` per entry —
 * so the frame-boundary and terminator cases are exercised end to end rather
 * than only in `src/api/sse.test.ts`.
 */
import { describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import {
  DEFAULT_STREAM_ANSWER,
  makeConfiguredAiSettings,
  mswState,
  resetMswState,
} from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import Coach from './index'

function signIn() {
  useAuthStore.setState({
    status: 'authenticated',
    user: {
      userId: mswState.me.userId,
      email: mswState.me.email,
      displayName: mswState.me.displayName,
      householdId: mswState.me.householdId,
      role: mswState.me.role,
      totpEnabled: false,
      currency: mswState.me.currency,
      country: mswState.me.country,
      timezone: mswState.me.timezone,
      locale: mswState.me.locale,
      onboardingCompleted: true,
    },
  })
}

/** A member whose endpoint is configured and whose privacy tier allows the coach. */
function configured() {
  resetMswState({ aiSettings: makeConfiguredAiSettings(), chatMessages: [] })
  signIn()
}

/**
 * Type a question and send it.
 *
 * The composer is inert until `getAiSettings` has answered — the page will not
 * let a member type into a coach that is not configured — so this waits for that
 * before typing. Without the wait, `user.type` is a silent no-op on a disabled
 * textarea and every streaming case fails for the wrong reason.
 */
async function ask(question: string) {
  const user = userEvent.setup()
  const box = screen.getByLabelText('Your question')
  await waitFor(() => expect(box).toBeEnabled())
  await user.type(box, question)
  await user.click(await screen.findByRole('button', { name: 'Ask the coach' }))
  return user
}

describe('Coach — the configure state', () => {
  it('shows the "needs your own AI endpoint" state and links to the settings section', async () => {
    resetMswState({ chatMessages: [] })
    signIn()
    renderWithProviders(<Coach />)

    expect(await screen.findByTestId('coach-not-configured')).toBeInTheDocument()
    const link = screen.getByRole('link', { name: /set up your ai endpoint/i })
    expect(link).toHaveAttribute('href', '/settings#ai-endpoint')
    // The composer is inert rather than absent: the page explains itself.
    expect(screen.getByLabelText('Your question')).toBeDisabled()
  })

  it('still seeds the empty state with the SERVER’s own suggestions when AI is off', async () => {
    resetMswState({ chatMessages: [], chatSuggestions: ['How much did I spend on transport?'] })
    signIn()
    renderWithProviders(<Coach />)

    // `listChatSuggestions` costs no AI call and needs no endpoint.
    expect(
      await screen.findByRole('button', { name: 'How much did I spend on transport?' })
    ).toBeInTheDocument()
  })

  it('warns before the first question when the privacy tier will refuse it', async () => {
    resetMswState({
      aiSettings: makeConfiguredAiSettings({ privacyTier: 'CLOUD_OK' }),
      chatMessages: [],
    })
    signIn()
    renderWithProviders(<Coach />)

    expect(await screen.findByTestId('coach-tier-warning')).toHaveTextContent(/local/i)
  })
})

describe('Coach — streaming', () => {
  it('renders a streamed answer incrementally and reassembles a split frame', async () => {
    configured()
    renderWithProviders(<Coach />)
    await ask('What did I spend the most on?')

    // The default fixture splits `the mo|st on Groceries.` across two chunks.
    expect(await screen.findByText(DEFAULT_STREAM_ANSWER)).toBeInTheDocument()
    expect(screen.getByTestId('coach-turn-user')).toHaveTextContent(
      'What did I spend the most on?'
    )
  })

  it('sends exactly ONE user turn, never the transcript', async () => {
    configured()
    renderWithProviders(<Coach />)

    await ask('First question')
    await screen.findByText(DEFAULT_STREAM_ANSWER)
    await ask('Second question')
    await waitFor(() => expect(mswState.lastChatBody?.messages[0].content).toBe('Second question'))

    // One entry, `role: user`, and no assistant turn the page invented.
    expect(mswState.lastChatBody?.messages).toHaveLength(1)
    expect(mswState.lastChatBody?.messages[0].role).toBe('user')
  })

  it('round-trips the session id: the first turn omits it, the second sends it back', async () => {
    configured()
    renderWithProviders(<Coach />)

    await ask('First question')
    await screen.findByText(DEFAULT_STREAM_ANSWER)
    // No conversation existed, so nothing was sent.
    expect(mswState.lastChatSessionHeader).toBeNull()

    await ask('Second question')
    await waitFor(() =>
      expect(mswState.lastChatSessionHeader).toBe(mswState.chatSessionId)
    )
  })

  it('ends cleanly on [DONE] and announces completion once, not per token', async () => {
    configured()
    renderWithProviders(<Coach />)
    await ask('Anything')

    await waitFor(() =>
      expect(screen.getByTestId('coach-live-status')).toHaveTextContent(
        'The coach has finished answering.'
      )
    )
    // The transcript itself is a log: added turns are announced, token growth is not.
    expect(screen.getByTestId('coach-transcript')).toHaveAttribute('role', 'log')
  })

  it('surfaces an IN-BAND error frame with no [DONE] and KEEPS what already arrived', async () => {
    configured()
    mswState.chatStreamChunks = [
      'data: {"choices":[{"delta":{"content":"Your groceries came to "}}]}\n\n',
      'data: {"error":{"type":"https://trove.finance/errors/answer-too-long","title":"Bad Request","status":400,"detail":"d","instance":"/api/v1/chat/completions","requestId":"r"}}\n\n',
    ]
    renderWithProviders(<Coach />)
    await ask('Tell me everything')

    expect(await screen.findByTestId('coach-stream-error')).toHaveTextContent(/length limit/i)
    // The partial answer stays on screen: the endpoint did not fail.
    expect(screen.getByTestId('coach-turn-assistant')).toHaveTextContent(
      'Your groceries came to'
    )
  })

  it('names the setting for an in-band outbound-url-rejected frame', async () => {
    configured()
    mswState.chatStreamChunks = [
      'data: {"choices":[{"delta":{"content":"partial"}}]}\n\n',
      'data: {"error":{"type":"https://trove.finance/errors/outbound-url-rejected","title":"Bad Request","status":400,"detail":"Your endpoint must use https.","instance":"/i","requestId":"r"}}\n\n',
    ]
    renderWithProviders(<Coach />)
    await ask('Anything')

    const notice = await screen.findByTestId('coach-stream-error')
    expect(notice).toHaveTextContent('Your endpoint must use https.')
    expect(screen.getAllByRole('link', { name: /ai endpoint settings/i }).length).toBeGreaterThan(0)
  })

  it('handles a stream that ends with neither [DONE] nor an error frame', async () => {
    configured()
    mswState.chatStreamChunks = ['data: {"choices":[{"delta":{"content":"Cut off"}}]}\n\n']
    renderWithProviders(<Coach />)
    await ask('Anything')

    expect(await screen.findByTestId('coach-truncated')).toBeInTheDocument()
    expect(screen.getByText('Cut off')).toBeInTheDocument()
  })

  it('renders the answer as TEXT, never as markup', async () => {
    configured()
    mswState.chatStreamChunks = [
      'data: {"choices":[{"delta":{"content":"<img src=x onerror=alert(1)>"}}]}\n\ndata: [DONE]\n\n',
    ]
    const { container } = renderWithProviders(<Coach />)
    await ask('Anything')

    // The characters are on the page as characters…
    expect(await screen.findByText('<img src=x onerror=alert(1)>')).toBeInTheDocument()
    // …and no element was ever created from them.
    expect(container.querySelector('img')).toBeNull()
    expect(container.innerHTML).not.toContain('<img src=x')
  })

  it('aborts the request on unmount rather than merely ignoring it', async () => {
    configured()
    // A stream that never terminates: only an abort can end it, which is the
    // only way this assertion means anything.
    mswState.chatStreamHangs = true
    mswState.chatStreamChunks = ['data: {"choices":[{"delta":{"content":"Half an answer"}}]}\n\n']
    const abort = vi.spyOn(AbortController.prototype, 'abort')
    const { unmount } = renderWithProviders(<Coach />)
    await ask('Anything')
    // The stream is still open — nothing has terminated it.
    await screen.findByText('Half an answer')

    abort.mockClear()
    unmount()
    expect(abort).toHaveBeenCalled()
    abort.mockRestore()
  })
  it('stops an answer on request, keeping what arrived', async () => {
    configured()
    mswState.chatStreamHangs = true
    mswState.chatStreamChunks = ['data: {"choices":[{"delta":{"content":"Half an answer"}}]}\n\n']
    renderWithProviders(<Coach />)
    const user = await ask('Anything')

    const stop = await screen.findByRole('button', { name: 'Stop answering' })
    await user.click(stop)

    expect(await screen.findByTestId('coach-aborted')).toBeInTheDocument()
    // What arrived before the stop is kept.
    expect(screen.getByText('Half an answer')).toBeInTheDocument()
  })

  it('reads a Stop pressed BEFORE the first byte as a stop, not as an unreachable server', async () => {
    configured()
    // The route answers NOTHING — no status, no headers, no body — until the
    // request is aborted. This is the window the case above cannot reach: there
    // a chunk had already been written, so the abort landed inside the SSE
    // reader's read loop, which classifies it. Here the `fetch` itself rejects
    // and only the hook can tell "the member pressed Stop" from "the endpoint is
    // unreachable". With `readTimeoutMs` configurable up to ten minutes, losing
    // patience with a local model that has not answered yet is an ordinary act.
    mswState.chatResponseHangs = true
    renderWithProviders(<Coach />)
    const user = await ask('Anything')

    await user.click(await screen.findByRole('button', { name: 'Stop answering' }))

    expect(await screen.findByTestId('coach-aborted')).toBeInTheDocument()
    // NOT the network-failure copy, and not an error state at all.
    expect(screen.queryByTestId('coach-error')).toBeNull()
  })
})

describe('Coach — the failures the operation declares', () => {
  it('shows the ai-disabled copy for a 409 and links to settings', async () => {
    configured()
    mswState.chatFailure = { status: 409, slug: 'ai-disabled', title: 'Conflict' }
    renderWithProviders(<Coach />)
    await ask('Anything')

    expect(await screen.findByTestId('coach-error')).toHaveTextContent(/needs your own AI endpoint/i)
  })

  it('shows its own copy for a 403 ai-policy-blocked and names the setting that changes it', async () => {
    configured()
    mswState.chatFailure = { status: 403, slug: 'ai-policy-blocked', title: 'Forbidden' }
    renderWithProviders(<Coach />)
    await ask('Anything')

    const notice = await screen.findByTestId('coach-error')
    expect(notice).toHaveTextContent(/privacy/i)
    expect(notice).toHaveTextContent(/Local only/i)
  })

  it('reads a 429 as "slow down", not as a failure or a logout', async () => {
    configured()
    mswState.chatFailure = { status: 429, slug: 'rate-limited', title: 'Too Many Requests' }
    renderWithProviders(<Coach />)
    await ask('Anything')

    expect(await screen.findByTestId('coach-error')).toHaveTextContent(/slow down/i)
    expect(screen.getByTestId('coach-error')).toHaveTextContent(/session is fine/i)
    // A 429 must not have triggered a refresh.
    expect(mswState.refreshCalls).toBe(0)
  })

  it('shows the endpoint-unauthorized copy for a 502', async () => {
    configured()
    mswState.chatFailure = {
      status: 502,
      slug: 'ai-endpoint-unauthorized',
      title: 'Bad Gateway',
    }
    renderWithProviders(<Coach />)
    await ask('Anything')

    expect(await screen.findByTestId('coach-error')).toHaveTextContent(/rejected the stored API key/i)
  })
})

describe('Coach — history and insights', () => {
  it('rehydrates the most recent conversation from the stored turns', async () => {
    // A suggestion list that cannot collide with the stored turn's own text.
    resetMswState({
      aiSettings: makeConfiguredAiSettings(),
      chatSuggestions: ['Show me my subscriptions'],
    })
    signIn()
    renderWithProviders(<Coach />)

    expect(
      await screen.findByText('What did I spend the most on this month?')
    ).toBeInTheDocument()
    expect(screen.getByText('Groceries, across fourteen purchases.')).toBeInTheDocument()
  })

  it('confirms before erasing, says what goes, and clears the transcript on success', async () => {
    resetMswState({ aiSettings: makeConfiguredAiSettings() })
    signIn()
    const user = userEvent.setup()
    renderWithProviders(<Coach />)

    await screen.findByText('Groceries, across fourteen purchases.')
    await user.click(screen.getByRole('button', { name: 'Erase history' }))

    const dialog = await screen.findByTestId('clear-history-dialog')
    expect(dialog).toHaveTextContent(/cannot be undone/i)
    expect(dialog).toHaveTextContent(/ledger, your receipts and your weekly insights are not affected/i)

    await user.click(screen.getByRole('button', { name: /erase this conversation/i }))

    await waitFor(() => expect(screen.getByTestId('coach-empty')).toBeInTheDocument())
    // The stored session was named, not a blank parameter — `?sessionId=` is 400.
    expect(mswState.chatHistoryDeletes).toEqual(['00000000-0000-4000-8000-0000000000c1'])
  })

  it('empties the screen for a new conversation, so the erase dialog matches what is on it', async () => {
    resetMswState({ aiSettings: makeConfiguredAiSettings() })
    signIn()
    const user = userEvent.setup()
    renderWithProviders(<Coach />)

    await screen.findByText('Groceries, across fourteen purchases.')
    await user.click(screen.getByRole('button', { name: 'New conversation' }))

    // The control is not inert: the conversation it detached from is off screen…
    expect(await screen.findByTestId('coach-empty')).toBeInTheDocument()
    expect(screen.queryByText('Groceries, across fourteen purchases.')).toBeNull()

    // …and the erase dialog, which now covers EVERY conversation because there
    // is no current session id, says so — rather than offering to erase "this
    // conversation" while a different one is the only thing visible.
    await user.click(screen.getByRole('button', { name: 'Erase history' }))
    const dialog = await screen.findByTestId('clear-history-dialog')
    expect(dialog).toHaveTextContent(/every conversation you have had/i)
    expect(screen.getByRole('button', { name: 'Erase everything' })).toBeInTheDocument()
  })

  it('renders the weekly insights and never marks one delivered', async () => {
    resetMswState({ aiSettings: makeConfiguredAiSettings() })
    signIn()
    renderWithProviders(<Coach />)

    const list = await screen.findByTestId('insights-list')
    expect(list).toHaveTextContent('You spent less than the week before, mostly on dining out.')
    // Reading is a safe GET: nothing about `delivered` is sent back.
    expect(mswState.insights[0].delivered).toBe(false)
  })
})
