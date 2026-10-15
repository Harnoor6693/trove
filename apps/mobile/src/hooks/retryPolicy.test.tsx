import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react-native';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { createTestQueryClient } from '@/test/queryClient';
import { MAX_QUERY_RETRIES, retryUnlessRefused } from './retryPolicy';
import { useDashboardToday } from './useDashboardToday';
import { fetchDashboardToday } from '@api/dashboard';
import { NoServerConnectedError } from '@api/client';

jest.mock('@api/dashboard', () => ({ fetchDashboardToday: jest.fn() }));

const mockToday = fetchDashboardToday as jest.MockedFunction<typeof fetchDashboardToday>;

/** A real `AxiosError`, so the predicate reads the status the way `toApiFailure` does. */
function refusal(status: number, slug: string, headers: Record<string, string> = {}): AxiosError {
  const config = { headers: {} } as InternalAxiosRequestConfig;
  const response = {
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
        detail: 'Refused.',
      },
    },
  } as unknown as AxiosResponse;
  return new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, response);
}

describe('retryUnlessRefused', () => {
  it('never re-issues a 429 — the limiter is chain-wide and sent a Retry-After', () => {
    expect(retryUnlessRefused(0, refusal(429, 'rate-limited', { 'retry-after': '30' }))).toBe(
      false
    );
  });

  it('never re-issues a 401 — client.ts has already run the single-flight refresh', () => {
    expect(retryUnlessRefused(0, refusal(401, 'unauthenticated'))).toBe(false);
  });

  it('never re-issues the other deliberate refusals', () => {
    for (const [status, slug] of [
      [400, 'validation-error'],
      [403, 'csrf-required'],
      [404, 'not-found'],
      [409, 'conflict'],
      [413, 'upload-too-large'],
    ] as Array<[number, string]>) {
      expect(retryUnlessRefused(0, refusal(status, slug))).toBe(false);
    }
  });

  it('still retries a transport failure and a 5xx, twice, the way the app default did', () => {
    expect(retryUnlessRefused(0, new Error('network down'))).toBe(true);
    expect(retryUnlessRefused(MAX_QUERY_RETRIES - 1, refusal(500, 'internal-error'))).toBe(true);
    expect(retryUnlessRefused(MAX_QUERY_RETRIES, refusal(500, 'internal-error'))).toBe(false);
  });

  it('retries when no server is configured at all, which is a state this app can be in', () => {
    expect(retryUnlessRefused(0, new NoServerConnectedError())).toBe(true);
  });
});

describe('the shipped client, whose queries default to retry: 2', () => {
  afterEach(() => {
    jest.clearAllMocks();
  });

  it('asks exactly once for a 429 instead of spending two more against the limiter', async () => {
    mockToday.mockRejectedValue(refusal(429, 'rate-limited', { 'retry-after': '30' }));
    // `retry: 2` is what `app/_layout.tsx` sets; the hook's own predicate has
    // to win over it, which is the whole point of this test.
    const client = createTestQueryClient({ retry: 2 });
    const { result } = await renderHook(() => useDashboardToday(), {
      wrapper: ({ children }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      ),
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(mockToday).toHaveBeenCalledTimes(1);
    client.clear();
  });
});
