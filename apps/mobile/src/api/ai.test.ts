// Imported first: it installs the mock adapter on `axios.defaults`.
import { calls, failureOf, reply, replyError, replyOk, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests } from './client';
import {
  deleteAiApiKey,
  fetchAiModels,
  fetchAiSettings,
  testAiConnection,
  updateAiSettings,
} from './ai';
import type { AiSettingsResponse } from './types';

const BASE = 'https://trove.example.com';

const SETTINGS: AiSettingsResponse = {
  enabled: true,
  endpointUrl: 'https://ai.example.test/v1',
  hasApiKey: true,
  model: 'qwen2.5:14b',
  visionModel: null,
  allowPrivateEndpoint: false,
  privacyTier: 'LOCAL_ONLY',
  connectTimeoutMs: 5000,
  readTimeoutMs: 120000,
  lastTestAt: '2026-09-04T16:41:02.113Z',
  lastTestOk: true,
  updatedAt: '2026-09-04T16:40:55.802Z',
};

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('fetchAiSettings', () => {
  it('reads the row', async () => {
    replyOk(SETTINGS);
    await expect(fetchAiSettings()).resolves.toEqual(SETTINGS);
    expect(calls()).toEqual(['GET /v1/ai/settings']);
  });

  it('carries no apiKey field at all — the key never leaves the server', async () => {
    replyOk(SETTINGS);
    const settings = await fetchAiSettings();
    expect(Object.keys(settings)).not.toContain('apiKey');
    // `hasApiKey` is deliberately the only key-shaped name on the row, so the
    // assertion is against the field name itself rather than a substring of it.
    expect(JSON.stringify(settings)).not.toMatch(/"apiKey"/);
    // What the response *does* say about the key is one boolean.
    expect(settings.hasApiKey).toBe(true);
  });

  it('answers the defaults, not a 404, for a member who has never saved anything', async () => {
    replyOk({ ...SETTINGS, enabled: false, endpointUrl: null, hasApiKey: false, model: null });
    const settings = await fetchAiSettings();
    expect(settings.enabled).toBe(false);
    expect(settings.endpointUrl).toBeNull();
  });
});

describe('updateAiSettings', () => {
  it('PUTs the whole row — it is a replace, not a patch', async () => {
    replyOk(SETTINGS);
    await updateAiSettings({
      enabled: true,
      allowPrivateEndpoint: false,
      privacyTier: 'LOCAL_ONLY',
      endpointUrl: 'https://ai.example.test/v1',
      model: 'qwen2.5:14b',
      visionModel: null,
    });
    expect(calls()).toEqual(['PUT /v1/ai/settings']);
    const body = JSON.parse(seen[0].data as string);
    // The three required fields are on every body, whatever else changed.
    expect(body.enabled).toBe(true);
    expect(body.allowPrivateEndpoint).toBe(false);
    expect(body.privacyTier).toBe('LOCAL_ONLY');
    // No key was being changed, so none is sent — absent leaves the stored one alone.
    expect(body).not.toHaveProperty('apiKey');
  });

  it('sends a key only when the member typed a new one, and never gets it back', async () => {
    replyOk(SETTINGS);
    const settings = await updateAiSettings({
      enabled: true,
      allowPrivateEndpoint: false,
      privacyTier: 'LOCAL_ONLY',
      apiKey: 'sk-super-secret',
    });
    expect(JSON.parse(seen[0].data as string).apiKey).toBe('sk-super-secret');
    expect(JSON.stringify(settings)).not.toContain('sk-super-secret');
  });

  it('surfaces the egress refusal as its own slug', async () => {
    replyError(400, 'outbound-url-rejected', 'https is required for an endpoint outside your LAN.');
    const failure = await failureOf(
      updateAiSettings({
        enabled: true,
        allowPrivateEndpoint: false,
        privacyTier: 'LOCAL_ONLY',
        endpointUrl: 'http://192.168.0.4:11434/v1',
      })
    );
    expect(failure.status).toBe(400);
    expect(failure.code).toBe('outbound-url-rejected');
    expect(failure.message).toBe('https is required for an endpoint outside your LAN.');
  });

  it('surfaces field errors as the LIST of pairs the contract sends', async () => {
    replyError(400, 'validation-error', 'One or more fields failed validation.', {
      fieldErrors: [{ field: 'model', message: 'must not be blank' }],
    });
    const failure = await failureOf(
      updateAiSettings({ enabled: true, allowPrivateEndpoint: false, privacyTier: 'LOCAL_ONLY' })
    );
    expect(failure.fieldErrors).toEqual([{ field: 'model', message: 'must not be blank' }]);
  });
});

describe('deleteAiApiKey', () => {
  it('accepts the closed EmptySuccess shape', async () => {
    reply(() => ({ status: 200, data: { success: true } }));
    await expect(deleteAiApiKey()).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/ai/settings/api-key']);
  });

  it('is idempotent — no 404 for a member who had no key', async () => {
    reply(() => ({ status: 200, data: { success: true } }));
    await expect(deleteAiApiKey()).resolves.toBeUndefined();
    await expect(deleteAiApiKey()).resolves.toBeUndefined();
  });
});

describe('testAiConnection', () => {
  it('POSTs with no body — the settings under test are the saved ones', async () => {
    replyOk({
      ok: true,
      endpointHost: 'ai.example.test',
      checkedAt: '2026-09-04T16:41:02.113Z',
      modelCount: 12,
      visionModelPresent: null,
    });
    const result = await testAiConnection();
    expect(calls()).toEqual(['POST /v1/ai/settings/test']);
    expect(seen[0].data).toBeUndefined();
    expect(result.endpointHost).toBe('ai.example.test');
  });

  it.each([
    [409, 'ai-disabled'],
    [502, 'ai-unreachable'],
    [502, 'ai-endpoint-unauthorized'],
    [502, 'ai-model-missing'],
    [504, 'ai-timeout'],
  ])('surfaces %s %s as a typed slug rather than an {ok:false} payload', async (status, slug) => {
    replyError(status as number, slug as string, 'It did not work.');
    const failure = await failureOf(testAiConnection());
    expect(failure.status).toBe(status);
    expect(failure.code).toBe(slug);
  });
});

describe('fetchAiModels', () => {
  it('unwraps the bare array — this list is not paged', async () => {
    replyOk([{ id: 'qwen2.5:14b', ownedBy: 'library', created: 1735689600 }]);
    await expect(fetchAiModels()).resolves.toEqual([
      { id: 'qwen2.5:14b', ownedBy: 'library', created: 1735689600 },
    ]);
    expect(calls()).toEqual(['GET /v1/ai/models']);
  });

  it('is 409 ai-disabled only when no endpoint is saved — not when no model is', async () => {
    replyError(409, 'ai-disabled', 'No endpoint is configured.');
    const failure = await failureOf(fetchAiModels());
    expect(failure.code).toBe('ai-disabled');
  });
});
