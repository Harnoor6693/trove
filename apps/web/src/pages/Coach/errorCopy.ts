/**
 * The AI slug vocabulary, in one place, in the member's words.
 *
 * Every AI-backed operation narrows `ErrorResponse.type` to the slugs it can
 * really produce, and this switch covers exactly those and invents copy for
 * nothing else. The failures here are almost all the MEMBER'S OWN to fix — their
 * endpoint, their key, their privacy tier, their URL — so the copy names the
 * setting rather than apologising on the server's behalf.
 *
 * It lives in its own module rather than being exported from the page, because a
 * component file that also exports a function loses fast refresh, and the lint
 * gate enforces that.
 */
import type { ApiError } from '@api/client'

/** Where a member goes to fix any of this. Both surfaces are one entry for a reason. */
export const AI_SETTINGS_HASH = '/settings#ai-endpoint'

export interface AiErrorCopy {
  message: string
  /** True when the fix is in the member's own AI settings, so the screen links there. */
  linksToSettings: boolean
  /** True when this is "wait and retry", not a failure. Never a logout. */
  isRateLimit: boolean
}

export function aiErrorCopy(error: ApiError): AiErrorCopy {
  const toSettings = (message: string): AiErrorCopy => ({
    message,
    linksToSettings: true,
    isRateLimit: false,
  })

  switch (error.code) {
    case 'ai-disabled':
      return toSettings(
        'The coach needs your own AI endpoint. Nothing is configured yet, and Trove never turns one on for you.'
      )
    case 'ai-policy-blocked':
      return toSettings(
        'Your privacy setting is holding this back. A coach question is built from your household’s ledger, so it counts as sensitive, and sensitive payloads are only sent to an endpoint you have marked local. Change the privacy tier to “Local only” to allow it.'
      )
    case 'outbound-url-rejected':
      return toSettings(
        error.detail ??
          'Your saved endpoint address is no longer allowed. It must use https, unless private addresses are permitted both by this install and by your own setting.'
      )
    case 'ai-unreachable':
      return toSettings(
        'Your AI endpoint did not answer. Check that it is running and that the address in your settings is right.'
      )
    case 'ai-endpoint-unauthorized':
      return toSettings(
        'Your AI endpoint rejected the stored API key. Save a new key in your settings.'
      )
    case 'ai-model-missing':
      return toSettings(
        'Your AI endpoint does not serve the model your settings name. Pick another from the list in your settings.'
      )
    case 'ai-timeout':
      return toSettings(
        'Your AI endpoint did not answer in time. A local model on modest hardware may simply need a longer read timeout, which is a setting of yours.'
      )
    case 'answer-too-long':
      return {
        message:
          'The answer reached its length limit and stops here. What arrived is kept — your endpoint did not fail.',
        linksToSettings: false,
        isRateLimit: false,
      }
    case 'rate-limited':
      return {
        message:
          'Trove is asking you to slow down, not refusing you — your session is fine. Wait a moment, then ask again.',
        linksToSettings: false,
        isRateLimit: true,
      }
    case 'not-found':
      return {
        message:
          'That conversation is not yours any more. Start a new one — your other conversations are untouched.',
        linksToSettings: false,
        isRateLimit: false,
      }
    case 'validation-error':
      return {
        message:
          error.fieldErrors[0]?.message ?? error.detail ?? 'That question was refused as written.',
        linksToSettings: false,
        isRateLimit: false,
      }
    case 'malformed-request':
      return {
        message: 'That question could not be sent as written.',
        linksToSettings: false,
        isRateLimit: false,
      }
    case 'forbidden':
    case 'csrf-header-missing':
      return {
        message: 'That request was refused. Reload the page and try again.',
        linksToSettings: false,
        isRateLimit: false,
      }
    default:
      return {
        message: error.isNetworkError
          ? 'Trove could not reach the server. Check your connection and try again.'
          : 'The coach could not answer.',
        linksToSettings: false,
        isRateLimit: false,
      }
  }
}

/** Why a stream stopped without a terminator. Not an error the endpoint reported. */
export const TRUNCATED_STREAM_COPY =
  'The answer stopped before it finished. What arrived is kept; ask again to continue.'
