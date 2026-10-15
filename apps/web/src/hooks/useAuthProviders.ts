import { useQuery } from '@tanstack/react-query'
import { getAuthProviders } from '@api/auth'
import type { ApiError } from '@api/client'
import type { AuthProviders } from '@api/types'

export const authProvidersKey = ['auth', 'providers'] as const

/**
 * `GET /v1/auth/providers` (§4.1). Gates the OIDC button, the "invite-only"
 * message and the whole `/register` form, so the SPA never links an endpoint the
 * server does not expose.
 */
export function useAuthProviders() {
  return useQuery<AuthProviders, ApiError>({
    queryKey: authProvidersKey,
    queryFn: getAuthProviders,
    staleTime: 5 * 60 * 1000,
    retry: false,
  })
}
