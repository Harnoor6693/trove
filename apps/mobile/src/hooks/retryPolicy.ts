/**
 * The retry rule every query in this app uses.
 *
 * The app's single `QueryClient` (`app/_layout.tsx`) defaults queries to
 * `retry: 2`, and React Query's numeric retry knows nothing about status
 * codes: it re-issues the request twice, on its own exponential backoff, for
 * *any* rejection. That is wrong for a deliberate refusal, and it is worst for
 * exactly the one the contract makes a first-class state:
 *
 *  - **429.** Every operation but `getStatus` declares it, the limiter is
 *    chain-wide and the response carries `Retry-After`. Retrying it twice
 *    ignores the seconds the server just asked for, spends two more requests
 *    against the same limiter — deepening the penalty the member is being told
 *    to wait out — and delays the wait-and-retry state by the whole backoff.
 *  - **401.** `client.ts` already owns the single-flight refresh and has
 *    already replayed the request once by the time a 401 surfaces here. A
 *    query-level retry is a *second* refresh attempt, which is the rotation
 *    that family-revokes the session.
 *  - **400 / 403 / 404 / 409 / 413.** A validation error, a refused CSRF
 *    header, a row that is not there, a conflicting state and a file over the
 *    ceiling all answer the same way every time. Re-asking is pure noise.
 *
 * Anything else — a transport failure, a 5xx, a `NoServerConnectedError` — is
 * still worth two more attempts, which is what the app default was for.
 */

import { toApiFailure } from '@api/errors';

/** Statuses that mean "the server decided", so asking again changes nothing. */
const REFUSALS = [400, 401, 403, 404, 409, 413, 429];

/** The number of *extra* attempts a retryable failure gets, matching the app default. */
export const MAX_QUERY_RETRIES = 2;

/**
 * React Query's `retry` predicate: `false` stops immediately, `true` schedules
 * another attempt. `error` is typed `Error` rather than `unknown` because that
 * is the `TError` every hook here uses; widening it would make React Query
 * infer `TError = unknown` and change the type of `error` on every result.
 */
export function retryUnlessRefused(failureCount: number, error: Error): boolean {
  const { status } = toApiFailure(error, '');
  if (status !== null && REFUSALS.includes(status)) return false;
  return failureCount < MAX_QUERY_RETRIES;
}
