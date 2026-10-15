import React from 'react';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/queryClient';
import {
  aiKeys,
  coachReadiness,
  useAiModels,
  useAiSettings,
  useDeleteAiApiKey,
  useTestAiConnection,
  useUpdateAiSettings,
} from './useAiSettings';
import {
  deleteAiApiKey,
  fetchAiModels,
  fetchAiSettings,
  testAiConnection,
  updateAiSettings,
} from '@api/ai';
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

const READY: AiSettingsResponse = {
  enabled: true,
  endpointUrl: 'https://ai.example.test/v1',
  hasApiKey: true,
  model: 'qwen2.5:14b',
  visionModel: null,
  allowPrivateEndpoint: false,
  privacyTier: 'LOCAL_ONLY',
  connectTimeoutMs: 5000,
  readTimeoutMs: 120000,
  lastTestAt: null,
  lastTestOk: null,
  updatedAt: '2026-09-04T16:40:55.802Z',
};

const DEFAULTS: AiSettingsResponse = {
  ...READY,
  enabled: false,
  endpointUrl: null,
  hasApiKey: false,
  model: null,
  privacyTier: 'CLOUD_OK',
  updatedAt: null,
};

let clients: ReturnType<typeof createTestQueryClient>[] = [];

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = createTestQueryClient();
  clients.push(qc);
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  jest.clearAllMocks();
});
afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('coachReadiness', () => {
  it('is null while the settings have not loaded — "unknown" is not "broken"', () => {
    expect(coachReadiness(undefined)).toBeNull();
  });

  it('is null when everything the coach needs is set', () => {
    expect(coachReadiness(READY)).toBeNull();
  });

  it('is `disabled` with AI off, or with no endpoint saved', () => {
    expect(coachReadiness(DEFAULTS)).toBe('disabled');
    expect(coachReadiness({ ...READY, enabled: false })).toBe('disabled');
    expect(coachReadiness({ ...READY, endpointUrl: null })).toBe('disabled');
    expect(coachReadiness({ ...READY, endpointUrl: '' })).toBe('disabled');
  });

  it('is `no-model` when an endpoint is saved but no chat model is', () => {
    expect(coachReadiness({ ...READY, model: null })).toBe('no-model');
    expect(coachReadiness({ ...READY, model: '' })).toBe('no-model');
  });

  it('is `privacy-tier` for CLOUD_OK, which the coach is refused at before any connection opens', () => {
    expect(coachReadiness({ ...READY, privacyTier: 'CLOUD_OK' })).toBe('privacy-tier');
  });
});

describe('useAiSettings', () => {
  it('reads the row, which never carries a key', async () => {
    mockFetch.mockResolvedValue(READY);
    const { result } = renderHook(() => useAiSettings(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual(READY));
    expect(Object.keys(result.current.data ?? {})).not.toContain('apiKey');
  });
});

describe('useAiModels', () => {
  it('is idle until the caller says an endpoint is saved', () => {
    const { result } = renderHook(() => useAiModels(false), { wrapper });
    expect(result.current.fetchStatus).toBe('idle');
    expect(mockModels).not.toHaveBeenCalled();
  });

  it('lists the models, so the picker offers real ids rather than a free-text box', async () => {
    mockModels.mockResolvedValue([{ id: 'qwen2.5:14b', ownedBy: 'library', created: null }]);
    const { result } = renderHook(() => useAiModels(true), { wrapper });
    await waitFor(() => expect(result.current.data).toHaveLength(1));
    expect(result.current.data?.[0].id).toBe('qwen2.5:14b');
  });
});

describe('useUpdateAiSettings', () => {
  it('writes the response into the cache and never caches a key', async () => {
    mockUpdate.mockResolvedValue(READY);
    const qc = createTestQueryClient({ gcTime: 5_000 });
    clients.push(qc);
    const { result } = renderHook(() => useUpdateAiSettings(), {
      wrapper: ({ children }: { children: React.ReactNode }) => (
        <QueryClientProvider client={qc}>{children}</QueryClientProvider>
      ),
    });

    await act(async () => {
      await result.current.mutateAsync({
        enabled: true,
        allowPrivateEndpoint: false,
        privacyTier: 'LOCAL_ONLY',
        apiKey: 'sk-never-cache-me',
      });
    });

    expect(qc.getQueryData(aiKeys.settings())).toEqual(READY);
    // The key went up in the request and came back in nothing. Every entry in
    // the cache is checked, not just the settings one.
    const dumped = JSON.stringify(
      qc
        .getQueryCache()
        .getAll()
        .map((q) => q.state.data)
    );
    expect(dumped).not.toContain('sk-never-cache-me');
  });

  it('invalidates the model list — a new endpoint serves different models', async () => {
    mockUpdate.mockResolvedValue(READY);
    const qc = createTestQueryClient();
    clients.push(qc);
    const invalidate = jest.spyOn(qc, 'invalidateQueries');
    const { result } = renderHook(() => useUpdateAiSettings(), {
      wrapper: ({ children }: { children: React.ReactNode }) => (
        <QueryClientProvider client={qc}>{children}</QueryClientProvider>
      ),
    });
    await act(async () => {
      await result.current.mutateAsync({
        enabled: true,
        allowPrivateEndpoint: false,
        privacyTier: 'LOCAL_ONLY',
      });
    });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: aiKeys.models() });
  });
});

describe('useDeleteAiApiKey', () => {
  it('calls the dedicated route and refreshes the settings row', async () => {
    mockDeleteKey.mockResolvedValue(undefined);
    const qc = createTestQueryClient();
    clients.push(qc);
    const invalidate = jest.spyOn(qc, 'invalidateQueries');
    const { result } = renderHook(() => useDeleteAiApiKey(), {
      wrapper: ({ children }: { children: React.ReactNode }) => (
        <QueryClientProvider client={qc}>{children}</QueryClientProvider>
      ),
    });
    await act(async () => {
      await result.current.mutateAsync();
    });
    expect(mockDeleteKey).toHaveBeenCalledTimes(1);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: aiKeys.settings() });
    // The key is removed through this route, never by saving a blank value.
    expect(mockUpdate).not.toHaveBeenCalled();
  });
});

describe('useTestAiConnection', () => {
  it('refreshes the settings afterwards, because the test writes lastTestAt', async () => {
    mockTest.mockResolvedValue({
      ok: true,
      endpointHost: 'ai.example.test',
      checkedAt: '2026-09-04T16:41:02.113Z',
      modelCount: 12,
      visionModelPresent: null,
    });
    const qc = createTestQueryClient();
    clients.push(qc);
    const invalidate = jest.spyOn(qc, 'invalidateQueries');
    const { result } = renderHook(() => useTestAiConnection(), {
      wrapper: ({ children }: { children: React.ReactNode }) => (
        <QueryClientProvider client={qc}>{children}</QueryClientProvider>
      ),
    });
    await act(async () => {
      await result.current.mutateAsync();
    });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: aiKeys.settings() });
  });

  it('refreshes the settings even when the test failed — lastTestOk is written either way', async () => {
    mockTest.mockRejectedValue(new Error('ai-unreachable'));
    const qc = createTestQueryClient();
    clients.push(qc);
    const invalidate = jest.spyOn(qc, 'invalidateQueries');
    const { result } = renderHook(() => useTestAiConnection(), {
      wrapper: ({ children }: { children: React.ReactNode }) => (
        <QueryClientProvider client={qc}>{children}</QueryClientProvider>
      ),
    });
    await act(async () => {
      await result.current.mutateAsync().catch(() => undefined);
    });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: aiKeys.settings() });
  });
});
