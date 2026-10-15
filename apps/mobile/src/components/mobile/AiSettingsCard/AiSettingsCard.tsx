import React, { useState } from 'react';
import { StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { MnBtn } from '@components/ui/MnBtn/MnBtn';
import { MnCard } from '@components/ui/MnCard/MnCard';
import { MnEyebrow } from '@components/primitives/MnEyebrow/MnEyebrow';
import { MnToggle } from '@components/ui/MnToggle/MnToggle';
import {
  useAiModels,
  useAiSettings,
  useDeleteAiApiKey,
  useTestAiConnection,
  useUpdateAiSettings,
} from '@hooks/useAiSettings';
import { toApiFailure, type ApiFailure } from '@api/errors';
import { FontFamily, FontSize, Radius, Space } from '@design/tokens';
import { useTheme } from '@design/useTheme';
import { useUserFormat } from '@lib/useUserFormat';
import type { AiSettingsResponse, AiSettingsUpdateRequest, PrivacyTier } from '@api/types';

/**
 * The AI endpoint card on Settings.
 *
 * It lives in its own component rather than inline in `app/(tabs)/settings.tsx`
 * for two reasons: that screen is shared with the two families that append
 * their own cards after this one, so this step's change to it is a single
 * line; and `app/` is outside the coverage glob while `src/` is not, so the
 * logic here is measured.
 *
 * ## The rule that cannot be broken
 *
 * **The API key is never echoed.** The server stores it AES-GCM-encrypted and
 * never returns it; `AiSettingsResponse` carries `hasApiKey` and nothing else
 * about it. So this card:
 *
 *  - shows *whether* a key is stored, never a value and **never a mask it
 *    invented** — a row of dots that is not the length of the real key is a
 *    lie about a credential;
 *  - keeps a key the member is typing in component state only, and clears that
 *    state **on every ending of the save**, not only a successful one — and
 *    calls `update.reset()` with it. `mutate` hands the body to React Query,
 *    which keeps it as `mutation.state.variables` in the app-level
 *    `MutationCache`; the single client in `app/_layout.tsx` overrides
 *    `defaultOptions.queries` only, so mutations keep v5's five-minute
 *    `gcTime` and the credential would stay reachable in the heap for five
 *    minutes after this card cleared its own field and unmounted — across a
 *    sign-out inside that window, because that client is never torn down.
 *    `reset()` drops `variables`, `data` and the retained `AxiosError` (whose
 *    `config.data` carries the key on a failed save) immediately instead of
 *    waiting the timer out. This is the same fix, for the same mechanism, that
 *    `app/(tabs)/transactions/accounts.tsx` already applies to the SimpleFIN
 *    setup token. The refusal is copied into component state first, which is
 *    why the banner survives the reset. It is never written to the secure
 *    store, a log, a crash report or a query cache;
 *  - removes a key through `DELETE /v1/ai/settings/api-key`. There is no "save
 *    it as empty" — a blank value is refused server-side precisely so a form
 *    that cannot tell "left alone" from "emptied" cannot wipe a working key.
 *
 * ## `PUT` is a replace
 *
 * Every field except `apiKey` takes its value from the body, and an omitted
 * nullable field is written as null. The form therefore holds the whole row
 * and sends the whole row; sending a partial body would clear the fields it
 * left out.
 */

const PRIVACY_TIERS: ReadonlyArray<{ value: PrivacyTier; label: string; note: string }> = [
  {
    value: 'LOCAL_ONLY',
    label: 'Local only',
    note: 'Required for the coach: its questions carry figures from your ledger.',
  },
  {
    value: 'CLOUD_OK',
    label: 'Cloud allowed',
    note: 'Lower-sensitivity work only. The coach is refused at this tier.',
  },
];

/**
 * Copy for a `GET /v1/ai/settings` that has not answered.
 *
 * Kept apart from {@link aiFailureCopy} because it is a different sentence:
 * the mutations refuse *this* action, while this one means the card does not
 * know what the member's settings are — which is exactly when it must not
 * offer to replace them. A 429 is a wait-and-retry state with a real number,
 * worded the way the receipt detail screen words the same limiter.
 */
export function aiLoadFailureCopy(failure: ApiFailure): string {
  if (failure.status === 429) {
    return `Your AI settings could not be loaded: too many requests. Try again${
      failure.retryAfterSeconds === null
        ? ' in a moment'
        : ` in ${failure.retryAfterSeconds} seconds`
    }.`;
  }
  return `Your AI settings could not be loaded. ${aiFailureCopy(failure.code, failure.message)}`;
}

/** Copy for the AI slugs these five operations really emit. */
export function aiFailureCopy(code: string | null, detail: string): string {
  switch (code) {
    case 'ai-disabled':
      return 'No endpoint is saved yet. Add one and save before testing.';
    case 'outbound-url-rejected':
      return detail;
    case 'ai-unreachable':
      return 'That endpoint could not be reached from your Trove server.';
    case 'ai-endpoint-unauthorized':
      return 'That endpoint rejected the stored API key.';
    case 'ai-model-missing':
      return 'That endpoint does not serve the chat model you chose.';
    case 'ai-timeout':
      return 'That endpoint did not answer inside your read timeout.';
    default:
      return detail;
  }
}

export function AiSettingsCard() {
  const theme = useTheme();
  const format = useUserFormat();
  const settings = useAiSettings();
  const update = useUpdateAiSettings();
  const removeKey = useDeleteAiApiKey();
  const test = useTestAiConnection();

  const [enabled, setEnabled] = useState(false);
  const [endpointUrl, setEndpointUrl] = useState('');
  const [model, setModel] = useState('');
  const [visionModel, setVisionModel] = useState('');
  const [allowPrivateEndpoint, setAllowPrivateEndpoint] = useState(false);
  const [privacyTier, setPrivacyTier] = useState<PrivacyTier>('LOCAL_ONLY');
  /**
   * The key the member is typing, and nothing else. Never seeded from the
   * server — there is nothing to seed it from — and cleared when the save
   * settles, on every path rather than only a successful one: a key left in
   * state after a refusal is a credential sitting in memory for as long as
   * Settings is mounted.
   */
  const [apiKey, setApiKey] = useState('');
  /**
   * The save's refusal, copied out of the mutation.
   *
   * `update.reset()` runs on every settled save (see the header — it is what
   * evicts the key from the `MutationCache`), and reset clears `update.error`
   * with everything else. Copying the refusal here first is what keeps the
   * banner on screen afterwards.
   */
  const [saveError, setSaveError] = useState<unknown>(null);
  const loaded = settings.data !== undefined;

  /**
   * Seed the form from the row, **during render** rather than in an effect.
   *
   * This is React's own "adjust state when the data changes" pattern: an
   * effect that calls six setters runs a whole extra render pass with the
   * defaults on screen first, which is a flash of "AI is off" for a member
   * whose AI is on. Comparing against the row object itself is enough — React
   * Query hands back the same reference until the row actually changes, and a
   * refetch that returns equal data is a no-op because the reference is kept.
   */
  const [seededFrom, setSeededFrom] = useState<AiSettingsResponse | null>(null);
  if (settings.data !== undefined && settings.data !== seededFrom) {
    const row = settings.data;
    setSeededFrom(row);
    setEnabled(row.enabled);
    setEndpointUrl(row.endpointUrl ?? '');
    setModel(row.model ?? '');
    setVisionModel(row.visionModel ?? '');
    setAllowPrivateEndpoint(row.allowPrivateEndpoint);
    setPrivacyTier(row.privacyTier);
  }

  const models = useAiModels(loaded && (settings.data?.endpointUrl ?? '') !== '');

  /**
   * The row has not arrived. Until it does the card knows nothing about the
   * member's endpoint, model or key — so it says so and offers no form.
   *
   * `PUT /v1/ai/settings` is a **replace**: a Save pressed against the
   * `useState` defaults would send `endpointUrl: null, model: null,
   * visionModel: null, enabled: false`, wiping a working configuration the
   * card had never been told about. The only safe answer is not to render the
   * form at all until there is a row behind it, and to say what went wrong
   * when there never will be.
   */
  const loadFailure =
    !loaded && settings.error !== null && settings.error !== undefined
      ? toApiFailure(settings.error, 'Your AI settings could not be loaded.')
      : null;

  const failure =
    saveError !== null
      ? toApiFailure(saveError, 'Those settings could not be saved.')
      : test.error !== null
        ? toApiFailure(test.error, 'That endpoint could not be tested.')
        : removeKey.error !== null
          ? toApiFailure(removeKey.error, 'That key could not be removed.')
          : null;

  const save = () => {
    // Belt and braces with the render gate below: a Save that reached this
    // function without a row behind it would send the defaults as a replace.
    if (!loaded) return;
    setSaveError(null);
    const body: AiSettingsUpdateRequest = {
      enabled,
      allowPrivateEndpoint,
      privacyTier,
      endpointUrl: endpointUrl.trim() === '' ? null : endpointUrl.trim(),
      model: model.trim() === '' ? null : model.trim(),
      visionModel: visionModel.trim() === '' ? null : visionModel.trim(),
    };
    // Absent leaves the stored key alone. A blank field means "I did not
    // change it", which is the only reading a write-only field allows.
    if (apiKey !== '') body.apiKey = apiKey;
    update.mutate(body, {
      onError: (err) => setSaveError(err),
      // Cleared on EVERY path, not only on success — and `reset()` with it, so
      // the key does not sit in `mutation.state.variables` in the app-level
      // MutationCache for React Query's five-minute default `gcTime`. See the
      // module header; this is the same fix `accounts.tsx` applies to the
      // SimpleFIN setup token.
      onSettled: () => {
        setApiKey('');
        update.reset();
      },
    });
  };

  return (
    <View testID="settings-ai-section">
      <MnEyebrow style={styles.section}>AI</MnEyebrow>
      <MnCard style={styles.card}>
        <Text style={[styles.note, { color: theme.ink3 }]}>
          Trove ships no AI service. Point it at an endpoint you control — a local Ollama, or any
          OpenAI-compatible URL. These settings are yours alone: nobody else in your household can
          see or use them.
        </Text>

        {!loaded && loadFailure !== null && (
          <Text
            style={[styles.note, { color: theme.red }]}
            accessibilityRole="alert"
            testID="settings-ai-load-error"
          >
            {aiLoadFailureCopy(loadFailure)}
          </Text>
        )}

        {!loaded && loadFailure === null && (
          <Text
            style={[styles.note, { color: theme.ink3 }]}
            accessibilityLabel="Loading your AI settings"
            testID="settings-ai-loading"
          >
            Loading your AI settings…
          </Text>
        )}

        {loaded && (
          <>
            <MnToggle
              label="Use AI features"
              value={enabled}
              onToggle={setEnabled}
              testID="settings-ai-enabled"
            />

            <Text style={[styles.label, { color: theme.ink3 }]}>Endpoint URL</Text>
            <TextInput
              value={endpointUrl}
              onChangeText={setEndpointUrl}
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="url"
              placeholder="https://ai.example.test/v1"
              placeholderTextColor={theme.ink3}
              accessibilityLabel="AI endpoint URL"
              style={[styles.input, { borderColor: theme.line, color: theme.ink }]}
              testID="settings-ai-endpoint"
            />

            <Text style={[styles.label, { color: theme.ink3 }]}>API key</Text>
            <Text style={[styles.note, { color: theme.ink3 }]} testID="settings-ai-key-state">
              {settings.data?.hasApiKey === true
                ? 'A key is stored on your server. It is encrypted there and is never sent back to this app, so it cannot be shown here.'
                : 'No key is stored. Leave this blank for an endpoint that needs none.'}
            </Text>
            <TextInput
              value={apiKey}
              onChangeText={setApiKey}
              autoCapitalize="none"
              autoCorrect={false}
              secureTextEntry
              placeholder="Enter a new key to replace the stored one"
              placeholderTextColor={theme.ink3}
              accessibilityLabel="New AI API key"
              style={[styles.input, { borderColor: theme.line, color: theme.ink }]}
              testID="settings-ai-key"
            />
            {settings.data?.hasApiKey === true && (
              <MnBtn
                label="Forget the stored key"
                variant="ghost"
                loading={removeKey.isPending}
                onPress={() => removeKey.mutate()}
                style={styles.rowBtn}
                testID="settings-ai-key-delete"
              />
            )}

            <Text style={[styles.label, { color: theme.ink3 }]}>Chat model</Text>
            <TextInput
              value={model}
              onChangeText={setModel}
              autoCapitalize="none"
              autoCorrect={false}
              placeholder="qwen2.5:14b"
              placeholderTextColor={theme.ink3}
              accessibilityLabel="Chat model"
              style={[styles.input, { borderColor: theme.line, color: theme.ink }]}
              testID="settings-ai-model"
            />
            {models.data !== undefined && models.data.length > 0 && (
              <View style={styles.choices} testID="settings-ai-model-choices">
                {models.data.map((candidate) => (
                  <TouchableOpacity
                    key={candidate.id}
                    onPress={() => setModel(candidate.id)}
                    accessibilityRole="radio"
                    accessibilityState={{ selected: model === candidate.id }}
                    accessibilityLabel={candidate.id}
                    style={[
                      styles.chip,
                      {
                        borderColor: model === candidate.id ? theme.sage : theme.line,
                        backgroundColor: model === candidate.id ? theme.sageSoft : 'transparent',
                      },
                    ]}
                    testID={`settings-ai-model-${candidate.id}`}
                  >
                    <Text
                      style={[
                        styles.chipText,
                        { color: model === candidate.id ? theme.sage : theme.ink2 },
                      ]}
                    >
                      {candidate.id}
                    </Text>
                  </TouchableOpacity>
                ))}
              </View>
            )}

            <Text style={[styles.label, { color: theme.ink3 }]}>Vision model (receipts)</Text>
            <TextInput
              value={visionModel}
              onChangeText={setVisionModel}
              autoCapitalize="none"
              autoCorrect={false}
              placeholder="Optional"
              placeholderTextColor={theme.ink3}
              accessibilityLabel="Vision model for receipts"
              style={[styles.input, { borderColor: theme.line, color: theme.ink }]}
              testID="settings-ai-vision-model"
            />
            <Text style={[styles.note, { color: theme.ink3 }]}>
              Without one, receipts still upload — you fill the fields in yourself.
            </Text>

            <Text style={[styles.label, { color: theme.ink3 }]}>Privacy tier</Text>
            <View style={styles.choices}>
              {PRIVACY_TIERS.map((tier) => (
                <TouchableOpacity
                  key={tier.value}
                  onPress={() => setPrivacyTier(tier.value)}
                  accessibilityRole="radio"
                  accessibilityState={{ selected: privacyTier === tier.value }}
                  accessibilityLabel={tier.label}
                  style={[
                    styles.chip,
                    {
                      borderColor: privacyTier === tier.value ? theme.sage : theme.line,
                      backgroundColor: privacyTier === tier.value ? theme.sageSoft : 'transparent',
                    },
                  ]}
                  testID={`settings-ai-tier-${tier.value}`}
                >
                  <Text
                    style={[
                      styles.chipText,
                      { color: privacyTier === tier.value ? theme.sage : theme.ink2 },
                    ]}
                  >
                    {tier.label}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>
            <Text style={[styles.note, { color: theme.ink3 }]}>
              {PRIVACY_TIERS.find((tier) => tier.value === privacyTier)?.note}
            </Text>

            <MnToggle
              label="Allow a private or LAN address"
              value={allowPrivateEndpoint}
              onToggle={setAllowPrivateEndpoint}
              testID="settings-ai-allow-private"
            />
            <Text style={[styles.note, { color: theme.ink3 }]}>
              Your half of the opt-in. Your server has to allow private targets as well, so turning
              this on does not by itself make a LAN address reachable.
            </Text>

            {/*
          `disabled={!loaded}` is redundant while the whole form sits behind
          the `loaded` gate above, and it is kept anyway: it is the assertion
          that survives if a later family moves the gate, and a replace sent
          from the unseeded defaults clears the member's endpoint and models.
        */}
            <MnBtn
              label="Save AI settings"
              loading={update.isPending}
              disabled={!loaded}
              onPress={save}
              style={styles.rowBtn}
              testID="settings-ai-save"
            />
            <MnBtn
              label="Test this endpoint"
              variant="secondary"
              loading={test.isPending}
              onPress={() => test.mutate()}
              style={styles.rowBtn}
              testID="settings-ai-test"
            />

            {test.data !== undefined && (
              <Text
                style={[styles.note, { color: theme.sage }]}
                accessibilityRole="alert"
                testID="settings-ai-test-ok"
              >
                {test.data.endpointHost} answered and serves {format.number(test.data.modelCount)}{' '}
                models
                {test.data.visionModelPresent === false
                  ? ', but not the vision model you chose — receipts will fall back to the manual path.'
                  : '.'}
              </Text>
            )}

            {settings.data?.lastTestAt !== null && settings.data?.lastTestAt !== undefined && (
              <Text style={[styles.note, { color: theme.ink3 }]} testID="settings-ai-last-test">
                Last tested {format.date(settings.data.lastTestAt, 'dateTime')} —{' '}
                {settings.data.lastTestOk === true ? 'it worked' : 'it failed'}.
              </Text>
            )}

            {failure !== null && (
              <Text
                style={[styles.note, { color: theme.red }]}
                accessibilityRole="alert"
                testID="settings-ai-error"
              >
                {aiFailureCopy(failure.code, failure.message)}
              </Text>
            )}
          </>
        )}
      </MnCard>
    </View>
  );
}

const styles = StyleSheet.create({
  section: { marginTop: Space.s4 },
  card: { marginBottom: Space.s4 },
  label: {
    fontFamily: FontFamily.bodySemi,
    fontSize: FontSize.xs,
    letterSpacing: 1,
    marginTop: Space.s3,
    textTransform: 'uppercase',
  },
  note: { fontFamily: FontFamily.body, fontSize: FontSize.xs, marginTop: Space.s1 },
  input: {
    fontFamily: FontFamily.body,
    fontSize: FontSize.md,
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Space.s3,
    marginTop: Space.s2,
    minHeight: 44,
  },
  rowBtn: { marginTop: Space.s3 },
  choices: { flexDirection: 'row', flexWrap: 'wrap', gap: Space.s2, marginTop: Space.s2 },
  chip: {
    paddingHorizontal: Space.s3,
    paddingVertical: Space.s2,
    borderRadius: Radius.md,
    borderWidth: 1,
    minHeight: 44,
    justifyContent: 'center',
  },
  chipText: { fontFamily: FontFamily.bodyMedium, fontSize: FontSize.sm },
});
