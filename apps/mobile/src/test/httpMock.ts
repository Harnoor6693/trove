import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { toApiFailure, type ApiFailure } from '@api/errors';

/**
 * A mock **adapter**, for tests of the `src/api/*` endpoint modules.
 *
 * `axios` is deliberately not mocked anywhere in this app — a mocked
 * `axios.create()` whose `interceptors.use` throws its handler away makes the
 * bearer header, the base-URL resolution and the whole single-flight refresh
 * structurally untestable, which is the failure the mobile inventory calls out
 * for the over-mocked store tests (§4). An endpoint test that stubbed the `api`
 * object instead would have the same blind spot one level down: it would prove
 * the module called *something*, not that the request the server sees is the
 * one the contract describes.
 *
 * So these tests drive the real `api` instance and intercept at the bottom, at
 * the adapter, where the fully-built request config is visible: method, path,
 * params, body and headers.
 *
 * **Import this module before anything that pulls in `src/api/client`.**
 * `axios.create()` copies `axios.defaults` — the adapter included — into the
 * instance at creation time, so an adapter installed after `client.ts` has been
 * evaluated is never consulted. Installing it as an import side effect, and
 * importing this first, is what guarantees the order.
 */

export interface StubResponse {
  status: number;
  data?: unknown;
  headers?: Record<string, string>;
}

export type StubHandler = (config: InternalAxiosRequestConfig) => StubResponse;

const OK: StubResponse = { status: 200, data: { success: true, data: null } };

let handler: StubHandler = () => OK;

/** Every request that reached the adapter, in order, fully built. */
export const seen: InternalAxiosRequestConfig[] = [];

axios.defaults.adapter = async (config) => {
  const request = config as InternalAxiosRequestConfig;
  seen.push(request);
  const stub = handler(request);
  const response = {
    data: stub.data ?? null,
    status: stub.status,
    statusText: '',
    headers: stub.headers ?? {},
    config,
    request: {},
  };
  const accepted =
    typeof config.validateStatus === 'function'
      ? config.validateStatus(stub.status)
      : stub.status >= 200 && stub.status < 300;
  if (accepted) return response;
  throw new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, response);
};

/** Answer every request with this handler until the next `reply`/`reset`. */
export function reply(next: StubHandler): void {
  handler = next;
}

/** Answer once with `body` inside a successful envelope. */
export function replyOk(data: unknown, meta?: unknown): void {
  reply(() => ({
    status: 200,
    data: meta === undefined ? { success: true, data } : { success: true, data, meta },
  }));
}

/** Answer with an RFC 7807 problem inside the error envelope. */
export function replyError(
  status: number,
  slug: string,
  detail = 'Refused.',
  extra: { fieldErrors?: Array<{ field: string; message: string }>; retryAfter?: number } = {}
): void {
  const headers: Record<string, string> = {};
  if (extra.retryAfter !== undefined) headers['retry-after'] = String(extra.retryAfter);
  reply(() => ({
    status,
    headers,
    data: {
      success: false,
      error: {
        type: `https://trove.finance/errors/${slug}`,
        title: slug,
        status,
        detail,
        instance: '/v1/test',
        requestId: 'req-1',
        ...(extra.fieldErrors === undefined ? {} : { fieldErrors: extra.fieldErrors }),
      },
    },
  }));
}

/** Forget every recorded request and go back to the default success stub. */
export function reset(): void {
  seen.length = 0;
  handler = () => OK;
}

/** `GET /v1/accounts` style summaries of what was actually sent. */
export function calls(): string[] {
  return seen.map((config) => `${config.method?.toUpperCase()} ${config.url}`);
}

/** Sentinel for "the promise never rejected", distinguishable from a thrown undefined. */
const NOTHING_THROWN = Symbol('nothing-thrown');

/**
 * Run a call that is expected to be refused and hand back the normalised
 * failure.
 *
 * Written as its own helper rather than `promise.catch(toApiFailure)` because
 * that expression's type is `T | ApiFailure`, so every assertion afterwards
 * needs a narrowing the test does not care about — and, worse, a call that
 * unexpectedly *succeeded* would sail past the assertions instead of failing
 * the test.
 */
export async function failureOf(
  promise: Promise<unknown>,
  fallbackMessage = 'The call was refused.'
): Promise<ApiFailure> {
  let caught: unknown = NOTHING_THROWN;
  await promise.catch((err: unknown) => {
    caught = err;
  });
  if (caught === NOTHING_THROWN) {
    throw new Error('Expected this call to be refused, but it resolved.');
  }
  return toApiFailure(caught, fallbackMessage);
}
