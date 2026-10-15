/**
 * `POST /v1/accounts/exchange-setup-token` — the ONE SimpleFIN flow.
 *
 * Both the Onboarding connect step and the Accounts "Connect a bank" dialog use
 * this hook. There is deliberately no second exchange path: the setup token is a
 * credential, and one call site is one place to be careful about it.
 *
 * The token is never logged, never put in a query string, never echoed back to
 * the screen, and no caller keeps it in component state after the call settles.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { exchangeSetupToken } from '@api/accounts'
import { invalidateAccountsAndLedger } from './useAccounts'
import type { ApiError } from '@api/client'
import type { ExchangeSetupTokenRequest, ExchangeSetupTokenResponse } from '@api/types'

/**
 * Copy for exactly the slugs this operation narrows `ErrorResponse.type` to.
 * `outbound-url-rejected` is the egress policy refusing what the token decoded
 * to, and it is a different problem from a token that was simply mistyped, so it
 * gets its own sentence. Nothing here quotes the token or the decoded URL.
 */
export function exchangeErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'outbound-url-rejected':
      return 'That token points somewhere this server will not connect to. Check that it came from your SimpleFIN Bridge and has not been altered.'
    case 'validation-error':
    case 'malformed-request':
      return 'That token was not accepted. Copy it again from your bridge — it can only be used once.'
    case 'conflict':
      return 'Those accounts are already connected.'
    case 'rate-limited':
      return 'Too many attempts. Wait a moment and try again.'
    case 'forbidden':
    case 'csrf-header-missing':
      return 'Your session could not be verified. Reload the page and try again.'
    default:
      return 'That token was not accepted. You can skip this and try again later.'
  }
}

export function useExchangeSetupTokenMutation() {
  const qc = useQueryClient()
  return useMutation<ExchangeSetupTokenResponse, ApiError, ExchangeSetupTokenRequest>({
    mutationFn: exchangeSetupToken,
    onSuccess: () => invalidateAccountsAndLedger(qc),
    /**
     * The credential outlives the screen without this. A settled mutation keeps
     * its `variables` — the object carrying the raw setup token — in the shared
     * `MutationCache`, reachable from anywhere in the app, for the default
     * five-minute garbage-collection window after its last observer goes.
     * Clearing the input and the component state, which both call sites do,
     * empties the DOM and nothing else. `gcTime: 0` drops the entry as soon as
     * the dialog that owns it unmounts; the mutation is single-use and its
     * result is consumed on the spot, so there is nothing to keep.
     */
    gcTime: 0,
  })
}
