import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { AxiosError, AxiosHeaders } from 'axios';
import { createTestQueryClient } from '@/test/queryClient';
import { AiSettingsCard, aiFailureCopy } from './AiSettingsCard';
import {
  deleteAiApiKey,
  fetchAiModels,
  fetchAiSettings,
  testAiConnection,
  updateAiSettings,
} from '@api/ai';
import * as SecureStore from 'expo-secure-store';
import type { AiSettingsResponse } from '@api/types';

jest.mock('@api/ai', () => ({
  fetchAiSettings: jest.fn(),
  updateAiSettings: jest.fn(),
  deleteAiApiKey: jest.fn(),
  testAiConnection: jest.fn(),
  fetchAiModels: jest.fn(),
}));

const mockFetch = fetchAiSettings as jest.MockedFunction<typeof fetchAiSettings>;
const mockUpdate = updateAiSettings as jest.MockedFunction<typeof updateAiSettings>;
const mockDeleteKey = deleteAiApiKey as jest.MockedFunction<typeof deleteAiApiKey>;
const mockTest = testAiConnection as jest.MockedFunction<typeof testAiConnection>;
const mockModels = fetchAiModels as jest.MockedFunction<typeof fetchAiModels>;

const SECRET = 'sk-this-must-never-be-seen-again';

const SAVED: AiSettingsResponse = {
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

const DEFAULTS: AiSettingsResponse = {
  ...SAVED,
  enabled: false,
  endpointUrl: null,
  hasApiKey: false,
  model: null,
  privacyTier: 'CLOUD_OK',
  lastTestAt: null,
  lastTestOk: null,
  updatedAt: null,
};

function refusal(
  status: number,
  slug: string,
  detail: string,
  headers: Record<string, string> = {}
) {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, {
    status,
    statusText: '',
    headers,
    config,
    data: {
      success: false,
      error: {
        type: `https://trove.finance/errors/${slug}`,
        title: slug,
        status,
        detail,
        instance: '/api/v1/ai/settings',
      },
    },
  } as never);
}

let clients: ReturnType<typeof createTestQueryClient>[] = [];

function createWrapperWithClient() {
  const qc = createTestQueryClient();
  clients.push(qc);
  const Wrapper = function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  };
  return { qc, Wrapper };
}

function createWrapper() {
  return createWrapperWithClient().Wrapper;
}

beforeEach(() => {
  jest.clearAllMocks();
  mockFetch.mockResolvedValue(SAVED);
  mockUpdate.mockResolvedValue(SAVED);
  mockDeleteKey.mockResolvedValue(undefined);
  mockModels.mockResolvedValue([]);
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('aiFailureCopy', () => {
  it.each([
    'ai-disabled',
    'ai-unreachable',
    'ai-endpoint-unauthorized',
    'ai-model-missing',
    'ai-timeout',
  ])('has its own sentence for %s', (slug) => {
    expect(aiFailureCopy(slug, 'server detail')).not.toBe('server detail');
  });

  it("shows the egress refusal's own sentence, which names the rule", () => {
    expect(aiFailureCopy('outbound-url-rejected', 'https is required.')).toBe('https is required.');
  });

  it("falls back to the server's sentence for an unknown slug", () => {
    expect(aiFailureCopy('something-new', 'server detail')).toBe('server detail');
  });
});

describe('AiSettingsCard — the API key', () => {
  it('says a key is STORED without showing one, and invents no mask', async () => {
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });
    // The card renders before the row arrives, so wait for the row itself —
    // waiting for the field would assert against the defaults.
    await waitFor(() =>
      expect(getByTestId('settings-ai-endpoint').props.value).toBe('https://ai.example.test/v1')
    );

    const state = String(getByTestId('settings-ai-key-state').props.children);
    expect(state).toContain('encrypted');
    expect(state).not.toMatch(/[•*]{3,}/);
    // The field itself starts empty: there is nothing on the server to seed it from.
    expect(getByTestId('settings-ai-key').props.value).toBe('');
  });

  it('says plainly when no key is stored', async () => {
    mockFetch.mockResolvedValue(DEFAULTS);
    const { getByTestId, queryByTestId } = await render(<AiSettingsCard />, {
      wrapper: createWrapper(),
    });
    await waitFor(() =>
      expect(String(getByTestId('settings-ai-key-state').props.children)).toContain(
        'No key is stored'
      )
    );
    // Nothing to forget, so nothing to press.
    expect(queryByTestId('settings-ai-key-delete')).toBeNull();
  });

  it('sends a typed key, then CLEARS it from the form and shows it nowhere', async () => {
    const { getByTestId, queryByText, toJSON } = await render(<AiSettingsCard />, {
      wrapper: createWrapper(),
    });
    await waitFor(() => expect(getByTestId('settings-ai-save')).toBeTruthy());

    await fireEvent.changeText(getByTestId('settings-ai-key'), SECRET);
    await fireEvent.press(getByTestId('settings-ai-save'));

    await waitFor(() => expect(mockUpdate).toHaveBeenCalled());
    expect(mockUpdate.mock.calls[0][0].apiKey).toBe(SECRET);

    await waitFor(() => expect(getByTestId('settings-ai-key').props.value).toBe(''));
    expect(queryByText(SECRET)).toBeNull();
    // The whole rendered tree, not just the field: no snapshot of this screen
    // may ever carry a credential.
    expect(JSON.stringify(toJSON())).not.toContain(SECRET);
  });

  it('never writes the key to the secure store', async () => {
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('settings-ai-save')).toBeTruthy());
    await fireEvent.changeText(getByTestId('settings-ai-key'), SECRET);
    await fireEvent.press(getByTestId('settings-ai-save'));
    await waitFor(() => expect(mockUpdate).toHaveBeenCalled());

    for (const call of (SecureStore.setItemAsync as jest.Mock).mock.calls) {
      expect(JSON.stringify(call)).not.toContain(SECRET);
    }
  });

  it('leaves NOTHING of the key in the app-level MutationCache after a save', async () => {
    const { qc, Wrapper } = createWrapperWithClient();
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: Wrapper });
    await waitFor(() => expect(getByTestId('settings-ai-save')).toBeTruthy());

    await fireEvent.changeText(getByTestId('settings-ai-key'), SECRET);
    await fireEvent.press(getByTestId('settings-ai-save'));
    await waitFor(() => expect(mockUpdate).toHaveBeenCalled());
    await waitFor(() => expect(getByTestId('settings-ai-key').props.value).toBe(''));

    // `mutate` hands the body to React Query, which keeps it as
    // `state.variables` on a Mutation the app-level cache holds for `gcTime`
    // — five minutes on the shipped client, which overrides the query branch
    // only, and the card is still mounted so nothing has been collected.
    // Clearing the TextInput does nothing about that copy; `reset()` does,
    // and this is the assertion that it happened.
    const cached = JSON.stringify(
      qc
        .getMutationCache()
        .getAll()
        .map((m) => m.state)
    );
    expect(cached).not.toContain(SECRET);
  });

  it('leaves nothing of the key in the MutationCache after a FAILED save either', async () => {
    // The retained AxiosError carries the key in `config.data` as well as the
    // variables, so the failure path is the one that matters most.
    mockUpdate.mockRejectedValue(refusal(502, 'ai-unreachable', 'Could not reach it.'));
    const { qc, Wrapper } = createWrapperWithClient();
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: Wrapper });
    await waitFor(() => expect(getByTestId('settings-ai-save')).toBeTruthy());

    await fireEvent.changeText(getByTestId('settings-ai-key'), SECRET);
    await fireEvent.press(getByTestId('settings-ai-save'));
    await waitFor(() => expect(mockUpdate).toHaveBeenCalled());

    // Cleared on every path, not only on success.
    await waitFor(() => expect(getByTestId('settings-ai-key').props.value).toBe(''));
    const cached = JSON.stringify(
      qc
        .getMutationCache()
        .getAll()
        .map((m) => m.state)
    );
    expect(cached).not.toContain(SECRET);
    // …and the member still sees the refusal, because it is copied out before
    // the reset that drops it.
    expect(String(getByTestId('settings-ai-error').props.children)).toContain(
      'could not be reached'
    );
  });

  it('omits apiKey entirely when the member did not type one — absent leaves it alone', async () => {
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('settings-ai-save')).toBeTruthy());
    await fireEvent.press(getByTestId('settings-ai-save'));
    await waitFor(() => expect(mockUpdate).toHaveBeenCalled());
    expect(mockUpdate.mock.calls[0][0]).not.toHaveProperty('apiKey');
  });

  it('removes a key through the dedicated route, never by saving a blank one', async () => {
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('settings-ai-key-delete')).toBeTruthy());
    await fireEvent.press(getByTestId('settings-ai-key-delete'));
    await waitFor(() => expect(mockDeleteKey).toHaveBeenCalled());
    expect(mockUpdate).not.toHaveBeenCalled();
  });
});

describe('AiSettingsCard — saving', () => {
  it('sends the WHOLE row, because PUT is a replace', async () => {
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });
    await waitFor(() =>
      expect(getByTestId('settings-ai-endpoint').props.value).toBe('https://ai.example.test/v1')
    );
    await fireEvent.press(getByTestId('settings-ai-save'));
    await waitFor(() => expect(mockUpdate).toHaveBeenCalled());

    const body = mockUpdate.mock.calls[0][0];
    expect(body.enabled).toBe(true);
    expect(body.allowPrivateEndpoint).toBe(false);
    expect(body.privacyTier).toBe('LOCAL_ONLY');
    expect(body.endpointUrl).toBe('https://ai.example.test/v1');
    expect(body.model).toBe('qwen2.5:14b');
    // An omitted nullable field is written as null, so "cleared" is sent as
    // null rather than left out and silently kept.
    expect(body.visionModel).toBeNull();
  });

  it('sends a cleared endpoint as null rather than an empty string', async () => {
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });
    await waitFor(() =>
      expect(getByTestId('settings-ai-endpoint').props.value).toBe('https://ai.example.test/v1')
    );
    await fireEvent.changeText(getByTestId('settings-ai-endpoint'), '   ');
    await fireEvent.press(getByTestId('settings-ai-save'));
    await waitFor(() => expect(mockUpdate).toHaveBeenCalled());
    expect(mockUpdate.mock.calls[0][0].endpointUrl).toBeNull();
  });

  it('shows the egress refusal instead of pre-judging the URL itself', async () => {
    mockUpdate.mockRejectedValue(
      refusal(400, 'outbound-url-rejected', 'https is required unless private targets are allowed.')
    );
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('settings-ai-endpoint')).toBeTruthy());

    // A private, non-https URL is typed and SENT — only the server knows
    // whether this install allows a LAN Ollama.
    await fireEvent.changeText(getByTestId('settings-ai-endpoint'), 'http://192.168.0.4:11434/v1');
    await fireEvent.press(getByTestId('settings-ai-save'));

    await waitFor(() => expect(mockUpdate).toHaveBeenCalled());
    const shown = await waitFor(() => getByTestId('settings-ai-error'));
    expect(String(shown.props.children)).toBe(
      'https is required unless private targets are allowed.'
    );
  });

  it('lets the member choose the privacy tier the coach needs', async () => {
    mockFetch.mockResolvedValue(DEFAULTS);
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('settings-ai-tier-LOCAL_ONLY')).toBeTruthy());
    await fireEvent.press(getByTestId('settings-ai-tier-LOCAL_ONLY'));
    await fireEvent.press(getByTestId('settings-ai-save'));
    await waitFor(() => expect(mockUpdate).toHaveBeenCalled());
    expect(mockUpdate.mock.calls[0][0].privacyTier).toBe('LOCAL_ONLY');
  });
});

describe('AiSettingsCard — before the row arrives', () => {
  it('shows a loading line and offers NO form, because PUT is a replace', async () => {
    let release: (row: AiSettingsResponse) => void = () => {};
    mockFetch.mockReturnValue(
      new Promise<AiSettingsResponse>((resolve) => {
        release = resolve;
      })
    );
    const { getByTestId, queryByTestId } = await render(<AiSettingsCard />, {
      wrapper: createWrapper(),
    });

    expect(getByTestId('settings-ai-loading')).toBeTruthy();
    // Nothing to press: a Save against the useState defaults would send
    // `endpointUrl: null, model: null, visionModel: null, enabled: false` as a
    // replace and wipe a working configuration this card was never told about.
    expect(queryByTestId('settings-ai-save')).toBeNull();
    expect(queryByTestId('settings-ai-endpoint')).toBeNull();
    // …and it does not claim there is no key, which it has not been told.
    expect(queryByTestId('settings-ai-key-state')).toBeNull();

    await act(() => release(SAVED));
    await waitFor(() => expect(getByTestId('settings-ai-save')).toBeTruthy());
    expect(getByTestId('settings-ai-endpoint').props.value).toBe('https://ai.example.test/v1');
    expect(mockUpdate).not.toHaveBeenCalled();
  });

  it('shows the refusal when the settings cannot be loaded, and still offers no Save', async () => {
    mockFetch.mockRejectedValue(refusal(503, 'service-unavailable', 'The limiter is down.'));
    const { getByTestId, queryByTestId } = await render(<AiSettingsCard />, {
      wrapper: createWrapper(),
    });

    const shown = await waitFor(() => getByTestId('settings-ai-load-error'));
    expect(String(shown.props.children)).toContain('could not be loaded');
    expect(String(shown.props.children)).toContain('The limiter is down.');
    expect(queryByTestId('settings-ai-save')).toBeNull();
    expect(queryByTestId('settings-ai-loading')).toBeNull();
    expect(mockUpdate).not.toHaveBeenCalled();
  });

  it('words a 429 with the seconds the limiter asked for', async () => {
    mockFetch.mockRejectedValue(
      refusal(429, 'rate-limited', 'Too many requests.', { 'retry-after': '30' })
    );
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });
    const shown = await waitFor(() => getByTestId('settings-ai-load-error'));
    expect(String(shown.props.children)).toContain('in 30 seconds');
  });

  it('falls back to "in a moment" when a proxy stripped Retry-After', async () => {
    mockFetch.mockRejectedValue(refusal(429, 'rate-limited', 'Too many requests.'));
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });
    const shown = await waitFor(() => getByTestId('settings-ai-load-error'));
    expect(String(shown.props.children)).toContain('in a moment');
  });
});

describe('AiSettingsCard — models and testing', () => {
  it('populates the picker from the endpoint, which works before a model is chosen', async () => {
    mockFetch.mockResolvedValue({ ...SAVED, model: null });
    mockModels.mockResolvedValue([
      { id: 'qwen2.5:14b', ownedBy: 'library', created: null },
      { id: 'llama3.2:3b', ownedBy: null, created: null },
    ]);
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });

    await waitFor(() => expect(getByTestId('settings-ai-model-choices')).toBeTruthy());
    await fireEvent.press(getByTestId('settings-ai-model-llama3.2:3b'));
    await fireEvent.press(getByTestId('settings-ai-save'));
    await waitFor(() => expect(mockUpdate).toHaveBeenCalled());
    expect(mockUpdate.mock.calls[0][0].model).toBe('llama3.2:3b');
  });

  it('asks for no models when no endpoint is saved', async () => {
    mockFetch.mockResolvedValue(DEFAULTS);
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('settings-ai-save')).toBeTruthy());
    expect(mockModels).not.toHaveBeenCalled();
  });

  it('reports a green test with the host and the model count', async () => {
    mockTest.mockResolvedValue({
      ok: true,
      endpointHost: 'ai.example.test',
      checkedAt: '2026-09-04T16:41:02.113Z',
      modelCount: 12,
      visionModelPresent: null,
    });
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('settings-ai-test')).toBeTruthy());
    await fireEvent.press(getByTestId('settings-ai-test'));

    const shown = await waitFor(() => getByTestId('settings-ai-test-ok'));
    const text = (shown.props.children as unknown[]).join('');
    expect(text).toContain('ai.example.test');
    expect(text).toContain('12');
  });

  it('reports a missing VISION model as a warning, not a refusal', async () => {
    mockTest.mockResolvedValue({
      ok: true,
      endpointHost: 'ai.example.test',
      checkedAt: '2026-09-04T16:41:02.113Z',
      modelCount: 12,
      visionModelPresent: false,
    });
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('settings-ai-test')).toBeTruthy());
    await fireEvent.press(getByTestId('settings-ai-test'));
    const shown = await waitFor(() => getByTestId('settings-ai-test-ok'));
    expect((shown.props.children as unknown[]).join('')).toContain('manual path');
  });

  it('shows a failed test with the slug it answered', async () => {
    mockTest.mockRejectedValue(refusal(502, 'ai-endpoint-unauthorized', 'Key rejected.'));
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });
    await waitFor(() => expect(getByTestId('settings-ai-test')).toBeTruthy());
    await fireEvent.press(getByTestId('settings-ai-test'));
    const shown = await waitFor(() => getByTestId('settings-ai-error'));
    expect(String(shown.props.children)).toContain('rejected the stored API key');
  });

  it('shows when the endpoint was last tested and whether it worked', async () => {
    const { getByTestId } = await render(<AiSettingsCard />, { wrapper: createWrapper() });
    const line = await waitFor(() => getByTestId('settings-ai-last-test'));
    expect((line.props.children as unknown[]).join('')).toContain('it worked');
  });
});
