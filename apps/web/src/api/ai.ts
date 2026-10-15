/**
 * `/v1/ai/*` — the caller's OWN AI endpoint settings. Five operations.
 *
 * **Per member, not per household.** The endpoint and its key are configured by
 * each member individually and are invisible to everyone else, administrators
 * included — the opposite of the ledger routes. There is no route by which one
 * member reads another's row, and nothing in this module is household-scoped.
 *
 * **THE API KEY IS WRITE-ONLY AND THIS MODULE IS WHERE THAT IS ENFORCED.** The
 * server stores it AES-GCM-encrypted and never returns it in any form:
 * `AiSettingsResponse` carries `hasApiKey` and nothing else about it. So:
 *
 *  - nothing here reads a key off a response, because there is none to read;
 *  - `updateAiSettings` takes the key as an argument and does not put it in a
 *    query string, a log or anything this module returns;
 *  - the caller must not put it in a React Query cache — `useAiSettings` caches
 *    `AiSettingsResponse`, which cannot contain one by construction, and the
 *    form holds the typed value in component state and drops it after the save.
 *
 * A blank key is 400 `validation-error` rather than a silent clear, because
 * "clear the key" has its own route: `deleteAiApiKey`.
 */
import { api, unwrap, unwrapVoid } from './client'
import type {
  AiConnectionTestResponse,
  AiModel,
  AiSettingsResponse,
  AiSettingsUpdateRequest,
  ApiResponse,
} from './types'

/**
 * `GET /v1/ai/settings`.
 *
 * A member who has never saved anything gets the defaults (`enabled: false`, no
 * endpoint, no key, `privacyTier: CLOUD_OK`) rather than a 404, so the settings
 * screen renders identically on the first visit and the hundredth, and the Coach
 * can ask "is an endpoint configured?" without treating an error as an answer.
 */
export async function getAiSettings(): Promise<AiSettingsResponse> {
  const res = await api.get<ApiResponse<AiSettingsResponse>>('/v1/ai/settings')
  return unwrap(res)
}

/**
 * `PUT /v1/ai/settings` — **a REPLACE, not a patch.**
 *
 * Every field except `apiKey` takes its value from this body: an omitted
 * nullable field is written as NULL, which is how an endpoint or a model is
 * cleared. `enabled`, `allowPrivateEndpoint` and `privacyTier` must be sent
 * every time. A caller that sends only what the member changed would therefore
 * wipe everything they did not touch — the body must be built from the whole
 * current settings, which is exactly what the form does.
 *
 * 400 `outbound-url-rejected` is the egress policy refusing the URL (a
 * non-https target, or a private one without both opt-ins). It carries a
 * sentence naming the rule and never the resolved address.
 */
export async function updateAiSettings(
  body: AiSettingsUpdateRequest
): Promise<AiSettingsResponse> {
  const res = await api.put<ApiResponse<AiSettingsResponse>>('/v1/ai/settings', body)
  return unwrap(res)
}

/**
 * `DELETE /v1/ai/settings/api-key` — forget the stored key and nothing else.
 * Idempotent, and there is deliberately no 404: the alternative would turn the
 * route into a way of asking whether somebody has a key. `EmptySuccess`, so
 * `unwrapVoid` — reading `data` off it reads a key the server never sends.
 */
export async function deleteAiApiKey(): Promise<void> {
  const res = await api.delete<ApiResponse<never>>('/v1/ai/settings/api-key')
  unwrapVoid(res)
}

/**
 * `POST /v1/ai/settings/test` — **no body**. The settings under test are the
 * SAVED ones, so a green result means the stored configuration works rather than
 * that some other configuration would have.
 *
 * `ok` is always `true` in a 200; every failure is a typed slug instead
 * (409 `ai-disabled`, 400 `outbound-url-rejected`, 502 `ai-unreachable` /
 * `ai-endpoint-unauthorized` / `ai-model-missing`, 504 `ai-timeout`), so a
 * caller has one vocabulary whether it hit this route or a real completion.
 */
export async function testAiConnection(): Promise<AiConnectionTestResponse> {
  const res = await api.post<ApiResponse<AiConnectionTestResponse>>(
    '/v1/ai/settings/test',
    null
  )
  return unwrap(res)
}

/**
 * `GET /v1/ai/models` — the models the member's own endpoint serves, so the
 * settings screen offers the real list instead of a free-text box.
 *
 * It needs a saved `endpointUrl` and nothing else — not `enabled`, and pointedly
 * not a `model`, which is what it is there to help choose. That is the fix
 * recorded in handoff 15, and it is why the picker can be populated before a
 * model has ever been saved. 409 `ai-disabled` here means only "no endpoint is
 * saved", never "the feature is off".
 *
 * A bare array: the operation states this list is not paginated and sends no
 * `meta`.
 */
export async function listAiModels(): Promise<AiModel[]> {
  const res = await api.get<ApiResponse<AiModel[]>>('/v1/ai/models')
  return unwrap(res)
}
