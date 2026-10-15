/**
 * React Query bindings for `/v1/ai/*`.
 *
 * Shared by the two surfaces step 214 lands, which is why it is one module:
 * the **AI settings card** on `settings.tsx` writes these, and the **coach**
 * reads them to decide whether it can work at all. A coach that only found
 * out AI was off by getting a 409 would render a broken chat and then an
 * error toast; asking first is what lets it render "the coach needs your own
 * AI endpoint" with a route to the card instead.
 *
 * **Nothing in this module ever holds an API key.** `AiSettingsResponse` has
 * no `apiKey` field — the server stores it encrypted and never returns it —
 * so a key exists only as the component state of the form that is typing one,
 * and is handed straight to `updateAiSettings`. It is never put in this query
 * cache.
 *
 * **It is not, however, free of the mutation cache, and the caller has to do
 * something about that.** `mutate` hands its body to React Query as
 * `mutation.state.variables`, held in the app-level `MutationCache`; the
 * single client in `app/_layout.tsx` overrides `defaultOptions.queries` only,
 * so mutations keep v5's five-minute default `gcTime` (`gcTime: 0` is set on
 * mutations by `src/test/queryClient.ts`, which is the *test* client and not
 * the shipped one). A settled mutation with no observers is therefore
 * collected five minutes later, not immediately — long enough to outlive the
 * screen and a sign-out inside that window, because the client is never torn
 * down. So **whoever calls {@link useUpdateAiSettings} with a key must call
 * `reset()` on the result when the save settles**;
 * `AiSettingsCard` does exactly that, as `accounts.tsx` does for the SimpleFIN
 * setup token.
 */

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  deleteAiApiKey,
  fetchAiModels,
  fetchAiSettings,
  testAiConnection,
  updateAiSettings,
} from '@api/ai';
import type {
  AiConnectionTestResponse,
  AiModel,
  AiSettingsResponse,
  AiSettingsUpdateRequest,
} from '@api/types';
import { retryUnlessRefused } from './retryPolicy';

export const aiKeys = {
  all: () => ['ai'] as const,
  settings: () => ['ai', 'settings'] as const,
  models: () => ['ai', 'models'] as const,
};

/**
 * `GET /v1/ai/settings`.
 *
 * Never 404s: a member who has never saved anything gets the defaults, so
 * `data === undefined` here means "not loaded yet" and never "not
 * configured". The configured-ness question is {@link coachReadiness}.
 */
export function useAiSettings(): UseQueryResult<AiSettingsResponse> {
  return useQuery({
    queryKey: aiKeys.settings(),
    queryFn: fetchAiSettings,
    staleTime: 60_000,
    retry: retryUnlessRefused,
  });
}

/**
 * `GET /v1/ai/models` — populates the model picker.
 *
 * `enabled` is the caller's, because the operation needs a saved
 * `endpointUrl` and nothing else: a member who has saved an endpoint and no
 * model is exactly who this list is for, so a picker asks for it as soon as
 * there is an endpoint, not once a model has been chosen.
 */
export function useAiModels(enabled: boolean): UseQueryResult<AiModel[]> {
  return useQuery({
    queryKey: aiKeys.models(),
    queryFn: fetchAiModels,
    enabled,
    staleTime: 60_000,
    retry: retryUnlessRefused,
  });
}

/**
 * `PUT /v1/ai/settings` — a replace.
 *
 * The models list is invalidated with the settings: changing the endpoint
 * changes which models exist, and a picker still offering the old endpoint's
 * ids would write one the new endpoint does not serve.
 */
export function useUpdateAiSettings(): UseMutationResult<
  AiSettingsResponse,
  Error,
  AiSettingsUpdateRequest
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: AiSettingsUpdateRequest) => updateAiSettings(body),
    onSuccess: (settings) => {
      // The response *is* the new state, so the cache is written rather than
      // refetched. It carries `hasApiKey`, never the key.
      client.setQueryData(aiKeys.settings(), settings);
      void client.invalidateQueries({ queryKey: aiKeys.models() });
    },
  });
}

/** `DELETE /v1/ai/settings/api-key` — idempotent; the endpoint and models stay. */
export function useDeleteAiApiKey(): UseMutationResult<void, Error, void> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => deleteAiApiKey(),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: aiKeys.settings() });
    },
  });
}

/**
 * `POST /v1/ai/settings/test` — no body.
 *
 * The settings row records `lastTestAt` / `lastTestOk`, so a successful test
 * changes the settings the card is showing and they are refetched.
 */
export function useTestAiConnection(): UseMutationResult<AiConnectionTestResponse, Error, void> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => testAiConnection(),
    onSettled: () => {
      void client.invalidateQueries({ queryKey: aiKeys.settings() });
    },
  });
}

/** Why the coach cannot run, or `null` when it can. */
export type CoachBlock =
  /** No endpoint saved at all, or the member has switched AI off. */
  | 'disabled'
  /** An endpoint is saved but no chat model has been chosen. */
  | 'no-model'
  /**
   * The privacy tier forbids it. Every coach payload is classified
   * `SENSITIVE`, so `CLOUD_OK` is refused with 403 `ai-policy-blocked`
   * *before any connection is opened* — the coach can and should say so
   * rather than sending a question that cannot succeed.
   */
  | 'privacy-tier';

/**
 * Whether the coach can ask a question, decided from the settings row alone.
 *
 * Exported and pure so the rule is testable without a screen. It mirrors what
 * the server enforces; it does not replace it — the routes still answer 409
 * `ai-disabled` and 403 `ai-policy-blocked`, and the coach handles both.
 */
export function coachReadiness(settings: AiSettingsResponse | undefined): CoachBlock | null {
  if (settings === undefined) return null;
  if (!settings.enabled || settings.endpointUrl === null || settings.endpointUrl === '') {
    return 'disabled';
  }
  if (settings.model === null || settings.model === '') return 'no-model';
  if (settings.privacyTier !== 'LOCAL_ONLY') return 'privacy-tier';
  return null;
}
