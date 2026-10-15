/**
 * The SSE reader — one of the two mechanics step 214 lands for the families
 * that follow.
 *
 * ## Why this is not an `EventSource`
 *
 * The pre-collapse app used `react-native-event-source` against
 * `GET /api/coach/stream?message=…` and waited for a named `done` event. Three
 * things were wrong with that and all three are load-bearing:
 *
 *  - `EventSource` **cannot POST**, and `createChatCompletion` is a POST with
 *    a JSON body.
 *  - `EventSource` **cannot carry headers**, so it could not send the bearer
 *    token or `X-Session-Id`; the old code smuggled the question through the
 *    query string instead, which puts a member's question in every access log
 *    between here and the server.
 *  - There is **no named `done` event**. The stream ends with a
 *    `data: [DONE]` frame, and a failure after the response opened arrives
 *    in band as `data: {"error":{…}}` with **no `[DONE]` after it**. A reader
 *    that waits for `done` hangs for ever on a timeout, an unreachable
 *    endpoint, an answer that hit the cap, or an endpoint that has stopped
 *    passing the egress policy.
 *
 * ## Does incremental body reading work on this runtime?
 *
 * **`fetch` does not stream here, and `XMLHttpRequest` does.** React Native
 * 0.83 polyfills `fetch` with `whatwg-fetch` over its own `XMLHttpRequest`
 * (`react-native/Libraries/Network/fetch.js` is a one-line re-export of the
 * `whatwg-fetch` globals). That polyfill exposes no `ReadableStream` — Hermes
 * has no `ReadableStream` global for it to build one from — so
 * `response.body` is `undefined` and `response.body.getReader()` is not a
 * thing a screen can await. Reading the body means `await response.text()`,
 * which resolves once, at the end, after the whole answer has been generated.
 *
 * React Native's `XMLHttpRequest`, by contrast, genuinely delivers a text
 * response incrementally: the native side pushes
 * `__didReceiveIncrementalData`, which appends to `responseText` and fires a
 * `readystatechange` at `LOADING` for each arrival. It only does so once a
 * `readystatechange` or `progress` listener has been added — the class sets
 * `_incrementalEvents` in `addEventListener` — which is why
 * {@link xhrChunkSource} attaches one before it sends.
 *
 * So this module keeps **both** transports behind one seam and picks at run
 * time: {@link supportsFetchStreaming} is true on an engine whose `fetch`
 * really does expose a `ReadableStream` body (a browser, a future Hermes, the
 * web client's runtime), and false on this one, where the XHR path takes over.
 * Neither path buffers the answer, and no third "fall back to the
 * non-streaming form" branch is needed — that form stays available as
 * {@link ../api/coach.askCoach}, which is a first-class mode of the contract
 * rather than a workaround.
 *
 * ## What the reader must survive
 *
 * Every one of these is a real ending of this operation, and every one of them
 * has its own outcome below rather than a shared "failed":
 *
 *  - `data: [DONE]`                          → `'done'`
 *  - `data: {"error":{…}}` and then silence  → `'error'`, **keeping the text**
 *  - the body ending with neither            → `'truncated'`, keeping the text
 *  - the member pressing Stop, or unmount    → `'aborted'`, keeping the text
 *  - a frame split across two chunks         → buffered to the delimiter
 *  - a frame that is not JSON                → skipped, the stream continues
 */

import { API_CONTEXT_PATH, NoServerConnectedError, getAccessToken } from './client';
import { getBaseUrl } from './serverConfig';
import type { ErrorDetail } from './types';

// ---------------------------------------------------------------------------
// Frames
// ---------------------------------------------------------------------------

/**
 * Splits a byte-agnostic character stream into SSE `data:` payloads.
 *
 * Frames are delimited by a blank line, and a chunk boundary can fall
 * anywhere — including inside a UTF-8 word, inside a JSON string, or between
 * the `\n` and the `\n` of the delimiter — so the residue is held until the
 * delimiter actually arrives. Both `\n\n` and `\r\n\r\n` are accepted.
 */
/**
 * The most residue one unterminated frame may accumulate, in characters.
 *
 * A frame of this operation is a few hundred bytes; 64 KiB with no blank line
 * in it is not a slow frame, it is a body that is never going to deliver one —
 * a proxy that rewrote the stream, or a transport handing back something that
 * is not SSE at all. Without a ceiling that body grows one JS string until the
 * device runs out of memory, with no `[DONE]`, no error frame and no change on
 * screen: the one ending this reader would otherwise not survive. The server's
 * own cap is enforced on the answer it stores and arrives as an in-band
 * `answer-too-long` frame; it is not a bound on what something between here
 * and the server may hand over.
 */
export const MAX_FRAME_RESIDUE_CHARS = 64 * 1024;

/**
 * The most an answer may accumulate before the reader stops, in characters.
 *
 * Generous — several times the longest answer this operation stores — and
 * finite, which is the point.
 */
export const MAX_ANSWER_CHARS = 512 * 1024;

/** Thrown by {@link SseFrameBuffer.push} when a frame passes the ceiling. */
export class SseFrameTooLargeError extends Error {
  constructor() {
    super('The stream sent a frame with no end to it.');
    this.name = 'SseFrameTooLargeError';
  }
}

export class SseFrameBuffer {
  private buffer = '';

  /**
   * Feed one chunk; get back every complete frame's `data` payload it
   * finished.
   *
   * Throws {@link SseFrameTooLargeError} when the residue passes
   * {@link MAX_FRAME_RESIDUE_CHARS} — `readSseStream` turns that into the
   * `'truncated'` ending, which keeps what arrived and is a state the screen
   * already renders.
   */
  push(chunk: string): string[] {
    this.buffer += chunk;
    const frames: string[] = [];
    // eslint-disable-next-line no-constant-condition
    while (true) {
      const boundary = nextBoundary(this.buffer);
      if (boundary === null) break;
      const raw = this.buffer.slice(0, boundary.index);
      this.buffer = this.buffer.slice(boundary.index + boundary.length);
      const data = dataOf(raw);
      if (data !== null) frames.push(data);
    }
    if (this.buffer.length > MAX_FRAME_RESIDUE_CHARS) {
      // Drop the residue before throwing: nothing downstream will ever be able
      // to use it, and holding it is the leak this ceiling exists to stop.
      this.buffer = '';
      throw new SseFrameTooLargeError();
    }
    return frames;
  }

  /**
   * The residue, when the body ended without a final blank line.
   *
   * Delivered rather than dropped on purpose: a server that closes the
   * connection straight after writing `data: [DONE]` has sent a complete
   * frame, and treating it as a truncation would report a clean ending as a
   * broken one.
   */
  flush(): string[] {
    const rest = this.buffer;
    this.buffer = '';
    const data = dataOf(rest);
    return data === null ? [] : [data];
  }
}

function nextBoundary(buffer: string): { index: number; length: number } | null {
  const lf = buffer.indexOf('\n\n');
  const crlf = buffer.indexOf('\r\n\r\n');
  if (lf === -1 && crlf === -1) return null;
  if (crlf !== -1 && (lf === -1 || crlf < lf)) return { index: crlf, length: 4 };
  return { index: lf, length: 2 };
}

/**
 * The `data` of one frame, per the SSE grammar: every `data:` line joined with
 * a newline, comments (`:` first) and unknown fields ignored. `null` when the
 * frame carried no `data` field at all — a keep-alive comment, or whitespace.
 */
function dataOf(raw: string): string | null {
  const lines = raw.split(/\r?\n/);
  const parts: string[] = [];
  for (const line of lines) {
    if (line === '' || line.startsWith(':')) continue;
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    if (field !== 'data') continue;
    const value = colon === -1 ? '' : line.slice(colon + 1);
    parts.push(value.startsWith(' ') ? value.slice(1) : value);
  }
  return parts.length === 0 ? null : parts.join('\n');
}

/** The sentinel that ends a successful stream. */
export const SSE_DONE = '[DONE]';

/** What one decoded frame turned out to mean. */
export type CoachFrame =
  | { kind: 'delta'; content: string }
  | { kind: 'error'; detail: ErrorDetail }
  | { kind: 'done' }
  | { kind: 'ignored' };

/**
 * Decode one `data` payload.
 *
 * A payload that is not JSON is `'ignored'` rather than an error: the stream
 * is still open and the frames after it are still the member's answer. Only
 * an explicit `{"error":…}` frame ends it.
 */
export function decodeCoachFrame(data: string): CoachFrame {
  const trimmed = data.trim();
  if (trimmed === '') return { kind: 'ignored' };
  if (trimmed === SSE_DONE) return { kind: 'done' };

  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return { kind: 'ignored' };
  }
  if (typeof parsed !== 'object' || parsed === null) return { kind: 'ignored' };

  const record = parsed as { error?: unknown; choices?: unknown };

  if (typeof record.error === 'object' && record.error !== null) {
    const detail = record.error as Partial<ErrorDetail>;
    return {
      kind: 'error',
      detail: { ...detail, status: typeof detail.status === 'number' ? detail.status : 0 },
    };
  }

  if (Array.isArray(record.choices)) {
    const first = record.choices[0] as { delta?: { content?: unknown } } | undefined;
    const content = first?.delta?.content;
    if (typeof content === 'string' && content !== '') return { kind: 'delta', content };
  }

  return { kind: 'ignored' };
}

// ---------------------------------------------------------------------------
// Reading a stream
// ---------------------------------------------------------------------------

/** How a stream ended. Every one of these is a real ending of this operation. */
export type StreamEnding = 'done' | 'error' | 'truncated' | 'aborted';

export interface StreamOutcome {
  /** Everything the deltas carried, in order. **Kept on every ending**, errors included. */
  content: string;
  ending: StreamEnding;
  /** The in-band problem details, on `ending === 'error'` only. */
  error: ErrorDetail | null;
}

/** A transport's body, as text, in the order it arrived. */
export type ChunkSource = AsyncIterable<string>;

export interface ReadOptions {
  /** Called with the running answer each time a delta lands, for incremental render. */
  onDelta?: (content: string, whole: string) => void;
  /** Aborts the read. What arrived before the abort is still returned. */
  signal?: AbortSignal;
}

/**
 * Drive a chunk source to the end of the stream.
 *
 * Written against the seam rather than against `fetch` or `XMLHttpRequest` so
 * that the frame handling — which is the part that has to be right — is
 * testable with plain strings, the way `client.test.ts` drives a mock adapter
 * rather than a mocked axios.
 */
export async function readSseStream(
  chunks: ChunkSource,
  { onDelta, signal }: ReadOptions = {}
): Promise<StreamOutcome> {
  const buffer = new SseFrameBuffer();
  let content = '';
  let ending: StreamEnding | null = null;
  let error: ErrorDetail | null = null;

  const consume = (payloads: string[]): boolean => {
    for (const payload of payloads) {
      const frame = decodeCoachFrame(payload);
      if (frame.kind === 'delta') {
        content += frame.content;
        onDelta?.(frame.content, content);
        if (content.length > MAX_ANSWER_CHARS) {
          // A stream that keeps writing deltas and never writes `[DONE]` or an
          // error frame. Ending it as a truncation keeps every character that
          // arrived and says so, which is what the screen renders.
          ending = 'truncated';
          return true;
        }
      } else if (frame.kind === 'done') {
        ending = 'done';
        return true;
      } else if (frame.kind === 'error') {
        // The last frame of a failed stream. There is no `[DONE]` after it,
        // and what arrived before it stays on the member's screen.
        ending = 'error';
        error = frame.detail;
        return true;
      }
    }
    return false;
  };

  // Read through a function, not a narrowed expression: `signal.aborted` flips
  // under us while the loop is parked, and a direct comparison lets TypeScript
  // narrow it to `false` for the rest of the body.
  const aborted = (): boolean => signal?.aborted === true;

  if (aborted()) return { content, ending: 'aborted', error };

  try {
    for await (const chunk of chunks) {
      // Consume **before** checking the signal: a chunk the transport had
      // already received when Stop was pressed is text that arrived, and
      // everything that arrived is kept. Only then does the abort end the read.
      if (consume(buffer.push(chunk))) break;
      if (aborted()) {
        ending = 'aborted';
        break;
      }
    }
  } catch (err) {
    if (!(err instanceof SseFrameTooLargeError)) throw err;
    // A body with no frame delimiter in it. Truncation is the honest ending:
    // what arrived is kept, and the reader stops rather than growing one
    // string until the device runs out of memory.
    return { content, ending: 'truncated', error };
  }

  if (ending === null) {
    // The body ended. Deliver a frame the server wrote without a trailing
    // blank line, then decide between a clean ending and a truncation.
    consume(buffer.flush());
  }

  // A transport that stopped producing *because* it was aborted ends the loop
  // normally; that is an abort, not a truncation, and the two mean different
  // things to the member.
  if (ending === null && aborted()) ending = 'aborted';

  return { content, ending: ending ?? 'truncated', error };
}

// ---------------------------------------------------------------------------
// Transports
// ---------------------------------------------------------------------------

export interface SseRequest {
  /** A `/v1/...` path, exactly as every other call site writes one. */
  path: string;
  /** Serialised JSON body. */
  body: string;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

export interface SseResponse {
  status: number;
  /** Lower-cased header names. */
  headers: Record<string, string>;
  /** The body, chunk by chunk. Drain and join it when the body was JSON. */
  chunks: ChunkSource;
}

export type SseTransport = (request: SseRequest, url: string) => Promise<SseResponse>;

/**
 * Whether this engine's `fetch` really streams.
 *
 * False on React Native (see the file header): `whatwg-fetch` builds no
 * `body` because Hermes has no `ReadableStream`. Exported so a test can pin
 * the branch rather than depend on which engine the suite happens to run on.
 */
export function supportsFetchStreaming(): boolean {
  const scope = globalThis as Record<string, unknown>;
  if (typeof scope.fetch !== 'function') return false;
  if (typeof scope.ReadableStream !== 'function') return false;
  const responseCtor = scope.Response;
  if (typeof responseCtor !== 'function') return false;
  const prototype = (responseCtor as { prototype?: unknown }).prototype;
  return typeof prototype === 'object' && prototype !== null && 'body' in prototype;
}

interface StreamingBody {
  getReader(): {
    read(): Promise<{ done: boolean; value?: Uint8Array }>;
    cancel(): Promise<void> | void;
  };
}

/** `fetch` + `response.body.getReader()`, for an engine that has one. */
export const fetchChunkSource: SseTransport = async (request, url) => {
  const response = await (globalThis.fetch as typeof fetch)(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...request.headers },
    body: request.body,
    signal: request.signal,
  });

  const headers: Record<string, string> = {};
  response.headers.forEach((value: string, key: string) => {
    headers[key.toLowerCase()] = value;
  });

  const body = (response as unknown as { body?: StreamingBody }).body;

  async function* stream(): AsyncGenerator<string> {
    if (body === undefined || body === null || typeof body.getReader !== 'function') {
      // Defensive: `supportsFetchStreaming()` said yes but this response has
      // no reader. One buffered chunk is still a correct stream of length one.
      yield await response.text();
      return;
    }
    const reader = body.getReader();
    const decoder = new TextDecoder();
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        if (value !== undefined) yield decoder.decode(value, { stream: true });
      }
      const tail = decoder.decode();
      if (tail !== '') yield tail;
    } finally {
      await reader.cancel();
    }
  }

  return { status: response.status, headers, chunks: stream() };
};

const XHR_LOADING = 3;
const XHR_DONE = 4;

/**
 * `XMLHttpRequest` with incremental `readystatechange`, which is what actually
 * streams on React Native.
 *
 * The listener is attached **before** `send()`: React Native's
 * `XMLHttpRequest.addEventListener` is what flips `_incrementalEvents`, and
 * without it the native side batches the whole body into one `DONE`.
 */
export const xhrChunkSource: SseTransport = (request, url) =>
  new Promise<SseResponse>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', url);
    xhr.responseType = 'text';
    xhr.setRequestHeader('Content-Type', 'application/json');
    for (const [name, value] of Object.entries(request.headers ?? {})) {
      xhr.setRequestHeader(name, value);
    }

    let delivered = 0;
    let settled = false;
    const queue: string[] = [];
    /** Set while the generator is parked waiting for the next arrival. */
    let wake: (() => void) | null = null;
    let finished = false;
    let failure: Error | null = null;

    const notify = () => {
      const resume = wake;
      wake = null;
      resume?.();
    };

    const take = () => {
      // `responseText` accumulates; only the new tail is a chunk.
      const whole = xhr.responseText ?? '';
      if (whole.length > delivered) {
        queue.push(whole.slice(delivered));
        delivered = whole.length;
      }
    };

    const onAbort = () => {
      finished = true;
      notify();
      try {
        xhr.abort();
      } catch {
        // An abort after the request already settled is not an error.
      }
    };
    request.signal?.addEventListener('abort', onAbort);

    const settle = () => {
      if (settled) return;
      settled = true;
      const headers: Record<string, string> = {};
      const raw = xhr.getAllResponseHeaders() ?? '';
      for (const line of raw.split(/\r?\n/)) {
        const colon = line.indexOf(':');
        if (colon === -1) continue;
        headers[line.slice(0, colon).trim().toLowerCase()] = line.slice(colon + 1).trim();
      }

      async function* stream(): AsyncGenerator<string> {
        try {
          for (;;) {
            while (queue.length > 0) {
              const next = queue.shift();
              if (next !== undefined && next !== '') yield next;
            }
            if (failure !== null) throw failure;
            if (finished) return;
            await new Promise<void>((wakeUp) => {
              wake = wakeUp;
            });
          }
        } finally {
          request.signal?.removeEventListener('abort', onAbort);
        }
      }

      resolve({ status: xhr.status, headers, chunks: stream() });
    };

    xhr.addEventListener('readystatechange', () => {
      if (xhr.readyState === XHR_LOADING) {
        settle();
        take();
        notify();
      } else if (xhr.readyState === XHR_DONE) {
        settle();
        take();
        finished = true;
        notify();
      }
    });

    xhr.addEventListener('error', () => {
      failure = new Error('The connection to your Trove server failed.');
      finished = true;
      if (settled) notify();
      else {
        request.signal?.removeEventListener('abort', onAbort);
        reject(failure);
      }
    });

    xhr.addEventListener('timeout', () => {
      failure = new Error('The connection to your Trove server timed out.');
      finished = true;
      if (settled) notify();
      else {
        request.signal?.removeEventListener('abort', onAbort);
        reject(failure);
      }
    });

    xhr.send(request.body);
  });

/**
 * The absolute URL for a `/v1/...` path, built the way `client.ts` builds one.
 *
 * A stream cannot go through the axios instance — axios has no incremental
 * body — but it must not invent its own idea of where the server is either,
 * so the base URL comes from the same `serverConfig` the interceptor reads and
 * an unconfigured app throws the same `NoServerConnectedError`.
 */
export function streamUrl(path: string, query?: Record<string, string>): string {
  const base = getBaseUrl();
  if (base === null) throw new NoServerConnectedError();
  const search =
    query === undefined
      ? ''
      : Object.entries(query)
          .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
          .join('&');
  return `${base}${API_CONTEXT_PATH}${path}${search === '' ? '' : `?${search}`}`;
}

/**
 * The headers a streamed call carries.
 *
 * The same three the axios request interceptor sets — bearer mode, the
 * `X-Requested-With` marker and the `Authorization` header from the in-memory
 * access token — read through `client.ts`'s own accessor rather than copied,
 * so a screen never sees the token.
 */
export function streamAuthHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = {
    'X-Trove-Auth-Mode': 'bearer',
    'X-Requested-With': 'XMLHttpRequest',
    Accept: 'text/event-stream',
    ...extra,
  };
  const token = getAccessToken();
  if (token !== null) headers.Authorization = `Bearer ${token}`;
  return headers;
}

/** The transport this engine can actually stream with. */
export function selectTransport(): SseTransport {
  return supportsFetchStreaming() ? fetchChunkSource : xhrChunkSource;
}

/** Drain a chunk source into one string — for a response whose body was JSON, not a stream. */
export async function drain(chunks: ChunkSource): Promise<string> {
  let text = '';
  for await (const chunk of chunks) text += chunk;
  return text;
}
