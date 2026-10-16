import * as SecureStore from 'expo-secure-store';
import { StorageKeys } from '@api/secureStorage';
import {
  __resetClientForTests,
  clearSession,
  getRefreshToken,
  refreshAccessToken,
  setSession,
} from '@api/client';
import { __resetServerConnectionForTests, getServerConnection } from '@api/serverConfig';
import { useAuthStore } from './authStore';
import { queryClient } from '@api/queryClient';
import * as authApi from '@api/auth';

jest.mock('@api/auth', () => ({
  logout: jest.fn(() => Promise.resolve()),
}));

const mockLogout = authApi.logout as jest.MockedFunction<typeof authApi.logout>;

const SERVER = { baseUrl: 'https://trove.example.com', allowInsecureLan: false, meta: null };
const USER = {
  id: 'u1',
  email: 'a@b.co',
  displayName: 'A',
  householdId: 'h1',
  role: 'MEMBER' as const,
  totpEnabled: false,
};
const PAIR = { accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 900 };

async function wipeStorage() {
  for (const key of Object.values(StorageKeys)) {
    await SecureStore.deleteItemAsync(key);
  }
}

function resetStore() {
  useAuthStore.setState({
    status: 'disconnected',
    hydrated: false,
    server: null,
    user: null,
    me: null,
    preferences: null,
  });
}

beforeEach(async () => {
  jest.clearAllMocks();
  await wipeStorage();
  __resetClientForTests();
  __resetServerConnectionForTests();
  resetStore();
});

describe('hydrate', () => {
  it('lands on disconnected when no server has ever been paired', async () => {
    await useAuthStore.getState().hydrate();
    expect(useAuthStore.getState().status).toBe('disconnected');
    expect(useAuthStore.getState().hydrated).toBe(true);
  });

  it('lands on anonymous when a server is paired but no session is stored', async () => {
    await useAuthStore.getState().connectServer(SERVER);
    resetStore();
    __resetServerConnectionForTests();
    __resetClientForTests();

    await useAuthStore.getState().hydrate();

    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(useAuthStore.getState().server?.baseUrl).toBe(SERVER.baseUrl);
  });

  it('lands on locked — never straight to authenticated — when a session is stored', async () => {
    // A stored session is restored locked so the token exchange and the
    // biometric prompt both happen in one place, on every cold start.
    await useAuthStore.getState().connectServer(SERVER);
    await setSession(PAIR, USER);
    resetStore();
    __resetServerConnectionForTests();
    __resetClientForTests();

    await useAuthStore.getState().hydrate();

    expect(useAuthStore.getState().status).toBe('locked');
    expect(useAuthStore.getState().user?.id).toBe('u1');
  });

  it('sets `hydrated` before anything routes, so no screen flashes', async () => {
    expect(useAuthStore.getState().hydrated).toBe(false);
    await useAuthStore.getState().hydrate();
    expect(useAuthStore.getState().hydrated).toBe(true);
  });
});

describe('connectServer', () => {
  it('moves disconnected → anonymous and persists the connection', async () => {
    await useAuthStore.getState().connectServer(SERVER);
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(getServerConnection()?.baseUrl).toBe(SERVER.baseUrl);
  });

  it('drops any existing session — a session belongs to one server', async () => {
    await setSession(PAIR, USER);
    await useAuthStore
      .getState()
      .connectServer({ ...SERVER, baseUrl: 'https://other.example.com' });
    expect(getRefreshToken()).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });
});

describe('sign in and out', () => {
  beforeEach(async () => {
    await useAuthStore.getState().connectServer(SERVER);
  });

  it('moves anonymous → authenticated', () => {
    useAuthStore.getState().onAuthenticated(USER);
    expect(useAuthStore.getState().status).toBe('authenticated');
    expect(useAuthStore.getState().user).toEqual(USER);
  });

  it('revokes the refresh token server-side on sign out (§4.1)', async () => {
    await setSession(PAIR, USER);
    useAuthStore.getState().onAuthenticated(USER);

    await useAuthStore.getState().signOut();

    expect(mockLogout).toHaveBeenCalledWith('refresh-1');
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(getRefreshToken()).toBeNull();
  });

  it('still signs out locally when the revoke call fails', async () => {
    // A user who taps Sign out must end up signed out even with no
    // connectivity; the server-side family expires on its own.
    mockLogout.mockRejectedValueOnce(new Error('offline'));
    await setSession(PAIR, USER);
    useAuthStore.getState().onAuthenticated(USER);

    await useAuthStore.getState().signOut();

    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(getRefreshToken()).toBeNull();
  });

  it('clears the profile as well as the tokens', async () => {
    await setSession(PAIR, USER);
    useAuthStore.getState().onAuthenticated(USER);
    useAuthStore.getState().setMe({
      userId: 'u1',
      email: 'a@b.co',
      displayName: 'A',
      householdId: 'h1',
      role: 'MEMBER',
      totpEnabled: false,
      currency: 'CAD',
      country: 'CA',
      timezone: 'America/Toronto',
      locale: 'en-CA',
      onboardingCompleted: true,
    });

    await useAuthStore.getState().signOut();

    expect(useAuthStore.getState().me).toBeNull();
    expect(useAuthStore.getState().preferences).toBeNull();
  });
});

describe('lock and unlock', () => {
  beforeEach(async () => {
    await useAuthStore.getState().connectServer(SERVER);
    await setSession(PAIR, USER);
    useAuthStore.getState().onAuthenticated(USER);
  });

  it('moves authenticated → locked', () => {
    useAuthStore.getState().lock();
    expect(useAuthStore.getState().status).toBe('locked');
  });

  it('does not lock a state that has no session to lock', async () => {
    await useAuthStore.getState().signOut();
    useAuthStore.getState().lock();
    expect(useAuthStore.getState().status).toBe('anonymous');
  });

  describe('unlock result', () => {
    // These drive the real refresh path with a stubbed fetch, so the result
    // reflects what the client actually did to the session.
    let fetchSpy: { mockRestore: () => void } | null = null;
    afterEach(() => {
      fetchSpy?.mockRestore();
      fetchSpy = null;
    });

    beforeEach(() => {
      useAuthStore.getState().lock();
    });

    it("is 'ok' and authenticates when the refresh succeeds", async () => {
      fetchSpy = jest.spyOn(global, 'fetch').mockResolvedValue(
        new Response(JSON.stringify({ success: true, data: PAIR }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      );
      await expect(useAuthStore.getState().unlock()).resolves.toBe('ok');
      expect(useAuthStore.getState().status).toBe('authenticated');
    });

    it("is 'ended' when the server refuses the token, and the store is anonymous", async () => {
      fetchSpy = jest.spyOn(global, 'fetch').mockResolvedValue(new Response(null, { status: 401 }));
      await expect(useAuthStore.getState().unlock()).resolves.toBe('ended');
      expect(useAuthStore.getState().status).toBe('anonymous');
    });

    it("is 'retryable' on a 5xx, keeping the session and the lock", async () => {
      fetchSpy = jest.spyOn(global, 'fetch').mockResolvedValue(new Response(null, { status: 503 }));
      await expect(useAuthStore.getState().unlock()).resolves.toBe('retryable');
      expect(useAuthStore.getState().status).toBe('locked');
      expect(getRefreshToken()).not.toBeNull();
    });

    it("is 'retryable' on a network error", async () => {
      fetchSpy = jest
        .spyOn(global, 'fetch')
        .mockRejectedValue(new TypeError('Network request failed'));
      await expect(useAuthStore.getState().unlock()).resolves.toBe('retryable');
      expect(useAuthStore.getState().status).toBe('locked');
    });

    it("is 'no-token' when no refresh token is stored, without calling the server", async () => {
      const spy = jest.spyOn(global, 'fetch');
      fetchSpy = spy;
      await clearSession();
      await expect(useAuthStore.getState().unlock()).resolves.toBe('no-token');
      expect(spy).not.toHaveBeenCalled();
      expect(useAuthStore.getState().status).toBe('locked');
    });
  });
});

describe('disconnectServer', () => {
  it('moves any state → disconnected and forgets the server', async () => {
    await useAuthStore.getState().connectServer(SERVER);
    await setSession(PAIR, USER);
    useAuthStore.getState().onAuthenticated(USER);

    await useAuthStore.getState().disconnectServer();

    expect(useAuthStore.getState().status).toBe('disconnected');
    expect(useAuthStore.getState().server).toBeNull();
    expect(getServerConnection()).toBeNull();
    expect(mockLogout).toHaveBeenCalled();
  });
});

let afterEachRestore: () => void = () => undefined;

describe('forced logout from the client', () => {
  afterEach(() => afterEachRestore());

  it('follows a rejected refresh down to anonymous, keeping the server', async () => {
    // `client.ts` clears the session when the server rejects a refresh (reuse
    // detection, §1.5). The store subscribes to that at import time, so it
    // must not be left rendering a signed-in shell over an empty token store.
    // This drives the real path: a live refresh call that 401s.
    // `client.ts` built its axios instances at import, before any adapter could
    // be swapped in, so the stub sits one layer down, on the fetch they use.
    const fetchSpy = jest
      .spyOn(global, 'fetch')
      .mockResolvedValue(new Response(null, { status: 401 }));
    afterEachRestore = () => fetchSpy.mockRestore();

    await useAuthStore.getState().connectServer(SERVER);
    await setSession(PAIR, USER);
    useAuthStore.getState().onAuthenticated(USER);
    expect(useAuthStore.getState().status).toBe('authenticated');

    await expect(refreshAccessToken()).rejects.toBeDefined();

    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(useAuthStore.getState().user).toBeNull();
    // The server pairing survives — the user is signed out, not disconnected.
    expect(useAuthStore.getState().server?.baseUrl).toBe(SERVER.baseUrl);
  });
});

describe('query cache (B5-02)', () => {
  beforeEach(async () => {
    await wipeStorage();
    __resetClientForTests();
    __resetServerConnectionForTests();
    queryClient.setQueryData(['accounts'], [{ id: 'a1' }]);
  });

  afterEach(() => queryClient.clear());

  it('is emptied on sign-out', async () => {
    useAuthStore.setState({ status: 'authenticated', server: SERVER, user: USER });
    await useAuthStore.getState().signOut();
    expect(queryClient.getQueryData(['accounts'])).toBeUndefined();
  });

  it('is emptied when the client ends the session', async () => {
    useAuthStore.setState({ status: 'authenticated', server: SERVER, user: USER });
    useAuthStore.setState({ status: 'anonymous', user: null });
    expect(queryClient.getQueryData(['accounts'])).toBeUndefined();
  });

  it('is emptied on disconnect and on pairing with another server', async () => {
    useAuthStore.setState({ status: 'authenticated', server: SERVER, user: USER });
    await useAuthStore.getState().disconnectServer();
    expect(queryClient.getQueryData(['accounts'])).toBeUndefined();

    queryClient.setQueryData(['accounts'], [{ id: 'a1' }]);
    await useAuthStore
      .getState()
      .connectServer({ ...SERVER, baseUrl: 'https://other.example.com' });
    expect(queryClient.getQueryData(['accounts'])).toBeUndefined();
  });

  it('survives a lock and unlock', () => {
    useAuthStore.setState({ status: 'authenticated', server: SERVER, user: USER });
    queryClient.setQueryData(['accounts'], [{ id: 'a1' }]);
    useAuthStore.getState().lock();
    useAuthStore.setState({ status: 'authenticated' });
    expect(queryClient.getQueryData(['accounts'])).toBeDefined();
  });
});
