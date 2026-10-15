/**
 * Turning a failed request into something a screen can render.
 *
 * Every `trove-core` error body is the same envelope (§4.1 states this
 * explicitly for `/v1/auth/*` too — there is no bare shape carved out for
 * auth) with an RFC 7807 `error` block:
 *
 *   { success: false,
 *     error: { type: "https://trove.finance/errors/credentials-invalid",
 *              title, status, detail, instance, requestId,
 *              fieldErrors: [{field, message}, …] } }
 *
 * Two details are load-bearing and were got wrong in the first draft of this
 * client:
 *
 *  - `fieldErrors` is a **list** of `{field, message}` pairs, not a map from
 *    field name to messages. That is what `core`'s `ErrorResponse.FieldError`
 *    record serialises and what `apps/web` reads.
 *  - The machine-readable part of the error is the **trailing slug of
 *    `type`**, not the status. §4.1 lists the slugs the clients branch on;
 *    `credentials-invalid` and `account-locked` share a status, and
 *    `registration-closed` and `invite-expired` do too, so a screen that
 *    branches on status alone cannot tell them apart.
 */

import axios from 'axios';
import type { ApiResponse, ErrorDetail, FieldErrorItem, Page, PaginationMeta } from './types';

export interface ApiFailure {
  status: number | null;
  /**
   * The trailing slug of `error.type` — `credentials-invalid`,
   * `registration-closed`, … — or null when the server sent no `type`.
   */
  code: string | null;
  /** Human-readable message safe to show in the UI. */
  message: string;
  /** Correlation id, also in the `X-Request-Id` response header. */
  requestId: string | null;
  /** Per-field validation messages; only populated on a 400. */
  fieldErrors: FieldErrorItem[];
  /**
   * Whole seconds to wait, read off the `Retry-After` response header.
   *
   * Every operation on this API except `getStatus` declares a 429, and the
   * limiter always sends the delta form of the header (RFC 9110 also permits
   * an HTTP-date; this server never sends one). A 429 is a *wait and retry*
   * state with a real number attached — not a generic failure toast — so the
   * number has to survive normalisation. Null on every other status, and on a
   * 429 whose header a proxy stripped.
   */
  retryAfterSeconds: number | null;
}

const NETWORK_MESSAGE = 'Could not reach your Trove server. Check your connection and try again.';

/** Unwrap the `ApiResponse` envelope, or throw if the server said `success: false`. */
export function unwrap<T>(body: ApiResponse<T>): T {
  if (body.success !== true || body.data === undefined) {
    throw rejection(body);
  }
  return body.data;
}

function rejection(body: ApiResponse<unknown>): Error {
  const detail = body.error;
  return new Error(detail?.detail ?? detail?.title ?? 'The server rejected that request.');
}

/**
 * Unwrap a **paged** list operation: one whose 200 requires `data` *and*
 * `meta`.
 *
 * `unwrap` alone would hand back page one and silently drop the totals, and a
 * `FlatList` fed from page one only is a truncation of the member's ledger
 * they are never told about. Every paged route in the contract
 * (`listTransactions` is this family's) goes through here so the page controls
 * and the rows come from the same fetch.
 */
export function unwrapPage<T>(body: ApiResponse<T[]>): Page<T> {
  const data = unwrap(body);
  const meta = body.meta;
  if (meta === undefined) {
    throw new Error('The server returned a page without its pagination metadata.');
  }
  return { data, meta: meta as PaginationMeta };
}

/**
 * Assert the closed `EmptySuccess` shape — literally `{"success": true}` with
 * **no `data` key**.
 *
 * `deleteAccount`, `deleteTransaction` and `undoImport` all answer this, and
 * reading `data` off one of them is how a client ends up with `undefined`
 * where it expected a body. `unwrap` cannot be used here: it treats an absent
 * `data` as a failure, which for these three operations is the success case.
 */
export function expectSuccess(body: ApiResponse<never>): void {
  if (body.success !== true) {
    throw rejection(body);
  }
}

/**
 * A refusal that came back down axios's **binary** path.
 *
 * A download asks for `responseType: 'arraybuffer'`, and axios then skips its
 * JSON transform for the error body too — so a 409 on
 * `GET /v1/me/export/{id}/download` or the tax-export PDF arrives as raw
 * bytes, and every `slugOf`/`fieldError` read off it comes back null. Decoded
 * here, once, at the normaliser every caller already goes through, rather than
 * in each download function. Anything that is not a small JSON body (a real
 * ZIP, a proxy's HTML page, an engine with no `TextDecoder`) falls through to
 * the generic failure, which is what happens today.
 */
function decodedBinaryBody(data: ArrayBuffer): unknown {
  if (data.byteLength === 0 || data.byteLength > 64_000) return null;
  try {
    return JSON.parse(new TextDecoder().decode(new Uint8Array(data)));
  } catch {
    return null;
  }
}

function readErrorDetail(data: unknown): ErrorDetail | null {
  if (data instanceof ArrayBuffer) return readErrorDetail(decodedBinaryBody(data));
  if (typeof data !== 'object' || data === null) return null;
  const envelope = (data as ApiResponse<unknown>).error;
  if (typeof envelope === 'object' && envelope !== null) return envelope;
  // A bare problem-details body, from an entry point that wrote one without
  // the envelope (Spring's own `/error` page, or a reverse proxy).
  const bare = data as ErrorDetail;
  return typeof bare.status === 'number' && typeof bare.title === 'string' ? bare : null;
}

/** `https://trove.finance/errors/not-found` → `not-found`. */
export function slugOf(type: string | undefined): string | null {
  if (typeof type !== 'string' || type === '') return null;
  const slash = type.lastIndexOf('/');
  return slash === -1 ? type : type.slice(slash + 1);
}

/**
 * `Retry-After`, in whole seconds. Only the delta form is parsed, which is the
 * only form this server sends; an HTTP-date would yield null rather than a
 * wrong wait.
 */
function headerRetryAfter(headers: unknown): number | null {
  if (typeof headers !== 'object' || headers === null) return null;
  const value = (headers as Record<string, unknown>)['retry-after'];
  const raw = typeof value === 'number' ? String(value) : value;
  if (typeof raw !== 'string' || !/^\d+$/.test(raw.trim())) return null;
  const seconds = Number(raw.trim());
  return Number.isFinite(seconds) ? seconds : null;
}

function headerRequestId(headers: unknown): string | null {
  if (typeof headers !== 'object' || headers === null) return null;
  const value = (headers as Record<string, unknown>)['x-request-id'];
  return typeof value === 'string' && value !== '' ? value : null;
}

/**
 * A refusal that did not come through axios.
 *
 * `POST /v1/chat/completions` with `stream=true` cannot go through the axios
 * instance — axios has no incremental body — so the streaming reader
 * (`sse.ts`) issues that one request itself. Everything it can be refused
 * with is still an ordinary `ApiResponse` with the usual status: an unknown
 * session, AI switched off, the privacy tier, a malformed body, the rate
 * limiter. This carries such a refusal back to `toApiFailure` with its slug
 * and its `Retry-After` intact, so a screen still branches on `failure.code`
 * and never on which transport happened to make the call.
 */
export class ApiProblemError extends Error {
  readonly status: number;
  readonly detail: ErrorDetail | null;
  readonly retryAfterSeconds: number | null;

  constructor(status: number, detail: ErrorDetail | null, retryAfterSeconds: number | null = null) {
    super(detail?.detail ?? detail?.title ?? 'The server rejected that request.');
    this.name = 'ApiProblemError';
    this.status = status;
    this.detail = detail;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/**
 * Read an `ApiResponse` error block out of an already-parsed body.
 *
 * Exported for the one caller that has a body but no `AxiosError` around it:
 * the streaming reader, which parses the JSON refusal the server writes
 * *before* a stream opens.
 */
export function problemOf(body: unknown): ErrorDetail | null {
  return readErrorDetail(body);
}

/** Normalise any thrown value into `{status, code, message, requestId, fieldErrors}`. */
export function toApiFailure(err: unknown, fallbackMessage: string): ApiFailure {
  const empty = {
    code: null,
    requestId: null,
    fieldErrors: [] as FieldErrorItem[],
    retryAfterSeconds: null,
  };

  if (err instanceof ApiProblemError) {
    const detail = err.detail;
    return {
      status: err.status,
      code: slugOf(detail?.type),
      message: detail?.detail ?? detail?.title ?? fallbackMessage,
      requestId: detail?.requestId ?? null,
      fieldErrors: Array.isArray(detail?.fieldErrors) ? detail.fieldErrors : [],
      retryAfterSeconds: err.status === 429 ? err.retryAfterSeconds : null,
    };
  }

  if (axios.isAxiosError(err)) {
    const status = err.response?.status ?? null;
    if (status === null) {
      return { status: null, message: NETWORK_MESSAGE, ...empty };
    }
    const detail = readErrorDetail(err.response?.data);
    return {
      status,
      code: slugOf(detail?.type),
      message: detail?.detail ?? detail?.title ?? fallbackMessage,
      requestId: detail?.requestId ?? headerRequestId(err.response?.headers),
      fieldErrors: Array.isArray(detail?.fieldErrors) ? detail.fieldErrors : [],
      retryAfterSeconds: status === 429 ? headerRetryAfter(err.response?.headers) : null,
    };
  }
  if (err instanceof Error && err.message !== '') {
    return { status: null, message: err.message, ...empty };
  }
  return { status: null, message: fallbackMessage, ...empty };
}

/** The first validation message for one field, for an inline form error. */
export function fieldError(failure: ApiFailure, field: string): string | null {
  return failure.fieldErrors.find((item) => item.field === field)?.message ?? null;
}

/**
 * True for a 429 from the chain-wide limiter.
 *
 * Kept separate from the failure's message because a 429 is not a failure the
 * member caused: the screen shows a wait-and-retry state, and — this is the
 * part that has bitten clients of this API — it must **not** trigger a token
 * refresh and must **not** clear the session.
 */
export function isRateLimited(failure: ApiFailure): boolean {
  return failure.status === 429;
}

/** True for the 401 the login endpoints return on bad credentials. */
export function isUnauthorized(err: unknown): boolean {
  return axios.isAxiosError(err) && err.response?.status === 401;
}
