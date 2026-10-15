/**
 * The coach — `/v1/chat/*` and `/v1/insights`.
 *
 * The prefix is **`/v1/chat`, never `/v1/coach`**: the pre-collapse route map
 * had `GET /api/coach/stream?message=…` and the spec supersedes it wherever
 * the two disagree.
 *
 * Three rules of this operation break a naive chat client, and all three are
 * enforced here rather than left to a screen:
 *
 *  1. **`messages` is the turn you are sending, not the history.** The server
 *     takes the *last* entry, which must be `role: 'user'`, and rebuilds the
 *     preceding turns from its own store for the named session. Earlier
 *     entries are ignored. Accepting a client-supplied `assistant` turn would
 *     let a screen put words into the model's mouth and quote them back as
 *     the coach's own, so {@link oneUserTurn} is the only body builder and it
 *     can only build one user turn.
 *  2. **The session id is the server's.** `X-Session-Id` is set on *every*
 *     response, streaming or not; send it back to continue, omit it to start
 *     a new conversation. It is the conversation's own id, it carries no
 *     privileges, and somebody else's is a 404. **Nothing here mints one** —
 *     the `crypto.randomUUID` / `react-native-get-random-values` polyfill the
 *     pre-collapse app carried for exactly that purpose is deleted, because
 *     a client-minted id was never the id the server was storing against.
 *  3. **The answer is text and is never interpreted.** It is rendered as
 *     text, never in a `WebView`, never as HTML, and no id, link or command
 *     is ever taken from it.
 */

import { api } from './client';
import { ApiProblemError, expectSuccess, problemOf, unwrap, unwrapPage } from './errors';
import { endSessionOutsideInterceptor, getRefreshToken, refreshAccessToken } from './client';
import {
  drain,
  readSseStream,
  selectTransport,
  streamAuthHeaders,
  streamUrl,
  type SseTransport,
  type StreamOutcome,
} from './sse';
import type {
  ApiResponse,
  ChatCompletionRequest,
  ChatCompletionResponse,
  ChatHistoryQuery,
  CoachMessageResponse,
  InsightResponse,
  InsightsQuery,
  Page,
} from './types';

/** The conversation id, on the way out and on the way back. */
export const SESSION_ID_HEADER = 'X-Session-Id';

/** The cap on one question, per `ChatTurnRequest.content`. The conversation is bounded separately. */
export const MAX_QUESTION_CHARS = 4000;

const COMPLETIONS_PATH = '/v1/chat/completions';

/**
 * The only body builder for a completion.
 *
 * One `user` turn. Not a transcript, and never an `assistant` turn — see rule
 * 1 in the file header.
 */
export function oneUserTurn(question: string): ChatCompletionRequest {
  return { messages: [{ role: 'user', content: question }] };
}

function sessionHeader(sessionId: string | null | undefined): Record<string, string> {
  // Absent means "start a new conversation". An empty string is not the same
  // thing and must never be sent as though it were.
  return typeof sessionId === 'string' && sessionId !== ''
    ? { [SESSION_ID_HEADER]: sessionId }
    : {};
}

/**
 * `POST /v1/chat/completions` **without** streaming — a first-class mode of
 * this contract, not a fallback.
 *
 * The ordinary envelope with the whole answer, and the endpoint's own
 * failures as real status codes (502 / 504) rather than as frames.
 */
export async function askCoach(
  question: string,
  sessionId?: string | null
): Promise<ChatCompletionResponse> {
  const response = await api.post<ApiResponse<ChatCompletionResponse>>(
    COMPLETIONS_PATH,
    oneUserTurn(question),
    { headers: sessionHeader(sessionId) }
  );
  return unwrap(response.data);
}

export interface CoachStreamRequest {
  question: string;
  /** Absent or empty starts a new conversation. */
  sessionId?: string | null;
  /** Abort on unmount or when the member presses Stop. */
  signal?: AbortSignal;
  /** Called as each delta lands, with the delta and the running answer. */
  onDelta?: (delta: string, whole: string) => void;
  /**
   * Test seam. Defaults to {@link selectTransport}, which picks `fetch` on an
   * engine whose responses expose a `ReadableStream` body and React Native's
   * incremental `XMLHttpRequest` — the one that actually streams here —
   * otherwise. See the header of `sse.ts` for what was measured.
   */
  transport?: SseTransport;
}

export interface CoachStreamResult extends StreamOutcome {
  /** The conversation this answer belongs to, from `X-Session-Id`. */
  sessionId: string | null;
}

function headerNumber(headers: Record<string, string>, name: string): number | null {
  const raw = headers[name];
  if (typeof raw !== 'string' || !/^\d+$/.test(raw.trim())) return null;
  return Number(raw.trim());
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/**
 * `POST /v1/chat/completions?stream=true`.
 *
 * **The status and the content type are read before the body is treated as a
 * stream.** Everything that can be decided before the first byte — an unknown
 * session, AI switched off, the privacy tier, a malformed body, the rate
 * limiter — is an ordinary JSON `ApiResponse` with the usual status whatever
 * `stream` asked for, and only after those does the stream open. A reader
 * that assumed a stream because it asked for one would parse a 409's JSON as
 * SSE frames, find none, and hang.
 *
 * A 401 is retried **once**, through `client.ts`'s own single-flight
 * `refreshAccessToken` — the same promise every axios call waits on, because
 * a second concurrent refresh presents a token the first already rotated away
 * and family-revokes the session.
 *
 * Every other ending is described by {@link StreamOutcome}, and **what
 * arrived is kept on all of them** — an in-band `answer-too-long` or
 * `outbound-url-rejected` frame means the answer stops, not that it vanishes.
 */
export async function streamCoachAnswer({
  question,
  sessionId,
  signal,
  onDelta,
  transport = selectTransport(),
}: CoachStreamRequest): Promise<CoachStreamResult> {
  const url = streamUrl(COMPLETIONS_PATH, { stream: 'true' });
  const body = JSON.stringify(oneUserTurn(question));

  const send = () =>
    transport(
      {
        path: COMPLETIONS_PATH,
        body,
        headers: streamAuthHeaders(sessionHeader(sessionId)),
        signal,
      },
      url
    );

  let response = await send();

  if (response.status === 401) {
    // Drain the refused body so the transport can release it, then take the
    // one refresh this call is allowed and try again exactly once.
    //
    // This is the ONE call in the app that does not pass through the axios
    // response interceptor, so it has to reach the interceptor's three
    // endings itself. `refreshAccessToken` rejects *before* its try block when
    // there is no refresh token, and therefore never ends the session — so a
    // member whose token is gone would stay on an authenticated UI whose every
    // request 401s until some other call happened to go through axios.
    const refused = await drain(response.chunks).catch(() => '');
    if (getRefreshToken() === null) {
      await endSessionOutsideInterceptor('NO_REFRESH_TOKEN');
      throw new ApiProblemError(401, problemOf(parseJson(refused)));
    }
    // A rejected refresh already ended the session inside `client.ts`
    // (`REFRESH_REJECTED`); let it propagate rather than re-ending it.
    await refreshAccessToken();
    response = await send();
    if (response.status === 401) {
      // The retry after a successful refresh 401'd as well — the server has
      // revoked this session outright, which is the interceptor's
      // `UNAUTHORIZED`.
      const again = await drain(response.chunks).catch(() => '');
      await endSessionOutsideInterceptor('UNAUTHORIZED');
      throw new ApiProblemError(401, problemOf(parseJson(again)));
    }
  }

  const returnedSession = response.headers[SESSION_ID_HEADER.toLowerCase()] ?? null;
  const conversation = returnedSession ?? (sessionId === '' ? null : (sessionId ?? null));

  if (response.status < 200 || response.status >= 300) {
    const text = await drain(response.chunks).catch(() => '');
    throw new ApiProblemError(
      response.status,
      problemOf(parseJson(text)),
      headerNumber(response.headers, 'retry-after')
    );
  }

  const contentType = response.headers['content-type'] ?? '';
  if (!contentType.toLowerCase().includes('text/event-stream')) {
    // A 200 that is not a stream. The contract allows it — the server decides
    // the content type, not the query parameter — so read it as the ordinary
    // envelope rather than looking for frames that will never come.
    const envelope = parseJson(
      await drain(response.chunks)
    ) as ApiResponse<ChatCompletionResponse> | null;
    if (envelope === null) {
      throw new ApiProblemError(response.status, null);
    }
    const answer = unwrap(envelope);
    onDelta?.(answer.content, answer.content);
    return {
      content: answer.content,
      ending: 'done',
      error: null,
      sessionId: answer.sessionId ?? conversation,
    };
  }

  const outcome = await readSseStream(response.chunks, { onDelta, signal });
  return { ...outcome, sessionId: conversation };
}

/**
 * `GET /v1/chat/suggestions` — prompt starters for the empty state.
 *
 * A bare array; `meta` is absent because this list is not paginated. **It
 * costs no AI call and needs no configured endpoint**: a member who has never
 * chatted and a member with AI switched off both get the default list rather
 * than an error, which is why the coach seeds its empty state from here
 * instead of hardcoding prompts.
 */
export async function fetchChatSuggestions(): Promise<string[]> {
  const response = await api.get<ApiResponse<string[]>>('/v1/chat/suggestions');
  return unwrap(response.data);
}

/**
 * `GET /v1/chat/history` — one **page** of the caller's own stored turns.
 *
 * `sessionId` narrows the page to one conversation; without it the page spans
 * every conversation the caller has, most recently used first and
 * chronological within each, so a client can group by `sessionId` without a
 * second request. A household mate's id is 404, exactly as an id that was
 * never issued is.
 *
 * `?sessionId=` — an empty value from a variable that was never set — is a
 * **400**, not "every conversation". The parameter is omitted rather than
 * sent empty.
 */
export async function fetchChatHistory(
  query: ChatHistoryQuery = {}
): Promise<Page<CoachMessageResponse>> {
  const response = await api.get<ApiResponse<CoachMessageResponse[]>>('/v1/chat/history', {
    params: withoutEmptySession(query),
  });
  return unwrapPage(response.data);
}

/**
 * `DELETE /v1/chat/history` — one conversation with `sessionId`, **every one
 * of the caller's** without it.
 *
 * Destructive and irreversible: the messages go with the conversation. The
 * screen confirms and says which of the two it is about to do before calling.
 * Answers the closed `EmptySuccess` shape.
 */
export async function deleteChatHistory(sessionId?: string | null): Promise<void> {
  const response = await api.delete<ApiResponse<never>>('/v1/chat/history', {
    params: withoutEmptySession({ sessionId: sessionId ?? undefined }),
  });
  expectSuccess(response.data);
}

/**
 * Drop a `sessionId` that is present but empty.
 *
 * Absent and empty are not the same thing on these two operations, and on the
 * delete reading them alike would destroy the member's whole history.
 */
function withoutEmptySession<T extends { sessionId?: string }>(query: T): T {
  if (query.sessionId !== '') return query;
  const rest: Record<string, unknown> = { ...query };
  delete rest.sessionId;
  return rest as T;
}

/**
 * `GET /v1/insights` — one **page** of the caller's own weekly notes, newest
 * first.
 *
 * A safe GET: `delivered` / `deliveredAt` say whether a note has been pushed
 * anywhere beyond this route, and **reading this page does not set them**. A
 * member with AI off gets an empty page rather than an error.
 */
export async function fetchInsights(query: InsightsQuery = {}): Promise<Page<InsightResponse>> {
  const response = await api.get<ApiResponse<InsightResponse[]>>('/v1/insights', {
    params: query,
  });
  return unwrapPage(response.data);
}
