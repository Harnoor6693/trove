/**
 * The caller's own AI endpoint — `/v1/ai/*`.
 *
 * **Per member, not per household.** The endpoint, the key and the timeouts
 * belong to whoever is signed in and are invisible to everyone else,
 * administrators included — the opposite of every ledger route in this app.
 * There is no route by which one member reads another's row, so nothing here
 * takes a user id.
 *
 * ## The rule that cannot be broken
 *
 * **The API key is never echoed.** It is stored AES-GCM-encrypted and never
 * leaves the server in any form; `AiSettingsResponse` carries `hasApiKey` and
 * nothing else about it. So:
 *
 *  - Nothing in this module returns a key, and no caller may render one, mask
 *    one, or invent a placeholder that looks like one.
 *  - The key is never written to the secure store, a log, a crash report or a
 *    query cache. It exists as component state for as long as the member is
 *    typing it and is dropped the moment {@link updateAiSettings} returns —
 *    `src/lib/sanitise.ts` already redacts an `apiKey`-shaped value out of
 *    anything that reaches a log sink, which is the backstop, not the plan.
 *  - `DELETE /v1/ai/settings/api-key` is how a key is removed. There is no
 *    "set it to empty".
 */

import { api } from './client';
import { expectSuccess, unwrap } from './errors';
import type {
  AiConnectionTestResponse,
  AiModel,
  AiSettingsResponse,
  AiSettingsUpdateRequest,
  ApiResponse,
} from './types';

/**
 * `GET /v1/ai/settings`.
 *
 * A member who has never saved anything gets the defaults — `enabled: false`,
 * no endpoint, no key, `privacyTier: CLOUD_OK` — rather than a 404, so the
 * settings card renders the same way on the first visit as on the hundredth
 * and the coach can ask "is this configured?" without treating an error as an
 * answer.
 */
export async function fetchAiSettings(): Promise<AiSettingsResponse> {
  const response = await api.get<ApiResponse<AiSettingsResponse>>('/v1/ai/settings');
  return unwrap(response.data);
}

/**
 * `PUT /v1/ai/settings` — **a replace, not a patch**.
 *
 * `enabled`, `allowPrivateEndpoint` and `privacyTier` must be sent every
 * time, and an omitted nullable field is written as null — which is how an
 * endpoint or a model is cleared. `apiKey` is the one exception: absent or
 * null leaves the stored key alone, because a write-only field cannot
 * round-trip through the GET, so a form has to be able to save everything
 * else without resending it.
 *
 * `endpointUrl` is checked by the egress policy on the way in: a non-`https`
 * or private target is 400 `outbound-url-rejected` with a sentence naming the
 * rule. The client does not pre-judge that — a LAN Ollama is the primary AI
 * story on this product and only the server knows whether the install allows
 * one.
 */
export async function updateAiSettings(body: AiSettingsUpdateRequest): Promise<AiSettingsResponse> {
  const response = await api.put<ApiResponse<AiSettingsResponse>>('/v1/ai/settings', body);
  return unwrap(response.data);
}

/**
 * `DELETE /v1/ai/settings/api-key` — forget the stored key and nothing else.
 *
 * Idempotent by design: a member with no key gets the same `{"success":true}`
 * as one who had a key a moment ago, because a 404 here would turn the route
 * into a way of asking whether somebody has a key configured. Answers the
 * closed `EmptySuccess` shape, so nothing reads `data` off it.
 */
export async function deleteAiApiKey(): Promise<void> {
  const response = await api.delete<ApiResponse<never>>('/v1/ai/settings/api-key');
  expectSuccess(response.data);
}

/**
 * `POST /v1/ai/settings/test` — **no body**.
 *
 * The settings under test are the saved ones, so a green result means the
 * stored configuration works rather than that some other configuration would
 * have. It sends no prompt and no household data. Failures come back as the
 * typed AI slugs — 409 `ai-disabled`, 400 `outbound-url-rejected`, 502
 * `ai-unreachable` / `ai-endpoint-unauthorized` / `ai-model-missing`, 504
 * `ai-timeout` — not as an `{ok: false}` payload.
 */
export async function testAiConnection(): Promise<AiConnectionTestResponse> {
  const response = await api.post<ApiResponse<AiConnectionTestResponse>>('/v1/ai/settings/test');
  return unwrap(response.data);
}

/**
 * `GET /v1/ai/models` — the models the member's own endpoint serves.
 *
 * A bare array; `meta` is absent because this list is not paginated. It needs
 * a saved `endpointUrl` and nothing else — pointedly **not** a `model`, which
 * is what it is there to help choose — so a picker can be populated before a
 * model has ever been set. 409 `ai-disabled` here means only that no endpoint
 * is saved.
 */
export async function fetchAiModels(): Promise<AiModel[]> {
  const response = await api.get<ApiResponse<AiModel[]>>('/v1/ai/models');
  return unwrap(response.data);
}
