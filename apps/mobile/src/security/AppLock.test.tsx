import React from 'react';
import { AppState, Text, type AppStateStatus } from 'react-native';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import * as LocalAuthentication from 'expo-local-authentication';
import { AppLock } from './AppLock';
import { withLockSuppressed } from './lockSuppression';
import { fetchMe } from '@api/me';
import { fetchPreferences } from '@api/preferences';
import { __resetClientForTests, refreshAccessToken, setSession } from '@api/client';
import { __resetServerConnectionForTests, setServerConnection } from '@api/serverConfig';
import { useAuthStore } from '@stores/authStore';
import { useUiStore } from '@stores/uiStore';

jest.mock('@api/me', () => ({ fetchMe: jest.fn() }));
jest.mock('@api/preferences', () => ({ fetchPreferences: jest.fn() }));

// The rotation mechanics themselves are covered by `src/api/client.test.ts`;
// here only its outcome matters, so the one call is stubbed and the rest of
// the module — session storage included — stays real.
jest.mock('@api/client', () => ({
  ...(jest.requireActual('@api/client') as Record<string, unknown>),
  refreshAccessToken: jest.fn(),
}));

const mockFetchMe = fetchMe as jest.MockedFunction<typeof fetchMe>;
const mockFetchPreferences = fetchPreferences as jest.MockedFunction<typeof fetchPreferences>;
const mockRefresh = refreshAccessToken as jest.MockedFunction<typeof refreshAccessToken>;
const mockAuthenticate = LocalAuthentication.authenticateAsync as jest.Mock;

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

async function emitAppState(next: AppStateStatus) {
  const calls = (AppState.addEventListener as jest.Mock).mock.calls;
  const handler = calls[calls.length - 1][1] as (state: AppStateStatus) => void;
  await act(() => handler(next));
}

function Child() {
  return <Text testID="protected-content">ledger</Text>;
}

beforeEach(async () => {
  jest.clearAllMocks();
  jest.spyOn(AppState, 'addEventListener').mockReturnValue({ remove: jest.fn() } as never);
  mockAuthenticate.mockResolvedValue({ success: true });
  mockFetchMe.mockResolvedValue(ME);
  mockFetchPreferences.mockResolvedValue(PREFERENCES);
  mockRefresh.mockResolvedValue('access-2');

  __resetClientForTests();
  __resetServerConnectionForTests();
  await setServerConnection({
    baseUrl: 'https://trove.example.com',
    allowInsecureLan: false,
    meta: null,
  });
  await setSession({ accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 900 }, USER);

  useAuthStore.setState({
    status: 'authenticated',
    hydrated: true,
    server: { baseUrl: 'https://trove.example.com', allowInsecureLan: false, meta: null },
    user: USER,
    me: null,
    preferences: null,
  });
  useUiStore.setState({
    theme: 'system',
    appLockEnabled: true,
    appLockTimeoutMinutes: 5,
    hydrated: true,
  });
});

describe('locked state', () => {
  beforeEach(() => {
    useAuthStore.setState({ status: 'locked' });
  });

  it('hides the app behind the unlock screen', async () => {
    const { getByTestId, queryByTestId } = await render(
      <AppLock>
        <Child />
      </AppLock>
    );
    expect(getByTestId('app-lock-screen')).toBeTruthy();
    expect(queryByTestId('protected-content')).toBeNull();
  });

  it('unlocking prompts for biometrics, refreshes the session, and re-reads /v1/auth/me', async () => {
    // Biometrics prove the human is present; they say nothing about whether
    // the session is still valid server-side, so the unlock re-validates.
    const { getByTestId } = await render(
      <AppLock>
        <Child />
      </AppLock>
    );

    await fireEvent.press(getByTestId('app-lock-unlock-btn'));

    await waitFor(() => expect(mockAuthenticate).toHaveBeenCalled());
    await waitFor(() => expect(useAuthStore.getState().status).toBe('authenticated'));
    await waitFor(() => expect(mockFetchMe).toHaveBeenCalled());
    await waitFor(() => expect(useAuthStore.getState().preferences).toEqual(PREFERENCES));
    expect(getByTestId('protected-content')).toBeTruthy();
  });

  it('stays locked when the biometric prompt fails', async () => {
    mockAuthenticate.mockResolvedValue({ success: false, error: 'user_cancel' });

    const { getByTestId, queryByTestId } = await render(
      <AppLock>
        <Child />
      </AppLock>
    );
    await fireEvent.press(getByTestId('app-lock-unlock-btn'));

    await waitFor(() => expect(getByTestId('app-lock-error')).toBeTruthy());
    expect(useAuthStore.getState().status).toBe('locked');
    expect(queryByTestId('protected-content')).toBeNull();
  });

  it('asks for the device passcode when no biometrics are enrolled (B5-04)', async () => {
    (LocalAuthentication.isEnrolledAsync as jest.Mock).mockResolvedValue(false);
    const { getByTestId } = await render(
      <AppLock>
        <Child />
      </AppLock>
    );
    await fireEvent.press(getByTestId('app-lock-unlock-btn'));

    await waitFor(() => expect(getByTestId('protected-content')).toBeTruthy());
    expect(mockAuthenticate).toHaveBeenCalledWith(
      expect.objectContaining({ disableDeviceFallback: false })
    );
  });

  it('stays locked, and says why, when the device has no passcode at all (B5-04)', async () => {
    (LocalAuthentication.isEnrolledAsync as jest.Mock).mockResolvedValue(false);
    mockAuthenticate.mockResolvedValue({ success: false, error: 'not_enrolled' });
    const { getByTestId, queryByTestId } = await render(
      <AppLock>
        <Child />
      </AppLock>
    );
    await fireEvent.press(getByTestId('app-lock-unlock-btn'));

    await waitFor(() => expect(getByTestId('app-lock-error')).toBeTruthy());
    expect(getByTestId('app-lock-error').props.children).toMatch(/passcode/i);
    expect(queryByTestId('protected-content')).toBeNull();
    expect(mockRefresh).not.toHaveBeenCalled();
  });

  it('reports an expired session when the server rejects the refresh', async () => {
    mockRefresh.mockRejectedValue(new Error('refresh rejected'));

    const { getByTestId } = await render(
      <AppLock>
        <Child />
      </AppLock>
    );
    await fireEvent.press(getByTestId('app-lock-unlock-btn'));

    await waitFor(() => expect(getByTestId('app-lock-error').props.children).toMatch(/expired/i));
  });

  it('auto-unlocks without a prompt when the app lock is switched off', async () => {
    useUiStore.setState({ appLockEnabled: false });

    const { getByTestId } = await render(
      <AppLock>
        <Child />
      </AppLock>
    );

    await waitFor(() => expect(useAuthStore.getState().status).toBe('authenticated'));
    expect(mockAuthenticate).not.toHaveBeenCalled();
    expect(getByTestId('protected-content')).toBeTruthy();
  });
});

describe('background timeout', () => {
  it('locks after longer than the configured timeout in the background', async () => {
    useUiStore.setState({ appLockTimeoutMinutes: 5 });
    await render(
      <AppLock>
        <Child />
      </AppLock>
    );

    const realNow = Date.now;
    let clock = 1_000_000;
    Date.now = () => clock;
    try {
      await emitAppState('background');
      clock += 6 * 60_000;
      await emitAppState('active');
    } finally {
      Date.now = realNow;
    }

    await waitFor(() => expect(useAuthStore.getState().status).toBe('locked'));
  });

  it('does not lock for a brief switch away', async () => {
    useUiStore.setState({ appLockTimeoutMinutes: 5 });
    await render(
      <AppLock>
        <Child />
      </AppLock>
    );

    const realNow = Date.now;
    let clock = 1_000_000;
    Date.now = () => clock;
    try {
      await emitAppState('background');
      clock += 30_000;
      await emitAppState('active');
    } finally {
      Date.now = realNow;
    }

    expect(useAuthStore.getState().status).toBe('authenticated');
  });

  it('locks immediately when the timeout is 0', async () => {
    useUiStore.setState({ appLockTimeoutMinutes: 0 });
    await render(
      <AppLock>
        <Child />
      </AppLock>
    );

    await emitAppState('background');
    await emitAppState('active');

    await waitFor(() => expect(useAuthStore.getState().status).toBe('locked'));
  });

  it('does not lock when the system camera or picker sent the app away (B5-12)', async () => {
    useUiStore.setState({ appLockTimeoutMinutes: 0 });
    const { getByTestId } = await render(
      <AppLock>
        <Child />
      </AppLock>
    );

    await withLockSuppressed(async () => {
      await emitAppState('background');
    });
    await emitAppState('active');

    expect(useAuthStore.getState().status).toBe('authenticated');
    expect(getByTestId('protected-content')).toBeTruthy();
  });

  it('never locks when the app lock is off', async () => {
    useUiStore.setState({ appLockEnabled: false });
    await render(
      <AppLock>
        <Child />
      </AppLock>
    );

    const realNow = Date.now;
    let clock = 1_000_000;
    Date.now = () => clock;
    try {
      await emitAppState('background');
      clock += 60 * 60_000;
      await emitAppState('active');
    } finally {
      Date.now = realNow;
    }

    expect(useAuthStore.getState().status).toBe('authenticated');
  });
});

describe('privacy overlay', () => {
  // The ledger must not appear in the OS app switcher or in a screenshot
  // taken from it (readiness gap P4-20).
  it('covers the app while it is inactive or backgrounded', async () => {
    const { queryByTestId } = await render(
      <AppLock>
        <Child />
      </AppLock>
    );
    expect(queryByTestId('app-lock-privacy-overlay')).toBeNull();

    await emitAppState('inactive');
    expect(queryByTestId('app-lock-privacy-overlay')).toBeTruthy();

    await emitAppState('active');
    expect(queryByTestId('app-lock-privacy-overlay')).toBeNull();
  });
});
