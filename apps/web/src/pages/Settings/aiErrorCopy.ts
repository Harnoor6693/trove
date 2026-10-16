/**
 * The AI settings section's copy for a refused save, test or model list.
 *
 * The three routes share one slug vocabulary by design — "the endpoint refused
 * us" reads the same whether it came from a completion or from the connection
 * test — so this switch covers exactly the slugs `updateAiSettings`,
 * `testAiConnection` and `listAiModels` narrow `ErrorResponse.type` to.
 *
 * **No branch here ever mentions a key's value.** The key is write-only: it is
 * not in any response, must not reach a log or a cache, and must not be echoed
 * back inside an error message either.
 */
import type { ApiError } from '@api/client'

export function aiSettingsErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'outbound-url-rejected':
      return (
        error.detail ??
        'That address was refused. It must be an absolute https URL with no credentials before the host — and a private or loopback address needs both this install’s permission and your own.'
      )
    case 'validation-error':
      return (
        error.fieldErrors[0]?.message ??
        error.detail ??
        'Some of those settings were refused. Turning the coach on needs both an endpoint and a chat model.'
      )
    case 'malformed-request':
      return 'Those settings could not be sent as written.'
    case 'ai-disabled':
      // On the two configuration-time routes this means only "nothing to call".
      return 'Save an endpoint address first — there is nothing to contact yet.'
    case 'ai-unreachable':
      return 'Your endpoint did not answer. Check that it is running and reachable from this server.'
    case 'ai-endpoint-unauthorized':
      return 'Your endpoint rejected the stored API key. Save a new one.'
    case 'ai-model-missing':
      return 'Your endpoint answered but does not serve the chat model these settings name.'
    case 'ai-timeout':
      return 'Your endpoint did not answer inside your read timeout. A local model on modest hardware may simply need longer.'
    case 'forbidden':
    case 'csrf-header-missing':
      return 'That request was refused. Reload the page and try again.'
    case 'rate-limited':
      return 'Too many requests just now. Wait a few seconds, then try again — your session is fine.'
    default:
      return error.isNetworkError
        ? 'Trove could not reach the server. Check your connection and try again.'
        : 'Those settings were not saved.'
  }
}
