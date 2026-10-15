import { QueryClient, type DefaultOptions } from '@tanstack/react-query';

/**
 * The one place a test should build a `QueryClient`.
 *
 * React Query v5 defaults `gcTime` to 5 minutes for both queries *and*
 * mutations: an idle query or a settled mutation with no observers isn't
 * discarded until that timer fires, and the timer is a real `setTimeout`
 * that keeps Node's event loop alive. That is exactly what made a suite
 * built on an ad-hoc, never-torn-down client pass and then sit for minutes
 * past "Ran all test suites" with "Jest did not exit ... asynchronous
 * operations that weren't stopped" (and, on a loaded CI runner, long enough
 * to get killed).
 *
 * **Both** `queries` and `mutations` need `gcTime: 0` here — they are two
 * separate branches of `defaultOptions`, and a client that only zeroes the
 * query branch still leaves every `useMutation` (every save button, every
 * upload) on the 5-minute default. That was the actual leak behind this
 * file's own history: setting `gcTime: 0` for queries alone, plus a
 * `client.clear()` in `afterEach`, still hung for ~300s per run, because
 * `QueryCache.clear()` calls `query.destroy()` (cancelling each query's GC
 * timeout) but `MutationCache.clear()` does not call the equivalent on a
 * single mutation — it removes the mutation from the cache's bookkeeping
 * and fires a notification, nothing more. A settled mutation's own pending
 * `setTimeout` from `scheduleGc()` keeps running regardless of `clear()`.
 * `gcTime: 0` on mutations is therefore not a defence-in-depth extra here;
 * it is the only thing that stops that timer, since it collects the
 * moment the mutation has no observers rather than after a 5-minute wait
 * `clear()` can't cancel.
 *
 * `retry: false` on both keeps a mocked rejection (query or mutation) from
 * spending real backoff delays. `retryDelay: 0` is the belt to that braces:
 * a hook that sets its own `retry` predicate — every query in the accounts,
 * transactions, imports and dashboard families does, so that a 429 or a 404 is
 * never re-issued — overrides the client's `retry: false` but not its delay,
 * so without this a test that mocks a *retryable* failure would sit through
 * React Query's real exponential backoff before reaching the error state.
 *
 * `queryOverrides` exists for the one test that
 * has to prove the opposite — that a hook's *own* `retry` predicate refuses to
 * re-issue a 429 even under a client whose default is `retry: 2`, the way the
 * shipped `app/_layout.tsx` client is configured. It merges over the query
 * branch only, so `gcTime: 0` on mutations (the thing that actually stops the
 * hang described above) is never overridable.
 *
 * Callers that render more than one component
 * against the same client in a test should still call `client.clear()` in
 * `afterEach` as a second line of defence for queries — cheap, and it also
 * covers a client that never unmounts (e.g. a screen still mounted at test
 * end).
 */
export function createTestQueryClient(
  queryOverrides: NonNullable<DefaultOptions['queries']> = {}
): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, retryDelay: 0, ...queryOverrides },
      mutations: { retry: false, gcTime: 0 },
    },
  });
}
