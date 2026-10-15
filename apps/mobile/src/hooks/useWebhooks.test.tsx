import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import { useCreateWebhook, useDeleteWebhook, useWebhooks, webhookKeys } from './useWebhooks';
import { createWebhook, deleteWebhook, fetchWebhooks } from '@api/webhooks';
import type { WebhookResponse } from '@api/types';

jest.mock('@api/webhooks', () => ({
  fetchWebhooks: jest.fn(),
  createWebhook: jest.fn(),
  deleteWebhook: jest.fn(),
}));

const mockList = fetchWebhooks as jest.MockedFunction<typeof fetchWebhooks>;
const mockCreate = createWebhook as jest.MockedFunction<typeof createWebhook>;
const mockDelete = deleteWebhook as jest.MockedFunction<typeof deleteWebhook>;

const WEBHOOK: WebhookResponse = {
  id: 'w1',
  targetUrl: 'https://hooks.example.test/trove',
  enabled: true,
  eventTypes: [],
  hasSecret: true,
  createdAt: '2026-09-05T18:00:00Z',
  updatedAt: '2026-09-05T18:00:00Z',
};

let clients: ReturnType<typeof createTestQueryClient>[] = [];

function wrapper({ children }: { children: React.ReactNode }) {
  const qc = createTestQueryClient();
  clients.push(qc);
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

function withClient() {
  const qc = createTestQueryClient();
  clients.push(qc);
  const invalidate = jest.spyOn(qc, 'invalidateQueries');
  const provider = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  return { qc, invalidate, provider };
}

beforeEach(() => {
  jest.clearAllMocks();
});

afterEach(() => {
  clients.forEach((c) => c.clear());
  clients = [];
});

describe('webhookKeys', () => {
  it('one root, one list', () => {
    expect(webhookKeys.list()).toEqual(['webhooks', 'list']);
  });
});

describe('useWebhooks', () => {
  it('returns the bare array, disabled ones included', async () => {
    mockList.mockResolvedValue([WEBHOOK]);
    const { result } = await renderHook(() => useWebhooks(), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([WEBHOOK]));
  });
});

describe('useCreateWebhook', () => {
  it('creates and invalidates the list; the returned row never carries a secret', async () => {
    mockCreate.mockResolvedValue(WEBHOOK);
    const { invalidate, provider } = withClient();
    const { result } = await renderHook(() => useCreateWebhook(), { wrapper: provider });
    let returned: WebhookResponse | undefined;
    await act(async () => {
      returned = await result.current.mutateAsync({
        targetUrl: 'https://hooks.example.test/trove',
        secret: 'a-long-enough-webhook-secret',
      });
    });
    expect(returned).not.toHaveProperty('secret');
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual([
      'webhooks',
      'list',
    ]);
  });
});

describe('useDeleteWebhook', () => {
  it('invalidates the list', async () => {
    mockDelete.mockResolvedValue(undefined);
    const { invalidate, provider } = withClient();
    const { result } = await renderHook(() => useDeleteWebhook(), { wrapper: provider });
    await act(async () => {
      await result.current.mutateAsync('w1');
    });
    expect(invalidate.mock.calls.map(([arg]) => arg?.queryKey)).toContainEqual([
      'webhooks',
      'list',
    ]);
  });
});
