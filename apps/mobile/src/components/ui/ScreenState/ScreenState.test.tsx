import React from 'react';
import { Text } from 'react-native';
import { AxiosError } from 'axios';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { ScreenState, failureMessage } from './ScreenState';
import { NoServerConnectedError } from '@api/client';
import { toApiFailure } from '@api/errors';

function axiosFailure(
  status: number,
  body: unknown,
  headers: Record<string, string> = {}
): AxiosError {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const config = { headers: {} } as any;
  return new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, {
    data: body,
    status,
    statusText: '',
    headers,
    config,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any);
}

describe('failureMessage', () => {
  it('names the setup step when no server is connected at all', () => {
    const error = new NoServerConnectedError();
    expect(failureMessage(error, toApiFailure(error, 'fallback'))).toContain('No Trove server');
  });

  it('turns a 429 into a wait-and-retry state with the real number of seconds', () => {
    const error = axiosFailure(
      429,
      { success: false, error: { status: 429 } },
      {
        'retry-after': '30',
      }
    );
    expect(failureMessage(error, toApiFailure(error, 'fallback'))).toBe(
      'Too many requests. Try again in 30 seconds.'
    );
  });

  it('still says wait when a proxy stripped Retry-After', () => {
    const error = axiosFailure(429, { success: false, error: { status: 429 } });
    expect(failureMessage(error, toApiFailure(error, 'fallback'))).toBe(
      'Too many requests. Wait a moment and try again.'
    );
  });

  it('otherwise shows the server’s own detail', () => {
    const error = axiosFailure(404, {
      success: false,
      error: {
        type: 'https://trove.finance/errors/not-found',
        title: 'not-found',
        status: 404,
        detail: 'No such account.',
      },
    });
    expect(failureMessage(error, toApiFailure(error, 'fallback'))).toBe('No such account.');
  });
});

describe('ScreenState', () => {
  it('renders children on the happy path', async () => {
    await render(
      <ScreenState isLoading={false} testID="s">
        <Text>Loaded content</Text>
      </ScreenState>
    );
    expect(screen.getByText('Loaded content')).toBeTruthy();
  });

  it('announces loading rather than showing a bare spinner', async () => {
    await render(<ScreenState isLoading testID="s" />);
    expect(screen.getByTestId('s-loading')).toBeTruthy();
    expect(screen.getByLabelText('Loading')).toBeTruthy();
  });

  it('shows the empty message when the request succeeded with nothing in it', async () => {
    await render(<ScreenState isLoading={false} isEmpty emptyMessage="No rows." testID="s" />);
    expect(screen.getByTestId('s-empty')).toBeTruthy();
    expect(screen.getByText('No rows.')).toBeTruthy();
  });

  it('shows a failure as an alert, and error wins over loading', async () => {
    const error = axiosFailure(500, { success: false, error: { status: 500, detail: 'Boom.' } });
    await render(<ScreenState isLoading error={error} testID="s" />);
    expect(screen.queryByTestId('s-loading')).toBeNull();
    expect(screen.getByTestId('s-error-message')).toBeTruthy();
    expect(screen.getByText('Boom.')).toBeTruthy();
  });

  it('offers a retry that calls back', async () => {
    const onRetry = jest.fn();
    const error = axiosFailure(500, { success: false, error: { status: 500, detail: 'Boom.' } });
    await render(<ScreenState isLoading={false} error={error} onRetry={onRetry} testID="s" />);
    await fireEvent.press(screen.getByTestId('s-retry'));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('omits the retry control when the caller offered no retry', async () => {
    const error = axiosFailure(500, { success: false, error: { status: 500, detail: 'Boom.' } });
    await render(<ScreenState isLoading={false} error={error} testID="s" />);
    expect(screen.queryByTestId('s-retry')).toBeNull();
  });

  it('falls back to the caller’s message when the body carries none', async () => {
    const error = axiosFailure(500, null);
    await render(
      <ScreenState isLoading={false} error={error} fallbackMessage="Nope." testID="s" />
    );
    expect(screen.getByText('Nope.')).toBeTruthy();
  });
});
