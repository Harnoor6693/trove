import { useQuery } from '@tanstack/react-query'
import { getPreferences } from '@api/preferences'
import { ApiError } from '@api/client'

export const preferencesKey = ['me', 'preferences'] as const

/**
 * `GET /v1/me/preferences`.
 *
 * `retry: false` is deliberate: `AuthGuard` gates the whole app on this query, so
 * a failure has to become an `isError` the guard can act on quickly rather than a
 * long pending state that looks like a hang.
 */
export function usePreferences(opts: { enabled?: boolean } = {}) {
  return useQuery<Awaited<ReturnType<typeof getPreferences>>, ApiError>({
    queryKey: preferencesKey,
    queryFn: getPreferences,
    staleTime: Infinity,
    retry: false,
    enabled: opts.enabled ?? true,
  })
}
