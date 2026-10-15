import React from 'react';
import { act, fireEvent, render } from '@testing-library/react-native';
import { AxiosError } from 'axios';
import { router, useLocalSearchParams } from 'expo-router';
import ResetPasswordScreen from './reset-password';
import { redeemPasswordResetLink } from '@api/auth';
import type { PasswordResetRequest } from '@api/types';

jest.mock('@api/auth', () => ({ redeemPasswordResetLink: jest.fn() }));

const mockRedeem = redeemPasswordResetLink as jest.MockedFunction<typeof redeemPasswordResetLink>;
const mockParams = useLocalSearchParams as unknown as jest.Mock;
const mockReplace = router.replace as jest.Mock;

/** An `ApiResponse` error envelope with the problem-details `type` slug set. */
function apiError(
  status: number,
  slug: string,
  detail: string,
  extras: Record<string, unknown> = {},
  headers: Record<string, string> = {}
): AxiosError {
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, {}, {
    status,
    data: {
      success: false,
      error: { type: `https://trove.finance/errors/${slug}`, status, detail, ...extras },
    },
    statusText: '',
    headers,
    config: {} as never,
  } as never);
}

beforeEach(() => {
  jest.clearAllMocks();
  mockParams.mockReturnValue({});
  mockRedeem.mockResolvedValue(undefined);
});

async function renderScreen() {
  const utils = render(<ResetPasswordScreen />);
  await act(async () => {});
  return utils;
}

async function fill(
  getByTestId: (id: string) => never,
  { link, password, confirmation }: { link?: string; password: string; confirmation?: string }
) {
  if (link !== undefined) fireEvent.changeText(getByTestId('reset-password-link-input'), link);
  fireEvent.changeText(getByTestId('reset-password-input'), password);
  fireEvent.changeText(getByTestId('reset-password-confirm-input'), confirmation ?? password);
  await act(async () => {
    fireEvent.press(getByTestId('reset-password-submit-btn'));
  });
}

describe('ResetPasswordScreen', () => {
  // `trove://reset-password?token=…` — expo-router maps the app scheme onto
  // this route's search params, so the deep link needs no linking code here.
  it('seeds the field from a deep link and redeems that token', async () => {
    mockParams.mockReturnValue({ token: 'DEEP-LINK-TOKEN' });
    const { getByTestId } = await renderScreen();

    expect(getByTestId('reset-password-link-input').props.value).toBe('DEEP-LINK-TOKEN');

    await fill(getByTestId as never, { password: 'correct horse battery' });

    const body: PasswordResetRequest = {
      token: 'DEEP-LINK-TOKEN',
      password: 'correct horse battery',
    };
    expect(mockRedeem).toHaveBeenCalledWith(body);
  });

  // Step 27.7: a second link opened while this screen is already showing (the
  // admin minted a fresh one) kept the stale token in the field.
  it('takes the token from a newer link opened while the screen is showing', async () => {
    mockParams.mockReturnValue({ token: 'FIRST-LINK' });
    const { getByTestId, rerender } = await renderScreen();
    mockParams.mockReturnValue({ token: 'NEWER-LINK' });
    rerender(<ResetPasswordScreen />);

    expect(getByTestId('reset-password-link-input').props.value).toBe('NEWER-LINK');
  });

  it('redeems a pasted link and confirms every session is gone', async () => {
    const { getByTestId, queryByTestId } = await renderScreen();
    await fill(getByTestId as never, {
      link: 'https://trove.example.test/reset-password/PASTED',
      password: 'correct horse battery',
    });

    expect(mockRedeem).toHaveBeenCalledWith({
      token: 'PASTED',
      password: 'correct horse battery',
    });
    expect(queryByTestId('reset-password-done')).not.toBeNull();
    // Every session of the account was revoked server-side, so there is
    // nothing to sign in with — the screen says so rather than pretending.
    expect(getByTestId('reset-password-done')).toBeTruthy();
  });

  it('sends the member to sign in again afterwards', async () => {
    const { getByTestId } = await renderScreen();
    await fill(getByTestId as never, { link: 'PASTED', password: 'correct horse battery' });
    fireEvent.press(getByTestId('reset-password-signin-btn'));
    expect(mockReplace).toHaveBeenCalledWith('/(auth)/login');
  });

  // Unknown, expired and already-spent tokens are the identical 400 on
  // purpose, so the route cannot be used to test guesses. The copy must not
  // distinguish them either, however much more helpful it would read.
  it('gives one undifferentiated message for reset-invalid', async () => {
    mockRedeem.mockRejectedValue(apiError(400, 'reset-invalid', 'Reset token is not valid.'));
    const { getByTestId, queryByTestId } = await renderScreen();
    await fill(getByTestId as never, { link: 'SPENT', password: 'correct horse battery' });

    const message = getByTestId('reset-password-error-text').props.children as string;
    expect(message).toContain('can’t be used');
    expect(message).not.toMatch(/expired|unknown|already used/i);
    expect(queryByTestId('reset-password-done')).toBeNull();
  });

  // The policy is checked *before* the token is consumed, so the link is
  // still usable and the member must not be sent back to their admin.
  it('says the link still works when the server refuses the password', async () => {
    mockRedeem.mockRejectedValue(apiError(400, 'password-policy', 'Password too weak.'));
    const { getByTestId } = await renderScreen();
    await fill(getByTestId as never, { link: 'GOOD', password: 'correct horse battery' });

    expect(getByTestId('reset-password-field-error').props.children as string).toContain(
      'Your link still works'
    );
  });

  it('renders per-field validation messages from the list shape the server sends', async () => {
    mockRedeem.mockRejectedValue(
      apiError(400, 'validation-error', 'Validation failed.', {
        fieldErrors: [{ field: 'password', message: 'Password must be at most 128 characters.' }],
      })
    );
    const { getByTestId } = await renderScreen();
    await fill(getByTestId as never, { link: 'GOOD', password: 'correct horse battery' });

    expect(getByTestId('reset-password-field-error').props.children).toBe(
      'Password must be at most 128 characters.'
    );
  });

  // A 429 is a wait-and-retry state with a real number on it, not a generic
  // failure toast — and never a reason to end a session.
  it('reads Retry-After off a 429 instead of showing a generic failure', async () => {
    mockRedeem.mockRejectedValue(
      apiError(429, 'rate-limited', 'Too many requests.', {}, { 'retry-after': '42' })
    );
    const { getByTestId } = await renderScreen();
    await fill(getByTestId as never, { link: 'GOOD', password: 'correct horse battery' });

    expect(getByTestId('reset-password-error-text').props.children as string).toContain(
      '42 seconds'
    );
  });

  it('refuses to spend a round trip on a password shorter than the policy', async () => {
    const { getByTestId } = await renderScreen();
    await fill(getByTestId as never, { link: 'GOOD', password: 'short' });

    expect(mockRedeem).not.toHaveBeenCalled();
    expect(getByTestId('reset-password-field-error').props.children as string).toContain(
      'at least 12 characters'
    );
  });

  it('refuses when the two passwords differ', async () => {
    const { getByTestId } = await renderScreen();
    await fill(getByTestId as never, {
      link: 'GOOD',
      password: 'correct horse battery',
      confirmation: 'correct horse batteries',
    });

    expect(mockRedeem).not.toHaveBeenCalled();
    expect(getByTestId('reset-password-field-error').props.children as string).toContain(
      'don’t match'
    );
  });

  it('asks for the link rather than posting an empty token', async () => {
    const { getByTestId } = await renderScreen();
    await fill(getByTestId as never, { password: 'correct horse battery' });

    expect(mockRedeem).not.toHaveBeenCalled();
    expect(getByTestId('reset-password-error-text').props.children as string).toContain(
      'Paste the reset link'
    );
  });

  // The new password has exactly one use and is not kept for a courtesy
  // sign-in: the account's sessions are already gone.
  it('drops the password out of component state once the call settles', async () => {
    const { getByTestId, queryByTestId } = await renderScreen();
    await fill(getByTestId as never, { link: 'GOOD', password: 'correct horse battery' });

    expect(queryByTestId('reset-password-input')).toBeNull();
    expect(queryByTestId('reset-password-confirm-input')).toBeNull();
  });

  it('offers a way back to sign in without redeeming anything', async () => {
    const { getByTestId } = await renderScreen();
    fireEvent.press(getByTestId('reset-password-cancel-link'));
    expect(mockReplace).toHaveBeenCalledWith('/(auth)/login');
    expect(mockRedeem).not.toHaveBeenCalled();
  });
});
