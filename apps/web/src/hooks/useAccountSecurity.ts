/**
 * Account security — the password change, the TOTP enrolment lifecycle and the
 * member's own security-event history (§4.1, step 205).
 *
 * Every mutation here carries a credential, so every one goes through
 * `useCredentialMutation`: the body never becomes a mutation variable and
 * therefore never sits in the mutation cache after the dialog closes.
 *
 * The recovery codes `confirmTotp` returns are NOT cached either — they are
 * readable exactly once, so they are returned to the caller and held in that
 * component's own state, never in a query key.
 */
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  changePassword,
  confirmTotp,
  disableTotp,
  requestPasswordResetLink,
  setupTotp,
} from '@api/auth'
import { getMySecurityEvents } from '@api/me'
import { useCredentialMutation } from './useCredentialMutation'
import type { ApiError } from '@api/client'
import type {
  CookieSession,
  PasswordChangeRequest,
  PasswordResetLinkRequest,
  PasswordResetLinkResponse,
  SecurityEventView,
  TotpConfirmRequest,
  TotpConfirmResponse,
  TotpDisableRequest,
  TotpEnrolment,
  TotpSetupRequest,
} from '@api/types'

export const securityEventsKey = ['me', 'security-events'] as const

/** `GET /v1/me/security-events` — bounded, not paged; newest first. */
export function useSecurityEvents(enabled = true) {
  return useQuery<SecurityEventView[], ApiError>({
    queryKey: securityEventsKey,
    queryFn: getMySecurityEvents,
    enabled,
    staleTime: 30 * 1000,
  })
}

/**
 * `POST /v1/auth/password`. The caller's own session survives; every other one
 * is revoked. Nothing to invalidate but the security-event list, which just grew
 * a `PASSWORD_CHANGE` row.
 */
export function useChangePasswordMutation() {
  const qc = useQueryClient()
  return useCredentialMutation<CookieSession, PasswordChangeRequest>(changePassword, {
    onSuccess: () => void qc.invalidateQueries({ queryKey: securityEventsKey }),
  })
}

/** `POST /v1/auth/totp/setup` — the secret, with the factor still OFF. */
export function useSetupTotpMutation() {
  return useCredentialMutation<TotpEnrolment, TotpSetupRequest>(setupTotp)
}

/**
 * `POST /v1/auth/totp/confirm` — enables the factor and mints the recovery
 * codes. The codes come back through `onSuccess` and stop there.
 */
export function useConfirmTotpMutation() {
  const qc = useQueryClient()
  return useCredentialMutation<TotpConfirmResponse, TotpConfirmRequest>(confirmTotp, {
    onSuccess: () => void qc.invalidateQueries({ queryKey: securityEventsKey }),
  })
}

/** `POST /v1/auth/totp/disable` — the POST twin. Password AND code. */
export function useDisableTotpMutation() {
  const qc = useQueryClient()
  return useCredentialMutation<void, TotpDisableRequest>(disableTotp, {
    onSuccess: () => void qc.invalidateQueries({ queryKey: securityEventsKey }),
  })
}

/**
 * `POST /v1/auth/password/reset-request` — ADMIN only, and an ordinary mutation:
 * the body is a household member's email address, not a credential. The RESPONSE
 * carries the one-time link, which the admin screen shows once and does not cache
 * — including in the MUTATION cache, which keeps a settled mutation's `data` for
 * the gc window after the dialog unmounts unless it is told not to. That link
 * sets somebody's password, so it is dropped with its observer.
 */
export function useMintResetLinkMutation() {
  return useMutation<PasswordResetLinkResponse, ApiError, PasswordResetLinkRequest>({
    mutationFn: requestPasswordResetLink,
    gcTime: 0,
  })
}
