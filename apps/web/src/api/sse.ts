/**
 * THE SSE READER — the fetch-based reader of the coach's pinned frame contract.
 *
 * Landed by step 204 as a shared mechanic. It is deliberately two pieces: a pure
 * incremental parser (`parseSseBuffer`) that never touches the network, and a
 * reader (`readChatEventStream`) that pumps a `ReadableStream` through it. The
 * parser is where every one of the interesting bugs lives, and a pure function
 * is the only way to test them without racing a socket.
 *
 * ## The frame contract
 *
 * It is NOT derivable from `openapi/trove-core.yaml`: the `text/event-stream`
 * response of `createChatCompletion` is `type: string` in the document and the
 * grammar lives in that operation's description prose. The three frame shapes
 * below are therefore the only hand-written wire types in this family, they are
 * written once, here, next to the parser that reads them, and this is the
 * pointer back to their source:
 *
 *   openapi/trove-core.yaml → paths → /v1/chat/completions → post → description,
 *   section "### Streaming (`stream=true`)".
 *
 * Quoting it, because everything below is an implementation of these lines:
 *
 *     data: {"choices":[{"delta":{"content":"Your "}}]}
 *
 *     data: {"choices":[{"delta":{"content":"groceries "}}]}
 *
 *     data: [DONE]
 *
 * and a failure **after** the stream has opened arrives in band, as the last
 * frame, with no `[DONE]` after it:
 *
 *     data: {"error":{"type":"https://trove.finance/errors/ai-timeout", …}}
 *
 * ## The four things that break a naive reader
 *
 *  1. **A reader that only waits for `[DONE]` hangs for ever** on exactly the
 *     cases that matter — a timeout, an unreachable endpoint, an answer that hit
 *     the cap, a saved endpoint that has stopped passing the egress policy. All
 *     of those end the stream with an error frame and NO `[DONE]`.
 *  2. **A frame split across two chunk boundaries.** The classic bug. Nothing
 *     guarantees a network chunk ends on a frame boundary, so the parser buffers
 *     until the blank-line delimiter and hands back the unconsumed remainder.
 *  3. **A stream that ends with neither `[DONE]` nor an error frame** — a dropped
 *     connection. That is `truncated`: what arrived is kept, and the screen says
 *     the answer stopped early rather than spinning.
 *  4. **A non-JSON frame.** Reported as `unparseable` and skipped, never thrown:
 *     one malformed frame must not discard an answer that is otherwise fine.
 *
 * What arrived before an `answer-too-long` or `outbound-url-rejected` frame IS
 * KEPT: the endpoint did not fail and the slug says what happened. That is why
 * `ChatStreamResult` carries `content` alongside `error` rather than either/or.
 */
import { ApiError } from './client'
import type { ErrorResponse } from './types'

/* ------------------------------------------------------------ frame types */

/**
 * One delta frame. The streaming body keeps OpenAI's `choices[]/delta` shape —
 * unlike the non-streaming `ChatCompletionResponse`, which deliberately does not
 * — because that is what both clients' SSE readers parse.
 */
export interface ChatDeltaFrame {
  choices?: { delta?: { content?: string | null } | null }[] | null
}

/** The in-band failure frame. Its payload is the ordinary problem-details body. */
export interface ChatErrorFrame {
  error: ErrorResponse
}

/** The literal terminator. Not JSON, so it is compared before parsing. */
export const SSE_DONE = '[DONE]'

/** One decoded frame. `unparseable` carries the raw text for a diagnostic, never for display. */
export type SseEvent =
  | { kind: 'delta'; text: string }
  | { kind: 'done' }
  | { kind: 'error'; error: ErrorResponse }
  | { kind: 'unparseable'; raw: string }

/* ---------------------------------------------------------------- parsing */

function isErrorFrame(value: unknown): value is ChatErrorFrame {
  if (!value || typeof value !== 'object') return false
  const error = (value as ChatErrorFrame).error
  return Boolean(error) && typeof error === 'object'
}

/**
 * The text of one `data:` payload, turned into an event.
 *
 * A frame with `choices` but no `content` (the endpoint's opening frame, which
 * carries only a role) is a delta of the empty string rather than an error — it
 * is a legal frame that simply adds nothing.
 */
export function parseSseData(data: string): SseEvent {
  if (data === SSE_DONE) return { kind: 'done' }
  let parsed: unknown
  try {
    parsed = JSON.parse(data)
  } catch {
    return { kind: 'unparseable', raw: data }
  }
  if (isErrorFrame(parsed)) return { kind: 'error', error: parsed.error }
  if (parsed && typeof parsed === 'object' && 'choices' in parsed) {
    const frame = parsed as ChatDeltaFrame
    const text = frame.choices?.[0]?.delta?.content
    return { kind: 'delta', text: typeof text === 'string' ? text : '' }
  }
  return { kind: 'unparseable', raw: data }
}

/**
 * Pull every COMPLETE frame out of a buffer, returning the events and the bytes
 * that are not a whole frame yet.
 *
 * A frame ends at a blank line — `\n\n`, or `\r\n\r\n` from a server that writes
 * CRLF. Everything after the last delimiter is returned as `rest` and must be
 * fed back in with the next chunk; that, and nothing else, is what makes a frame
 * split across two chunk boundaries arrive whole.
 *
 * A frame may carry several `data:` lines (the SSE spec joins them with a
 * newline). The coach writes one, but joining rather than taking the first costs
 * nothing and is what the format says. Lines that are not `data:` — a comment
 * keep-alive `:`, an `event:` or an `id:` — are ignored, and a frame with no
 * `data:` line at all yields no event.
 */
export function parseSseBuffer(buffer: string): { events: SseEvent[]; rest: string } {
  const events: SseEvent[] = []
  let rest = buffer

  for (;;) {
    const match = /\r?\n\r?\n/.exec(rest)
    if (!match) break
    const frame = rest.slice(0, match.index)
    rest = rest.slice(match.index + match[0].length)

    const payload = frame
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      // `data: x` and `data:x` are the same frame — one optional leading space.
      .map((line) => line.slice('data:'.length).replace(/^ /, ''))
      .join('\n')
    if (payload === '') continue
    events.push(parseSseData(payload))
  }

  return { events, rest }
}

/* ----------------------------------------------------------------- reader */

/** How a stream stopped. Every one of the four is a state the screen renders. */
export type StreamEndReason = 'done' | 'error' | 'truncated' | 'aborted'

export interface ChatStreamResult {
  /** Everything that arrived, in order. Kept whatever the end reason is. */
  content: string
  endedBy: StreamEndReason
  /** Set only when `endedBy` is `error`: the in-band frame, as an `ApiError`. */
  error: ApiError | null
  /** Raw payloads that were not parseable. Never rendered; a diagnostic only. */
  unparseable: string[]
}

export interface ReadChatEventStreamOptions {
  /** Called with each delta as it arrives, for incremental rendering. */
  onDelta?: (text: string, soFar: string) => void
  /**
   * The SAME signal that was passed to the `fetch` that produced this response.
   *
   * Passing it here is not belt and braces, it is required: aborting a request
   * does not, in every runtime, reject a `reader.read()` that is already
   * awaiting bytes — it does not in this app's own test environment, where the
   * read simply never settles. A reader that relies on the fetch alone therefore
   * hangs for ever on the one case the Stop control exists for. So the read is
   * raced against the signal, and the reader is cancelled when it fires.
   */
  signal?: AbortSignal
}

/** The in-band error frame, as the same `ApiError` every other call site handles. */
export function errorFrameToApiError(error: ErrorResponse): ApiError {
  return new ApiError({
    status: error.status ?? 0,
    type: error.type,
    title: error.title,
    detail: error.detail,
    requestId: error.requestId,
    fieldErrors: error.fieldErrors ?? [],
    message: error.detail ?? error.title ?? 'The answer stopped early.',
  })
}

/**
 * Pump an open `text/event-stream` body through the parser.
 *
 * Never throws for a stream-level outcome — an in-band error, a truncation and
 * an abort are all returned as a `ChatStreamResult`, because in every one of
 * them there may be an answer on the screen that must not be thrown away with
 * the exception. It throws only if the caller hands it a response with no body,
 * which is a contract violation rather than a stream outcome.
 *
 * The abort is the CALLER's: pass the same `AbortSignal` to the `fetch` that
 * produced this response, so an unmount aborts the REQUEST rather than merely
 * ignoring its bytes.
 */
export async function readChatEventStream(
  body: ReadableStream<Uint8Array> | null,
  opts: ReadChatEventStreamOptions = {}
): Promise<ChatStreamResult> {
  if (!body) {
    throw new ApiError({
      status: 0,
      message: 'The coach answered with a stream that had no body.',
    })
  }

  const reader = body.getReader()
  const decoder = new TextDecoder()
  const abortRejection = abortRejectionFor(opts.signal)
  let buffer = ''
  let content = ''
  const unparseable: string[] = []
  let endedBy: StreamEndReason = 'truncated'
  let errorHolder: ApiError | null = null

  const consume = (events: SseEvent[]): boolean => {
    for (const event of events) {
      if (event.kind === 'delta') {
        if (event.text !== '') {
          content += event.text
          opts.onDelta?.(event.text, content)
        }
      } else if (event.kind === 'unparseable') {
        unparseable.push(event.raw)
      } else {
        endedBy = event.kind === 'done' ? 'done' : 'error'
        if (event.kind === 'error') errorHolder = errorFrameToApiError(event.error)
        return true
      }
    }
    return false
  }

  try {
    for (;;) {
      const { done, value } = abortRejection
        ? await Promise.race([reader.read(), abortRejection])
        : await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const { events, rest } = parseSseBuffer(buffer)
      buffer = rest
      if (consume(events)) break
    }
    if (endedBy === 'truncated') {
      // A well-behaved server ends on a delimiter, but a stream that stopped
      // mid-frame can still leave a whole frame in the buffer with no trailing
      // blank line. Flush it rather than losing the terminator it may hold.
      buffer += decoder.decode()
      const tail = buffer.trim()
      if (tail !== '') {
        const { events } = parseSseBuffer(`${buffer}\n\n`)
        consume(events)
      }
    }
  } catch (err) {
    // An abort surfaces here as the reader rejecting. It is not a failure: the
    // member cancelled, or the component unmounted, and what arrived is kept.
    if (isAbort(err)) {
      endedBy = 'aborted'
    } else {
      endedBy = 'error'
      errorHolder = asApiError(err)
    }
  } finally {
    if (endedBy === 'aborted') {
      // Cancelling the reader is what actually tears the body down; releasing
      // the lock alone would leave the stream open behind it.
      void reader.cancel().catch(() => undefined)
    } else {
      try {
        reader.releaseLock()
      } catch {
        /* already released */
      }
    }
  }

  return { content, endedBy, error: endedBy === 'error' ? errorHolder : null, unparseable }
}

/**
 * A promise that rejects when the signal fires, and never resolves.
 *
 * One per read loop, not one per iteration: a listener added on every `read()`
 * would accumulate for the length of the answer. The `catch` is there because a
 * rejection that arrives after the loop has already finished has nobody left
 * awaiting it, and an unhandled rejection would fail the suite.
 */
function abortRejectionFor(signal: AbortSignal | undefined): Promise<never> | null {
  if (!signal) return null
  const rejection = new Promise<never>((_resolve, reject) => {
    const onAbort = () =>
      reject(new DOMException('The operation was aborted.', 'AbortError'))
    if (signal.aborted) onAbort()
    else signal.addEventListener('abort', onAbort, { once: true })
  })
  rejection.catch(() => undefined)
  return rejection
}

/**
 * "This rejection is the caller's own abort, not a failure."
 *
 * EXPORTED because the read loop below is not the only place an abort can land.
 * A `stop()` or an unmount BEFORE the response headers arrive rejects the
 * `fetch` itself, long before there is a body to read, so the caller
 * (`useCoachChat`) has to make the same distinction — otherwise a member's own
 * Stop is rendered as "Trove could not reach the server", which is both wrong
 * and alarming.
 */
export function isAbort(err: unknown): boolean {
  if (err instanceof DOMException && err.name === 'AbortError') return true
  return Boolean(err) && typeof err === 'object' && (err as { name?: string }).name === 'AbortError'
}

function asApiError(err: unknown): ApiError {
  if (err instanceof ApiError) return err
  return new ApiError({
    status: 0,
    isNetworkError: true,
    message: err instanceof Error ? err.message : String(err),
  })
}
