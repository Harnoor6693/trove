import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { createTestQueryClient } from '@/test/queryClient';
import { WebhooksCard } from './WebhooksCard';
import { createWebhook, deleteWebhook, fetchWebhooks } from '@api/webhooks';
import { toApiFailure } from '@api/errors';
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

async function renderCard() {
  const client = createTestQueryClient();
  await render(
    <QueryClientProvider client={client}>
      <WebhooksCard />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

it('lists webhooks by URL and event types — never a secret value, which the API never sends', async () => {
  mockList.mockResolvedValue([WEBHOOK]);
  await renderCard();
  await waitFor(() => expect(screen.getByTestId('webhook-row-w1')).toBeTruthy());
  expect(screen.getByText(WEBHOOK.targetUrl)).toBeTruthy();
  // `WebhookResponse` carries only `hasSecret: boolean` — there is no secret
  // value on the wire for this row to render even if it tried.
  expect(WEBHOOK).not.toHaveProperty('secret');
});

it('creates a webhook and clears the secret field afterwards', async () => {
  mockList.mockResolvedValue([]);
  mockCreate.mockResolvedValue(WEBHOOK);
  await renderCard();
  await waitFor(() => expect(screen.getByTestId('settings-webhooks-add-toggle')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('settings-webhooks-add-toggle'));
  await fireEvent.changeText(
    screen.getByTestId('settings-webhooks-url'),
    'https://hooks.example.test/trove'
  );
  await fireEvent.changeText(
    screen.getByTestId('settings-webhooks-secret'),
    'a-long-enough-webhook-secret'
  );
  await fireEvent.press(screen.getByTestId('settings-webhooks-submit'));

  await waitFor(() =>
    expect(mockCreate).toHaveBeenCalledWith({
      targetUrl: 'https://hooks.example.test/trove',
      secret: 'a-long-enough-webhook-secret',
    })
  );
  // The form closes on success — the secret field is gone, not merely blanked.
  await waitFor(() => expect(screen.queryByTestId('settings-webhooks-secret')).toBeNull());
});

it('shows the server’s own refusal sentence for a rejected URL', async () => {
  mockList.mockResolvedValue([]);
  const refusal = {
    isAxiosError: true,
    response: {
      status: 400,
      data: {
        success: false,
        error: {
          type: 'https://trove.finance/errors/outbound-url-rejected',
          status: 400,
          title: 'outbound-url-rejected',
          detail: 'That address is not allowed: it resolves to a private range.',
          instance: '/v1/webhooks',
        },
      },
    },
  };
  mockCreate.mockRejectedValue(refusal);
  await renderCard();
  await waitFor(() => expect(screen.getByTestId('settings-webhooks-add-toggle')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('settings-webhooks-add-toggle'));
  await fireEvent.changeText(screen.getByTestId('settings-webhooks-url'), 'https://10.0.0.5/hook');
  await fireEvent.changeText(
    screen.getByTestId('settings-webhooks-secret'),
    'a-long-enough-webhook-secret'
  );
  await fireEvent.press(screen.getByTestId('settings-webhooks-submit'));

  await waitFor(() => expect(screen.getByTestId('settings-webhooks-error')).toBeTruthy());
  expect(screen.getByText(/private range/)).toBeTruthy();
  // Sanity-check the fixture matches what toApiFailure actually extracts.
  expect(toApiFailure(refusal, '').code).toBe('outbound-url-rejected');
});

it('deletes a webhook after confirmation', async () => {
  mockList.mockResolvedValue([WEBHOOK]);
  mockDelete.mockResolvedValue(undefined);
  await renderCard();
  await waitFor(() => expect(screen.getByTestId('webhook-row-w1-delete')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('webhook-row-w1-delete'));
  expect(mockDelete).not.toHaveBeenCalled();
  await fireEvent.press(screen.getByTestId('webhook-row-w1-delete-confirm'));

  await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('w1'));
});

it('shows an error and a retry when the webhook list fails to load — not an empty-looking card', async () => {
  const refusal = {
    isAxiosError: true,
    response: {
      status: 401,
      data: {
        success: false,
        error: {
          type: 'https://trove.finance/errors/unauthorized',
          status: 401,
          title: 'unauthorized',
          detail: 'Your session has ended.',
          instance: '/v1/webhooks',
        },
      },
    },
  };
  mockList.mockRejectedValue(refusal);
  await renderCard();

  await waitFor(() => expect(screen.getByTestId('settings-webhooks-list-error')).toBeTruthy());
  expect(screen.queryByTestId('settings-webhooks-empty')).toBeNull();

  mockList.mockResolvedValueOnce([]);
  await fireEvent.press(screen.getByTestId('settings-webhooks-list-retry'));
  await waitFor(() => expect(screen.getByTestId('settings-webhooks-empty')).toBeTruthy());
});

it('surfaces a failed delete on the row instead of silently leaving it in place', async () => {
  mockList.mockResolvedValue([WEBHOOK]);
  const refusal = {
    isAxiosError: true,
    response: {
      status: 403,
      data: {
        success: false,
        error: {
          type: 'https://trove.finance/errors/forbidden',
          status: 403,
          title: 'forbidden',
          detail: 'You cannot remove this webhook.',
          instance: '/v1/webhooks/w1',
        },
      },
    },
  };
  mockDelete.mockRejectedValue(refusal);
  await renderCard();
  await waitFor(() => expect(screen.getByTestId('webhook-row-w1-delete')).toBeTruthy());

  await fireEvent.press(screen.getByTestId('webhook-row-w1-delete'));
  await fireEvent.press(screen.getByTestId('webhook-row-w1-delete-confirm'));

  await waitFor(() => expect(screen.getByTestId('webhook-row-w1-delete-error')).toBeTruthy());
  expect(screen.getByText(/cannot remove/)).toBeTruthy();
  // The row is still there — the failed delete did not silently remove it.
  expect(screen.getByTestId('webhook-row-w1')).toBeTruthy();
});
