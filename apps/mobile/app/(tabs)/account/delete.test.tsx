import React from 'react';
import { AxiosError } from 'axios';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import { router } from 'expo-router';
import { createTestQueryClient } from '@/test/queryClient';
import DeleteAccountScreen from './delete';
import { deleteMyAccount } from '@api/me';
import { getAccessToken, setSession } from '@api/client';
import { StorageKeys } from '@api/secureStorage';
import { useAuthStore } from '@stores/authStore';
import type { AuthUser, MeResponse } from '@api/types';

jest.mock('@api/me', () => ({
  deleteMyAccount: jest.fn(),
  fetchMe: jest.fn(),
  fetchSecurityEvents: jest.fn(),
  updateMe: jest.fn(),
}));

const mockDelete = deleteMyAccount as jest.MockedFunction<typeof deleteMyAccount>;
const mockReplace = router.replace as jest.Mock;

const ME: MeResponse = {
  userId: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada',
  householdId: 'h1',
  role: 'ADMIN',
  totpEnabled: false,
  currency: 'CAD',
  country: 'CA',
  timezone: 'America/Halifax',
  locale: 'en-CA',
  onboardingCompleted: true,
};

const USER: AuthUser = {
  id: 'u1',
  email: 'ada@example.com',
  displayName: 'Ada',
  householdId: 'h1',
  role: 'ADMIN',
  totpEnabled: false,
};

function refused(status: number, slug: string, detail: string) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const config = { headers: {} } as any;
  return new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, {
    data: {
      success: false,
      error: { type: `https://trove.finance/errors/${slug}`, title: slug, status, detail },
    },
    status,
    statusText: '',
    headers: {},
    config,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any);
}

function textOf(testID: string): string {
  const { children } = screen.getByTestId(testID).props as { children: unknown };
  return (Array.isArray(children) ? children : [children]).join('');
}

let client: ReturnType<typeof createTestQueryClient>;

async function renderScreen() {
  client = createTestQueryClient();
  return render(
    <QueryClientProvider client={client}>
      <DeleteAccountScreen />
    </QueryClientProvider>
  );
}

/** Open the step-up form, which sits behind an explicit "I understand" step. */
async function reachStepUp() {
  await fireEvent.press(screen.getByTestId('delete-begin-btn'));
}

beforeEach(async () => {
  jest.clearAllMocks();
  useAuthStore.setState({
    me: ME,
    user: USER,
    server: { baseUrl: 'https://trove.example.com', allowInsecureLan: false, meta: null },
    status: 'authenticated',
  });
  await setSession(
    { accessToken: 'access-abc', refreshToken: 'refresh-xyz', expiresIn: 900 },
    USER
  );
});

afterEach(() => {
  client.clear();
});

describe('what it says before it asks', () => {
  it('states every consequence the operation’s description commits to', async () => {
    await renderScreen();
    expect(textOf('delete-immediate')).toMatch(/every session of it ends/);
    expect(textOf('delete-grace')).toMatch(/seven days/);
    expect(textOf('delete-grace')).toMatch(/erased permanently/);
    expect(textOf('delete-household-keeps')).toMatch(/household keeps its shared ledger/);
    expect(textOf('delete-goes-with-you')).toMatch(/two-factor secret/);
  });

  it('does not invent a countdown or a member-facing restore — restore is the admin’s route', async () => {
    await renderScreen();
    expect(screen.queryByTestId('delete-restore-btn')).toBeNull();
    expect(screen.queryByTestId('delete-countdown')).toBeNull();
  });

  it('keeps the step-up behind an explicit acknowledgement, and makes "keep my account" the default action', async () => {
    await renderScreen();
    expect(screen.queryByTestId('delete-step-up')).toBeNull();
    expect(screen.getByTestId('delete-cancel-btn')).toBeTruthy();
    await reachStepUp();
    expect(screen.getByTestId('delete-step-up')).toBeTruthy();
  });
});

describe('deleting', () => {
  it('uses the POST twin and, on success, clears the session and lands on login', async () => {
    mockDelete.mockResolvedValue(undefined);
    await renderScreen();
    await reachStepUp();

    await fireEvent.changeText(
      screen.getByTestId('delete-step-up-password'),
      'correct-horse-battery'
    );
    await fireEvent.press(screen.getByTestId('delete-step-up-submit'));

    await waitFor(() =>
      expect(mockDelete).toHaveBeenCalledWith({ password: 'correct-horse-battery' })
    );
    // `clearSession()` — the in-memory access token, the stored refresh token
    // and the user all go.
    await waitFor(() => expect(getAccessToken()).toBeNull());
    await waitFor(() =>
      expect((SecureStore.deleteItemAsync as jest.Mock).mock.calls[0][0]).toBe(StorageKeys.session)
    );
    await waitFor(() => expect(useAuthStore.getState().status).toBe('anonymous'));
    // And the identity goes with it: a partial `setState` would merge, leaving
    // the deleted member's role, currency and locale in the store for the next
    // sign-in to render if its own best-effort profile fetch failed.
    const after = useAuthStore.getState();
    expect(after.user).toBeNull();
    expect(after.me).toBeNull();
    expect(after.preferences).toBeNull();
    expect(mockReplace).toHaveBeenCalledWith('/(auth)/login');
  });

  it('renders the last-admin 409 as an explanation with a way forward, not an error', async () => {
    mockDelete.mockRejectedValue(
      refused(409, 'conflict', 'This is the household’s only administrator.')
    );
    await renderScreen();
    await reachStepUp();
    await fireEvent.changeText(
      screen.getByTestId('delete-step-up-password'),
      'correct-horse-battery'
    );
    await fireEvent.press(screen.getByTestId('delete-step-up-submit'));

    await waitFor(() => expect(screen.getByTestId('delete-last-admin')).toBeTruthy());
    expect(textOf('delete-last-admin')).toMatch(/Give another member the admin role first/);
    // Refused means still signed in.
    expect(getAccessToken()).toBe('access-abc');
    expect(mockReplace).not.toHaveBeenCalled();
  });

  it('reads a wrong password as the step-up refusal and keeps the session', async () => {
    mockDelete.mockRejectedValue(
      refused(403, 'step-up-required', 'Step-up authentication required.')
    );
    await renderScreen();
    await reachStepUp();
    await fireEvent.changeText(screen.getByTestId('delete-step-up-password'), 'wrong');
    await fireEvent.press(screen.getByTestId('delete-step-up-submit'));

    await waitFor(() =>
      expect(screen.getByTestId('delete-step-up-error').props.children).toBe(
        'That password or code was not accepted.'
      )
    );
    expect(getAccessToken()).toBe('access-abc');
  });

  it('never writes the credential to the secure store or leaves it in the tree', async () => {
    mockDelete.mockRejectedValue(
      refused(403, 'step-up-required', 'Step-up authentication required.')
    );
    await renderScreen();
    await reachStepUp();
    await fireEvent.changeText(
      screen.getByTestId('delete-step-up-password'),
      'correct-horse-battery'
    );
    await fireEvent.press(screen.getByTestId('delete-step-up-submit'));
    await waitFor(() => expect(mockDelete).toHaveBeenCalled());

    const written = (SecureStore.setItemAsync as jest.Mock).mock.calls;
    expect(JSON.stringify(written)).not.toContain('correct-horse-battery');
    expect(JSON.stringify(screen.toJSON())).not.toContain('correct-horse-battery');
  });
});
