/**
 * `/v1/chat/*` and `/v1/insights` — the coach. Five operations.
 *
 * **The prefix is `/v1/chat`, not `/v1/coach`.** A `/v1/coach/**` path in an
 * older draft was a briefing error; the spec, the shipped page and the
 * rate-limit seed all say `/v1/chat`.
 *
 * Three rules from `createChatCompletion`'s description govern this module, and
 * each of them silently breaks a naive chat client:
 *
 *  1. **`messages` is the turn you are sending, not the history.** The server
 *     takes the LAST entry, which must be `role: user`, and rebuilds the
 *     preceding turns from its own store. Earlier entries are ignored, and a
 *     client-supplied `assistant` turn is refused by design — it would let a
 *     page put words into the model's mouth and quote them back as the coach's.
 *     So `sendCoachTurn` and `streamCoachTurn` take ONE question, not a
 *     transcript, and neither of them accepts an array.
 *  2. **Sessions.** `X-Session-Id` is set on EVERY response, streaming or not.
 *     Send it back to continue; omit it to start a new conversation. It is the
 *     conversation's own id, it carries no privileges, and presenting one that
 *     is not yours is 404 rather than 403. `createChatCompletion` declares it as
 *     a HEADER (`SessionIdHeader`) and nothing else; `listChatHistory` and
 *     `deleteChatHistory` declare it as a QUERY parameter (`SessionIdQuery`).
 *     Each is used where it is declared.
 *  3. **The answer is text and is never interpreted.** No id, category, link or
 *     command is taken from it, and it is rendered as text — never through
 *     `dangerouslySetInnerHTML`, and with no markdown renderer or HTML sanitiser
 *     added to do it.
 */
import { ApiError, api, sessionFetch, unwrap, unwrapVoid } from './client'
import { readChatEventStream, type ChatStreamResult } from './sse'
import type {
  ApiResponse,
  ChatCompletionRequest,
  ChatCompletionResponse,
  ChatHistoryQuery,
  CoachMessageResponse,
  InsightResponse,
  InsightsQuery,
  Page,
  PaginationMeta,
} from './types'

/** The response header the conversation's id arrives in, on every response. */
const SESSION_HEADER = 'X-Session-Id'

/** One question, and the conversation it belongs to. `null` starts a new one. */
export interface CoachTurn {
  question: string
  sessionId: string | null
}

/** Exactly one user turn. The server ignores anything before it; we send nothing before it. */
function oneUserTurn(question: string): ChatCompletionRequest {
  return { messages: [{ role: 'user', content: question }] }
}

function sessionHeader(sessionId: string | null): Record<string, string> {
  return sessionId ? { [SESSION_HEADER]: sessionId } : {}
}

/* ---------------------------------------------------- the non-streaming ask */

export interface CoachAnswer {
  answer: ChatCompletionResponse
  /** From `X-Session-Id`. Falls back to the body's own `sessionId`, which is the same value. */
  sessionId: string
}

/**
 * `POST /v1/chat/completions` with `stream` absent — the ordinary envelope with
 * the whole answer, and the endpoint's failures as real status codes (502 / 504)
 * rather than as frames.
 */
export async function sendCoachTurn(turn: CoachTurn): Promise<CoachAnswer> {
  const res = await api.post<ApiResponse<ChatCompletionResponse>>(
    '/v1/chat/completions',
    oneUserTurn(turn.question),
    { headers: sessionHeader(turn.sessionId) }
  )
  const answer = unwrap(res)
  const header = (res.headers as Record<string, string> | undefined)?.[
    SESSION_HEADER.toLowerCase()
  ]
  return { answer, sessionId: header ?? answer.sessionId }
}

/* -------------------------------------------------------- the streaming ask */

export interface CoachStreamResult extends ChatStreamResult {
  /** The conversation this answer belongs to, from `X-Session-Id`. */
  sessionId: string | null
  /**
   * True when the server answered `application/json` despite `stream=true` — it
   * is allowed to, and the whole answer is then in `content`.
   */
  wasStreamed: boolean
}

export interface StreamCoachTurnOptions extends CoachTurn {
  signal?: AbortSignal
  onDelta?: (text: string, soFar: string) => void
}

/**
 * `POST /v1/chat/completions?stream=true` — `text/event-stream`, read with
 * `fetch` and a `ReadableStream` reader.
 *
 * **Not `EventSource`.** It cannot POST, cannot carry a request body, and cannot
 * send the `X-Requested-With` and CSRF headers this API demands of a mutation.
 * The old Generation-B implementation used one against a `GET /api/coach/stream?q=`
 * that does not exist, and waited for a named `done` event that does not exist
 * either; it is deleted rather than ported.
 *
 * **`response.ok` and the content type are read BEFORE the body is treated as a
 * stream**, exactly as the operation instructs: everything decidable before the
 * first byte — an unknown session (404), AI switched off (409), the privacy tier
 * (403), a malformed body (400), the rate limiter (429) — is an ordinary JSON
 * `ApiResponse` whatever `stream` said, and only after those does the stream
 * open. Those reject with an `ApiError`. A failure AFTER the stream has opened
 * is an in-band frame instead, and comes back inside the result with whatever
 * text had already arrived still in `content`.
 */
export async function streamCoachTurn(
  opts: StreamCoachTurnOptions
): Promise<CoachStreamResult> {
  const res = await sessionFetch('/v1/chat/completions?stream=true', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...sessionHeader(opts.sessionId) },
    body: JSON.stringify(oneUserTurn(opts.question)),
    signal: opts.signal,
  })

  const sessionId = res.headers.get(SESSION_HEADER)
  const contentType = res.headers.get('content-type') ?? ''

  if (!contentType.includes('text/event-stream')) {
    // Allowed by the operation: a 200 that answered JSON anyway. The whole
    // answer is in the envelope, so it is reported as one complete delta rather
    // than as a stream that produced nothing.
    const body = (await res.json()) as ApiResponse<ChatCompletionResponse>
    if (!body.success || !body.data) {
      throw new ApiError({
        status: body.error?.status ?? res.status,
        type: body.error?.type,
        title: body.error?.title,
        detail: body.error?.detail,
        requestId: body.error?.requestId,
        fieldErrors: body.error?.fieldErrors ?? [],
        message: body.error?.detail ?? body.error?.title ?? 'The coach could not answer.',
      })
    }
    opts.onDelta?.(body.data.content, body.data.content)
    return {
      content: body.data.content,
      endedBy: 'done',
      error: null,
      unparseable: [],
      sessionId: sessionId ?? body.data.sessionId,
      wasStreamed: false,
    }
  }

  const result = await readChatEventStream(res.body, {
    onDelta: opts.onDelta,
    signal: opts.signal,
  })
  return { ...result, sessionId, wasStreamed: true }
}

/* ------------------------------------------------------ the rest of the coach */

/**
 * `GET /v1/chat/suggestions` — the server's own prompt starters, for the empty
 * state and the space under the composer.
 *
 * It costs no AI call and needs no configured endpoint: a member who has never
 * chatted and a member with AI switched off both get the default list rather
 * than an error, which is why the coach's empty state can be seeded before the
 * feature is even configured. Nothing here is hardcoded client-side.
 *
 * A bare array — the operation states this list is not paginated.
 */
export async function listChatSuggestions(): Promise<string[]> {
  const res = await api.get<ApiResponse<string[]>>('/v1/chat/suggestions')
  return unwrap(res)
}

/**
 * `GET /v1/chat/history` — **PAGED**, and its 200 requires BOTH `data` and
 * `meta`. `sessionId` narrows the page to one conversation; without it the page
 * spans every conversation the caller has, most recently used first and
 * chronological within each.
 *
 * **Absent and empty are not the same thing**: `?sessionId=` is 400, not "every
 * conversation". The parameter is omitted rather than sent blank, which matters
 * far more on the DELETE below.
 */
export async function listChatHistory(
  q: ChatHistoryQuery = {}
): Promise<Page<CoachMessageResponse>> {
  const params: Record<string, string | number> = {}
  if (q.sessionId !== undefined && q.sessionId !== '') params.sessionId = q.sessionId
  if (q.page !== undefined) params.page = q.page
  if (q.limit !== undefined) params.limit = q.limit

  const res = await api.get<ApiResponse<CoachMessageResponse[]>>('/v1/chat/history', { params })
  const rows = unwrap(res)
  const meta = res.data.meta
  if (!meta) {
    throw new Error('GET /v1/chat/history answered without the required pagination meta')
  }
  return { rows, meta: meta satisfies PaginationMeta }
}

/**
 * `DELETE /v1/chat/history` — one conversation when `sessionId` is given, and
 * EVERY conversation when it is not.
 *
 * The blank-versus-absent rule is load-bearing here: a URL built from a client
 * variable that was never set (`?sessionId=`) is 400 rather than "all of them",
 * because reading the two alike would destroy a member's whole history. This
 * function takes `null` to mean "all", never an empty string, and never sends
 * the parameter blank. `EmptySuccess`, so `unwrapVoid`.
 */
export async function deleteChatHistory(sessionId: string | null): Promise<void> {
  const params: Record<string, string> = {}
  if (sessionId !== null && sessionId !== '') params.sessionId = sessionId
  const res = await api.delete<ApiResponse<never>>('/v1/chat/history', { params })
  unwrapVoid(res)
}

/**
 * `GET /v1/insights` — **PAGED**. The caller's own weekly notes, newest first.
 *
 * The week each note describes is the recipient's own (Monday to Sunday in their
 * timezone) and its figures are in their own currency and jurisdiction, so
 * nothing here recomputes a boundary from the browser clock.
 *
 * `delivered` / `deliveredAt` say whether the note has been pushed anywhere
 * beyond this route. Reading the page does NOT set them — it is a safe GET, and
 * this client never marks anything delivered.
 */
export async function listInsights(q: InsightsQuery = {}): Promise<Page<InsightResponse>> {
  const params: Record<string, string | number> = {}
  if (q.page !== undefined) params.page = q.page
  if (q.limit !== undefined) params.limit = q.limit

  const res = await api.get<ApiResponse<InsightResponse[]>>('/v1/insights', { params })
  const rows = unwrap(res)
  const meta = res.data.meta
  if (!meta) {
    throw new Error('GET /v1/insights answered without the required pagination meta')
  }
  return { rows, meta: meta satisfies PaginationMeta }
}
