import React from 'react';
import { act, fireEvent, render } from '@testing-library/react-native';
import { AxiosError } from 'axios';
import { router, useLocalSearchParams } from 'expo-router';
import TotpScreen from './totp';
import { loginWithTotp } from '@api/auth';
import { fetchMe } from '@api/me';
import { fetchPreferences } from '@api/preferences';
import { useAuthStore } from '@stores/authStore';
import type { TotpLoginRequest } from '@api/types';

/**
 * Second factor — `POST /v1/auth/login/totp` (step 215, family E).
 *
 * **The conclusion this suite pins down.** `TotpLoginRequest.challengeToken`
 * is *optional* in the contract, and reading only the schema it is tempting to
 * let the screen submit without one. It is optional because the **web** flow
 * can carry the challenge in an HttpOnly cookie the SPA never sees — the
 * field's own description says the token "wins over the cookie when both are
 * present", which only means anything on a transport that has a cookie.
 *
 * Mobile is bearer mode. There is no cookie jar, so on this transport the
 * token in the login response body is the only carrier there is, and a submit
 * without one could only ever be a 401 the member would read as "my code is
 * wrong" when their sign-in attempt had in fact expired. So the screen keeps
 * refusing locally and says the attempt expired — and this suite asserts the
 * refusal rather than leaving it as a comment somebody deletes.
 */

jest.mock('@api/auth', () => ({ loginWithTotp: jest.fn() }));
jest.mock('@api/me', () => ({ fetchMe: jest.fn() }));
jest.mock('@api/preferences', () => ({ fetchPreferences: jest.fn() }));

const mockLoginWithTotp = loginWithTotp as jest.MockedFunction<typeof loginWithTotp>;
const mockMe = fetchMe as jest.MockedFunction<typeof fetchMe>;
const mockPreferences = fetchPreferences as jest.MockedFunction<typeof fetchPreferences>;
const mockParams = useLocalSearchParams as unknown as jest.Mock;
const mockReplace = router.replace as jest.Mock;

const USER = {
  id: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada',
  householdId: 'h1',
  role: 'MEMBER' as const,
  totpEnabled: true,
};
const SESSION = { accessToken: 'a', refreshToken: 'r', expiresIn: 900, user: USER };
const ME = {
  userId: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada',
  householdId: 'h1',
  role: 'MEMBER' as const,
  totpEnabled: true,
  currency: 'CAD' as const,
  country: 'CA' as const,
  timezone: 'America/Toronto',
  locale: 'en-CA' as const,
  onboardingCompleted: true,
};
const PREFERENCES = {
  region: 'CA',
  locale: 'en-CA' as const,
  timezone: 'America/Toronto',
  onboardingCompleted: true,
};

function unauthorized(): AxiosError {
  return new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', undefined, {}, {
    status: 401,
    data: { success: false, error: { status: 401, detail: 'Unauthorized' } },
    statusText: '',
    headers: {},
    config: {} as never,
  } as never);
}

function apiError(status: number, slug: string, detail: string): AxiosError {
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, {}, {
    status,
    data: {
      success: false,
      error: { type: `https://trove.finance/errors/${slug}`, status, detail },
    },
    statusText: '',
    headers: {},
    config: {} as never,
  } as never);
}

beforeEach(() => {
  jest.clearAllMocks();
  mockParams.mockReturnValue({ challengeToken: 'CHALLENGE-JWT' });
  mockLoginWithTotp.mockResolvedValue(SESSION);
  mockMe.mockResolvedValue(ME);
  mockPreferences.mockResolvedValue(PREFERENCES);
  useAuthStore.setState({
    status: 'anonymous',
    hydrated: true,
    server: { baseUrl: 'https://trove.example.com', allowInsecureLan: false, meta: null },
    user: null,
    me: null,
    preferences: null,
  });
});

async function submit(getByTestId: (id: string) => never, code = '123456') {
  fireEvent.changeText(getByTestId('totp-code-input'), code);
  await act(async () => {
    fireEvent.press(getByTestId('totp-submit-btn'));
  });
}

describe('TotpScreen', () => {
  it('submits the challenge token from the navigation params with the code', async () => {
    const { getByTestId } = render(<TotpScreen />);
    await submit(getByTestId as never);

    const body: TotpLoginRequest = { challengeToken: 'CHALLENGE-JWT', code: '123456' };
    expect(mockLoginWithTotp).toHaveBeenCalledWith(body);
  });

  // Bearer mode has no cookie jar, so the body token is the only carrier and
  // a submit without one could only be a 401 the member would misread as a
  // bad code. The refusal is local and names the real cause.
  it('refuses locally when the challenge token is absent, rather than sending a 401 round trip', async () => {
    mockParams.mockReturnValue({});
    const { getByTestId } = render(<TotpScreen />);
    await submit(getByTestId as never);

    expect(mockLoginWithTotp).not.toHaveBeenCalled();
    expect(getByTestId('totp-error-text').props.children).toMatch(/expired/i);
  });

  it('treats a non-string param the same as an absent one', async () => {
    mockParams.mockReturnValue({ challengeToken: ['a', 'b'] });
    const { getByTestId } = render(<TotpScreen />);
    await submit(getByTestId as never);

    expect(mockLoginWithTotp).not.toHaveBeenCalled();
  });

  it('signs in, loads the profile, and lands on the tabs', async () => {
    const { getByTestId } = render(<TotpScreen />);
    await submit(getByTestId as never);

    expect(useAuthStore.getState().status).toBe('authenticated');
    expect(mockMe).toHaveBeenCalled();
    expect(mockPreferences).toHaveBeenCalled();
    expect(mockReplace).toHaveBeenCalledWith('/(tabs)/');
  });

  // The field takes a 6-digit code *or* one of the ten single-use recovery
  // codes, so it must not be digit-constrained.
  it('accepts a recovery code, not only six digits', async () => {
    const { getByTestId } = render(<TotpScreen />);
    await submit(getByTestId as never, 'ABCD-EFGH');

    expect(mockLoginWithTotp).toHaveBeenCalledWith(expect.objectContaining({ code: 'ABCD-EFGH' }));
  });

  it('trims what the member typed before sending it', async () => {
    const { getByTestId } = render(<TotpScreen />);
    await submit(getByTestId as never, '  123456  ');

    expect(mockLoginWithTotp).toHaveBeenCalledWith(expect.objectContaining({ code: '123456' }));
  });

  it('names the 30-second window when the server refuses the code', async () => {
    mockLoginWithTotp.mockRejectedValue(unauthorized());
    const { getByTestId } = render(<TotpScreen />);
    await submit(getByTestId as never);

    expect(getByTestId('totp-error-text').props.children).toMatch(/30 seconds/);
    expect(useAuthStore.getState().status).toBe('anonymous');
  });

  it('shows the server’s own detail for a slug it has no special copy for', async () => {
    mockLoginWithTotp.mockRejectedValue(
      apiError(400, 'validation-error', 'Code must not be blank.')
    );
    const { getByTestId } = render(<TotpScreen />);
    await submit(getByTestId as never);

    expect(getByTestId('totp-error-text').props.children).toBe('Code must not be blank.');
  });

  // The profile load is best-effort: the session is already real, so a failed
  // `me` must not strand a signed-in member on the second-factor screen.
  it('still lands on the tabs when the profile load fails', async () => {
    mockMe.mockRejectedValue(new Error('offline'));
    const { getByTestId } = render(<TotpScreen />);
    await submit(getByTestId as never);

    expect(mockReplace).toHaveBeenCalledWith('/(tabs)/');
  });

  // The challenge is a 60-second JWT the server rejects on every other route,
  // and it is a navigation param — it must not be written anywhere that
  // outlives the attempt.
  it('never puts the challenge token into the session store', async () => {
    const { getByTestId } = render(<TotpScreen />);
    await submit(getByTestId as never);

    expect(JSON.stringify(useAuthStore.getState())).not.toContain('CHALLENGE-JWT');
  });

  it('offers a way back to sign in', () => {
    const { getByTestId } = render(<TotpScreen />);
    fireEvent.press(getByTestId('totp-cancel-link'));
    expect(mockReplace).toHaveBeenCalledWith('/(auth)/login');
  });

  // Autofill: iOS and Android both hand a one-time code straight to a field
  // that declares it, which is the difference between typing six digits and
  // tapping once.
  it('declares the one-time-code autofill hint on the input', () => {
    const { getByTestId } = render(<TotpScreen />);
    expect(getByTestId('totp-code-input').props.autoComplete).toBe('one-time-code');
    expect(getByTestId('totp-code-input').props.accessibilityLabel).toBe('Two-factor code');
  });
});
