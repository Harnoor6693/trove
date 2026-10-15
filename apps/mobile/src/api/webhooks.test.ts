import { calls, failureOf, replyOk, replyError, reset, seen } from '@/test/httpMock';
import { setServerConnection, __resetServerConnectionForTests } from './serverConfig';
import { __resetClientForTests } from './client';
import { createWebhook, deleteWebhook, fetchWebhooks } from './webhooks';
import type { WebhookResponse } from './types';

const BASE = 'https://trove.example.com';

const WEBHOOK: WebhookResponse = {
  id: 'w1',
  targetUrl: 'https://hooks.example.test/trove',
  enabled: true,
  eventTypes: ['budget.threshold_crossed', 'sync.completed'],
  hasSecret: true,
  createdAt: '2026-09-05T18:00:00Z',
  updatedAt: '2026-09-05T18:00:00Z',
};

beforeEach(async () => {
  reset();
  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('fetchWebhooks', () => {
  it('unwraps the bare array, disabled ones included', async () => {
    replyOk([WEBHOOK, { ...WEBHOOK, id: 'w2', enabled: false }]);
    const result = await fetchWebhooks();
    expect(result).toHaveLength(2);
    expect(calls()).toEqual(['GET /v1/webhooks']);
  });
});

describe('createWebhook', () => {
  it('posts targetUrl/secret/eventTypes and the response never carries the secret', async () => {
    replyOk(WEBHOOK);
    const result = await createWebhook({
      targetUrl: 'https://hooks.example.test/trove',
      secret: 'a-long-enough-webhook-secret',
      eventTypes: ['budget.threshold_crossed', 'sync.completed'],
    });
    expect(result).toEqual(WEBHOOK);
    expect(result).not.toHaveProperty('secret');
    expect(calls()).toEqual(['POST /v1/webhooks']);
  });

  it('surfaces a rejected URL as 400 outbound-url-rejected, in the member’s own words', async () => {
    replyError(
      400,
      'outbound-url-rejected',
      'That address is not allowed: it resolves to a private range.'
    );
    const failure = await failureOf(
      createWebhook({ targetUrl: 'https://10.0.0.5/hook', secret: 'a-long-enough-webhook-secret' })
    );
    expect(failure.status).toBe(400);
    expect(failure.code).toBe('outbound-url-rejected');
    expect(failure.message).toMatch(/private range/);
  });

  it('surfaces the twenty-first subscription as 409 conflict', async () => {
    replyError(409, 'conflict', 'You already hold the maximum number of webhook subscriptions.');
    const failure = await failureOf(
      createWebhook({
        targetUrl: 'https://hooks.example.test/trove',
        secret: 'a-long-enough-webhook-secret',
      })
    );
    expect(failure.status).toBe(409);
  });
});

describe('deleteWebhook', () => {
  it('deletes and expects EmptySuccess', async () => {
    replyOk(undefined);
    await expect(deleteWebhook('w1')).resolves.toBeUndefined();
    expect(calls()).toEqual(['DELETE /v1/webhooks/w1']);
  });

  it('encodes a path-hostile id rather than letting it change the route', async () => {
    replyOk(undefined);
    await deleteWebhook('../notifications/x');
    expect(seen[0].url).toBe('/v1/webhooks/..%2Fnotifications%2Fx');
  });
});
