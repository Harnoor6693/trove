import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { AxiosError } from 'axios';
import { router } from 'expo-router';
import LoginScreen from './login';
import { login } from '@api/auth';
import { fetchAuthProviders } from '@api/meta';
import { fetchMe } from '@api/me';
import { fetchPreferences } from '@api/preferences';
import { useAuthStore } from '@stores/authStore';

jest.mock('@api/auth', () => ({ login: jest.fn() }));
jest.mock('@api/meta', () => ({ fetchAuthProviders: jest.fn() }));
jest.mock('@api/me', () => ({ fetchMe: jest.fn() }));
jest.mock('@api/preferences', () => ({ fetchPreferences: jest.fn() }));

const mockLogin = login as jest.MockedFunction<typeof login>;
const mockProviders = fetchAuthProviders as jest.MockedFunction<typeof fetchAuthProviders>;
const mockMe = fetchMe as jest.MockedFunction<typeof fetchMe>;
const mockPreferences = fetchPreferences as jest.MockedFunction<typeof fetchPreferences>;
const mockReplace = router.replace as jest.Mock;
const mockPush = router.push as jest.Mock;

const USER = {
  id: 'u1',
  email: 'a@b.co',
  displayName: 'A',
  householdId: 'h1',
  role: 'MEMBER' as const,
  totpEnabled: false,
};
const ME = {
  userId: 'u1',
  email: 'a@b.co',
  displayName: 'A',
  householdId: 'h1',
  role: 'MEMBER' as const,
  totpEnabled: false,
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

beforeEach(() => {
  jest.clearAllMocks();
  useAuthStore.setState({
    status: 'anonymous',
    hydrated: true,
    server: { baseUrl: 'https://trove.example.com', allowInsecureLan: false, meta: null },
    user: null,
    me: null,
    preferences: null,
  });
  mockProviders.mockResolvedValue({
    local: true,
    oidc: { enabled: false, startUrl: null, displayName: null },
    registrationOpen: false,
  });
  mockMe.mockResolvedValue(ME);
  mockPreferences.mockResolvedValue(PREFERENCES);
});

/**
 * `fetchAuthProviders()` resolves in a microtask after mount, so every render
 * has to flush it inside `act()` — otherwise the state update lands outside
 * and React logs "not wrapped in act(...)" over the whole run.
 */
async function renderLogin() {
  const utils = await render(<LoginScreen />);
  await act(async () => {});
  return utils;
}

async function submit(getByTestId: (id: string) => never) {
  await fireEvent.changeText(getByTestId('login-email-input'), '  a@b.co  ');
  await fireEvent.changeText(getByTestId('login-password-input'), 'correct horse battery');
  // The submit handler is async and setStates after it settles; pressing
  // inside `act` keeps those updates wrapped.
  await act(async () => {
    await fireEvent.press(getByTestId('login-submit-btn'));
  });
}

/** An `ApiResponse` error envelope with the problem-details `type` slug set. */
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

describe('LoginScreen', () => {
  it('signs in, loads the profile, and lands on the tabs', async () => {
    mockLogin.mockResolvedValue({
      accessToken: 'a',
      refreshToken: 'r',
      expiresIn: 900,
      user: USER,
    });

    const { getByTestId } = await renderLogin();
    await submit(getByTestId as never);

    await waitFor(() =>
      expect(mockLogin).toHaveBeenCalledWith({
        email: 'a@b.co',
        password: 'correct horse battery',
      })
    );
    await waitFor(() => expect(useAuthStore.getState().status).toBe('authenticated'));
    // Currency, locale and timezone drive every amount and date, so they load
    // before the first screen paints.
    await waitFor(() => expect(useAuthStore.getState().me).toEqual(ME));
    await waitFor(() => expect(useAuthStore.getState().preferences).toEqual(PREFERENCES));
    expect(mockReplace).toHaveBeenCalledWith('/(tabs)/');
  });

  it('routes to the second factor when the server returns a challenge instead of a session', async () => {
    mockLogin.mockResolvedValue({ challengeToken: 'challenge-1' });

    const { getByTestId } = await renderLogin();
    await submit(getByTestId as never);

    await waitFor(() =>
      expect(mockPush).toHaveBeenCalledWith({
        pathname: '/(auth)/totp',
        params: { challengeToken: 'challenge-1' },
      })
    );
    // No session yet — the challenge token is not an access token.
    expect(useAuthStore.getState().status).toBe('anonymous');
  });

  it('shows one enumeration-safe message for bad credentials', async () => {
    mockLogin.mockRejectedValue(unauthorized());

    const { getByTestId } = await renderLogin();
    await submit(getByTestId as never);

    await waitFor(() =>
      expect(getByTestId('login-error-text').props.children).toBe('Invalid email or password.')
    );
    expect(useAuthStore.getState().status).toBe('anonymous');
  });

  // `account-locked` is the 429 rate-limit path only (§4.1 slugs): it says
  // nothing about whether the account exists, so it can be named. A 401
  // lockout stays behind the enumeration-safe message above.
  it('names the rate limit when the server sends the account-locked slug', async () => {
    mockLogin.mockRejectedValue(
      apiError(429, 'account-locked', 'Too many requests from this address.')
    );

    const { getByTestId } = await renderLogin();
    await submit(getByTestId as never);

    await waitFor(() =>
      expect(getByTestId('login-error-text').props.children).toBe(
        'Too many sign-in attempts. Wait a few minutes and try again.'
      )
    );
  });

  it('shows the server’s own detail for a slug it has no special copy for', async () => {
    mockLogin.mockRejectedValue(apiError(503, 'service-unavailable', 'Trove is restarting.'));

    const { getByTestId } = await renderLogin();
    await submit(getByTestId as never);

    await waitFor(() =>
      expect(getByTestId('login-error-text').props.children).toBe('Trove is restarting.')
    );
  });

  it('reports a transport failure differently from a rejected password', async () => {
    mockLogin.mockRejectedValue(new AxiosError('Network Error'));

    const { getByTestId } = await renderLogin();
    await submit(getByTestId as never);

    await waitFor(() =>
      expect(getByTestId('login-error-text').props.children).toMatch(/Could not reach/)
    );
  });

  it('hides the sign-up link and explains why when registration is closed', async () => {
    const { getByTestId, queryByTestId } = await renderLogin();
    await waitFor(() => expect(getByTestId('registration-closed-note')).toBeTruthy());
    expect(queryByTestId('create-account-link')).toBeNull();
  });

  it('offers sign-up when the server advertises open registration', async () => {
    mockProviders.mockResolvedValue({
      local: true,
      oidc: { enabled: false, startUrl: null, displayName: null },
      registrationOpen: true,
    });

    const { getByTestId, queryByTestId } = await renderLogin();
    await waitFor(() => expect(getByTestId('create-account-link')).toBeTruthy());
    expect(queryByTestId('registration-closed-note')).toBeNull();
  });

  it('offers a way back to the connect screen', async () => {
    const { getByTestId } = await renderLogin();
    await waitFor(() => expect(getByTestId('change-server-link')).toBeTruthy());
    await fireEvent.press(getByTestId('change-server-link'));
    expect(mockReplace).toHaveBeenCalledWith('/(auth)/connect');
  });

  // Step 215, family E.
  describe('single sign-on', () => {
    function withOidc(displayName: string | null) {
      mockProviders.mockResolvedValue({
        local: true,
        oidc: {
          enabled: true,
          startUrl: 'https://trove.example.test/api/v1/auth/start',
          displayName,
        },
        registrationOpen: false,
      });
    }

    it('says nothing about sign-on when the server has no provider', async () => {
      const { queryByTestId } = await renderLogin();
      await waitFor(() => expect(queryByTestId('registration-closed-note')).toBeTruthy());
      expect(queryByTestId('login-oidc-note')).toBeNull();
    });

    it('names the provider the server advertises', async () => {
      withOidc('Example SSO');
      const { getByTestId } = await renderLogin();
      await waitFor(() =>
        expect(getByTestId('login-oidc-note').props.children).toContain('Example SSO')
      );
    });

    it('falls back to a generic name when the provider has none', async () => {
      withOidc(null);
      const { getByTestId } = await renderLogin();
      await waitFor(() =>
        expect(getByTestId('login-oidc-note').props.children).toContain('your identity provider')
      );
    });

    // `startOidc` is a 302 into a browser flow that ends in cookies on the
    // server's own origin. Completing it needs a system-browser session and a
    // redirect back into the app — `expo-auth-session` plus
    // `expo-web-browser`, neither of which is a dependency, and this step may
    // not add one. A button that opened the URL would strand the member in
    // the OS browser holding a session the app can never see, so there is
    // deliberately no button and no `Linking.openURL` anywhere on this screen.
    it('offers no button that would open the provider and strand the member', async () => {
      withOidc('Example SSO');
      const { queryByTestId, getByTestId } = await renderLogin();
      await waitFor(() => expect(getByTestId('login-oidc-note')).toBeTruthy());
      expect(queryByTestId('login-oidc-btn')).toBeNull();
      const copy = (getByTestId('login-oidc-note').props.children as string[]).join('');
      expect(copy).toContain('web browser');
    });
  });

  it('points at the reset-password screen for an admin-minted link', async () => {
    const { getByTestId } = await renderLogin();
    await waitFor(() => expect(getByTestId('reset-password-link')).toBeTruthy());
    await fireEvent.press(getByTestId('reset-password-link'));
    expect(mockPush).toHaveBeenCalledWith('/(auth)/reset-password');
  });
});
