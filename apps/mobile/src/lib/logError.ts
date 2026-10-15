/**
 * Client-side error log.
 *
 * This does **not** ship anything anywhere — an earlier comment on this file
 * claimed it "ships errors to the observability backend", which was never
 * true (readiness gap P4-20 / P4-52). It writes one redacted line to the
 * console so a developer attached to a device can see what failed. Sending
 * crash reports to the user's own server is deliberately out of scope for
 * v1.0 (gap P4-52, SHOULD).
 *
 * Everything is funnelled through `sanitise()` first: the raw error object —
 * which for an `AxiosError` carries `config.headers.Authorization`, the
 * request body and the response body — never reaches the sink.
 */

import { sanitise } from './sanitise';

export function logError(code: string, error: unknown): void {
  const safe = sanitise(code, error);
  // eslint-disable-next-line no-console
  console.error(`[${safe.code}]`, JSON.stringify(safe));
}
