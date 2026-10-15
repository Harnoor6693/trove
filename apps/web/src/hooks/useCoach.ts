/**
 * The coach: its four ordinary queries and `useCoachChat`, the streaming state
 * machine.
 *
 * `useCoachChat` is where the SSE reader meets React, and its job is the three
 * things a naive chat hook gets wrong:
 *
 *  1. **It sends ONE user turn, never the transcript.** The transcript below is
 *     for the screen. `messages` on the wire is the question, because the server
 *     rebuilds the history from its own store and refuses a client-supplied
 *     `assistant` turn by design.
 *  2. **It aborts the REQUEST on unmount or cancel**, rather than ignoring the
 *     bytes of a request that is still running.
 *  3. **It keeps what already arrived when the stream ends badly.** An in-band
 *     `answer-too-long` or `outbound-url-rejected` frame is not a failure of the
 *     turn — the endpoint did not fail and the slug says what happened — so the
 *     partial answer stays on the screen with the reason beside it.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  deleteChatHistory,
  listChatHistory,
  listChatSuggestions,
  listInsights,
  streamCoachTurn,
} from '@api/coach'
import { toApiError, type ApiError } from '@api/client'
import { isAbort, type StreamEndReason } from '@api/sse'
import type {
  ChatHistoryQuery,
  ChatTurnRole,
  CoachMessageResponse,
  InsightResponse,
  InsightsQuery,
  Page,
} from '@api/types'

export const chatSuggestionsKey = ['chat', 'suggestions'] as const
export const chatHistoryKey = (q: ChatHistoryQuery) => ['chat', 'history', q] as const
export const insightsKey = (q: InsightsQuery) => ['insights', q] as const

/* ------------------------------------------------------------- the queries */

/**
 * `GET /v1/chat/suggestions` — the server's own prompt starters.
 *
 * It costs no AI call and needs no configured endpoint, so it is fetched even
 * when AI is off: the "configure your endpoint" state still shows what the coach
 * would be able to answer. Nothing here is a hardcoded client-side list.
 */
export function useChatSuggestions() {
  return useQuery<string[], ApiError>({
    queryKey: chatSuggestionsKey,
    queryFn: listChatSuggestions,
    staleTime: 5 * 60 * 1000,
  })
}

/** `GET /v1/chat/history` — paged. `meta` is consumed, never inferred from `rows.length`. */
export function useChatHistory(q: ChatHistoryQuery = {}, opts: { enabled?: boolean } = {}) {
  return useQuery<Page<CoachMessageResponse>, ApiError>({
    queryKey: chatHistoryKey(q),
    queryFn: () => listChatHistory(q),
    enabled: opts.enabled ?? true,
    staleTime: 0,
  })
}

/** `GET /v1/insights` — paged, newest first, and a safe GET: reading delivers nothing. */
export function useInsights(q: InsightsQuery = {}) {
  return useQuery<Page<InsightResponse>, ApiError>({
    queryKey: insightsKey(q),
    queryFn: () => listInsights(q),
    staleTime: 60 * 1000,
  })
}

/**
 * `DELETE /v1/chat/history` — destructive, and the argument says how much.
 *
 * `null` means every conversation the member has; a session id means that one.
 * It is never sent blank: `?sessionId=` is 400 rather than "all of them", and
 * reading the two alike would destroy a member's whole history.
 */
export function useDeleteChatHistoryMutation() {
  const qc = useQueryClient()
  return useMutation<void, ApiError, string | null>({
    mutationFn: deleteChatHistory,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['chat', 'history'] }),
  })
}

/* --------------------------------------------------------- the chat machine */

export interface CoachTurnEntry {
  /** Local id. The server's ids arrive only through the history route. */
  key: string
  role: ChatTurnRole
  content: string
  /** True while this assistant turn is still being written. */
  streaming: boolean
}

export interface CoachChat {
  transcript: CoachTurnEntry[]
  /** The conversation id, from `X-Session-Id`. Null until the first answer. */
  sessionId: string | null
  /**
   * True once `reset` has been called and before this session has asked
   * anything: "a NEW conversation, not the stored one".
   *
   * The screen needs it. A screen that falls back to the rehydrated turns
   * whenever `transcript` is empty would go on rendering the PREVIOUS
   * conversation after "New conversation" — the control would look inert, and
   * the erase dialog (which reads "everything" precisely because `sessionId` is
   * now null) would name a scope that does not match what is on the screen.
   */
  detached: boolean
  isStreaming: boolean
  /** A failure the server answered BEFORE the stream opened — 409, 403, 429, 502… */
  error: ApiError | null
  /** How the last stream ended. `truncated` means it stopped with no terminator. */
  endedBy: StreamEndReason | null
  /** The in-band frame's error, when one ended the stream. The partial answer is kept. */
  streamError: ApiError | null
  ask: (question: string) => Promise<void>
  /** Aborts the in-flight request, not merely its output. */
  stop: () => void
  /** Drops the local transcript and starts a new conversation on the next ask. */
  reset: () => void
}

export interface UseCoachChatOptions {
  /**
   * The conversation the screen rehydrated from `GET /v1/chat/history`, used
   * until a turn of this session has produced one of its own.
   *
   * It is passed IN rather than copied into state by an effect: an effect body
   * that calls `setState` synchronously is a cascading render, and the stored
   * turns are already query state that the screen can render directly.
   */
  resumeSessionId?: string | null
}

let turnCounter = 0
const nextKey = () => `turn-${(turnCounter += 1)}`

export function useCoachChat(opts: UseCoachChatOptions = {}): CoachChat {
  const [transcript, setTranscript] = useState<CoachTurnEntry[]>([])
  const [sessionId, setSessionId] = useState<string | null>(null)
  /** Set by `reset`: "start a new conversation", so the resumed id is dropped. */
  const [detached, setDetached] = useState(false)
  const [isStreaming, setIsStreaming] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [streamError, setStreamError] = useState<ApiError | null>(null)
  const [endedBy, setEndedBy] = useState<StreamEndReason | null>(null)

  const abortRef = useRef<AbortController | null>(null)
  /** Guards a `setState` after the component has gone — an abort still resolves. */
  const mountedRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      // The REQUEST is aborted, not merely its output ignored: a coach answer
      // can run for a minute, and a page the member has left must not hold a
      // connection open against their own endpoint.
      abortRef.current?.abort()
      abortRef.current = null
    }
  }, [])

  const stop = useCallback(() => {
    abortRef.current?.abort()
    abortRef.current = null
  }, [])

  const reset = useCallback(() => {
    stop()
    setTranscript([])
    setSessionId(null)
    setDetached(true)
    setError(null)
    setStreamError(null)
    setEndedBy(null)
  }, [stop])

  /**
   * The id the next turn continues. Omitting it starts a new conversation, which
   * is exactly what `reset` means; sending it back is what continues one.
   */
  const effectiveSessionId = sessionId ?? (detached ? null : (opts.resumeSessionId ?? null))

  const ask = useCallback(
    async (question: string) => {
      const trimmed = question.trim()
      if (trimmed === '' || abortRef.current !== null) return

      const controller = new AbortController()
      abortRef.current = controller
      const answerKey = nextKey()

      setError(null)
      setStreamError(null)
      setEndedBy(null)
      setIsStreaming(true)
      setTranscript((prev) => [
        ...prev,
        { key: nextKey(), role: 'USER', content: trimmed, streaming: false },
        { key: answerKey, role: 'ASSISTANT', content: '', streaming: true },
      ])

      try {
        const result = await streamCoachTurn({
          question: trimmed,
          sessionId: effectiveSessionId,
          signal: controller.signal,
          onDelta: (_delta, soFar) => {
            if (!mountedRef.current) return
            setTranscript((prev) =>
              prev.map((t) => (t.key === answerKey ? { ...t, content: soFar } : t))
            )
          },
        })
        if (!mountedRef.current) return
        // `X-Session-Id` is on every response; keeping it is what continues the
        // conversation on the next turn instead of starting a new one.
        if (result.sessionId) setSessionId(result.sessionId)
        setEndedBy(result.endedBy)
        setStreamError(result.error)
        setTranscript((prev) =>
          prev
            .map((t) => (t.key === answerKey ? { ...t, content: result.content, streaming: false } : t))
            // An answer that produced no text at all is not a turn worth keeping
            // on screen; the reason is rendered beside the composer instead.
            .filter((t) => !(t.key === answerKey && t.content === ''))
        )
      } catch (err) {
        if (!mountedRef.current) return
        if (isAbort(err)) {
          // The member pressed Stop (or left the page) BEFORE the response
          // headers arrived, so `readChatEventStream` never ran and could not
          // classify this for us. It is not a failure and must not be rendered
          // as one: `readTimeoutMs` goes up to ten minutes, so growing impatient
          // with a local model that has not answered yet is a NORMAL action, and
          // answering it with "Trove could not reach the server" would be a lie
          // about the member's own endpoint.
          setEndedBy('aborted')
          setTranscript((prev) =>
            prev
              .map((t) => (t.key === answerKey ? { ...t, streaming: false } : t))
              // Nothing arrived before the abort, so there is no answer to keep.
              .filter((t) => !(t.key === answerKey && t.content === ''))
          )
          return
        }
        // Everything else decidable before the first byte lands here as a real
        // status: 409 ai-disabled, 403 ai-policy-blocked, 429 rate-limited, 502, 504…
        setError(toApiError(err))
        setEndedBy('error')
        setTranscript((prev) => prev.filter((t) => t.key !== answerKey))
      } finally {
        if (abortRef.current === controller) abortRef.current = null
        if (mountedRef.current) setIsStreaming(false)
      }
    },
    [effectiveSessionId]
  )

  return {
    transcript,
    sessionId: effectiveSessionId,
    detached,
    isStreaming,
    error,
    endedBy,
    streamError,
    ask,
    stop,
    reset,
  }
}
