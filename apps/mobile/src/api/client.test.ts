import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';

/**
 * Tests for the refresh interceptor, driven through a real axios instance.
 *
 * `axios` is deliberately not mocked here (or globally any more): the whole
 * point of this file is the request/response interceptor pipeline, and a
 * mocked `axios.create()` whose `interceptors.use` throws the handler away
 * cannot exercise it. A mock **adapter** is installed on `axios.defaults`
 * instead, so every instance created afterwards — including the private
 * `refreshClient` inside `client.ts` — is served by it.
 *
 * The adapter must be installed before `client.ts` is first loaded, which is
 * why the module is `require`d lazily rather than imported at the top.
 */

interface StubResponse {
  status: number;
  data?: unknown;
}

type Handler = (config: InternalAxiosRequestConfig) => StubResponse;

let handler: Handler = () => ({ status: 200, data: { success: true, data: null } });
const seen: InternalAxiosRequestConfig[] = [];

axios.defaults.adapter = async (config) => {
  seen.push(config as InternalAxiosRequestConfig);
  const stub = handler(config as InternalAxiosRequestConfig);
  const response = {
    data: stub.data ?? null,
    status: stub.status,
    statusText: '',
    headers: {},
    config,
    request: {},
  };
  const accepted =
    typeof config.validateStatus === 'function'
      ? config.validateStatus(stub.status)
      : stub.status >= 200 && stub.status < 300;
  if (accepted) return response;
  throw new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, response);
};

// eslint-disable-next-line @typescript-eslint/consistent-type-imports
let client: typeof import('./client');
// eslint-disable-next-line @typescript-eslint/consistent-type-imports
let serverConfig: typeof import('./serverConfig');

const BASE = 'https://trove.example.com';
const USER = {
  id: 'u1',
  email: 'a@b.co',
  displayName: 'A',
  householdId: 'h1',
  role: 'MEMBER' as const,
  totpEnabled: false,
};

function pathsCalled(): string[] {
  return seen.map((config) => `${config.method?.toUpperCase()} ${config.url}`);
}

beforeAll(() => {
  serverConfig = require('./serverConfig');
  client = require('./client');
});

beforeEach(async () => {
  seen.length = 0;
  client.__resetClientForTests();
  serverConfig.__resetServerConnectionForTests();
  await serverConfig.setServerConnection({ baseUrl: BASE, allowInsecureLan: false, meta: null });
});

describe('request interceptor', () => {
  it('resolves the base URL from the connected server, with the /api context path', async () => {
    handler = () => ({ status: 200, data: { success: true, data: { ok: true } } });
    await client.api.get('/v1/auth/me');
    expect(seen[0].baseURL).toBe(`${BASE}/api`);
    expect(seen[0].url).toBe('/v1/auth/me');
  });

  it('attaches the in-memory access token as a bearer', async () => {
    await client.setSession(
      { accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 900 },
      USER
    );
    handler = () => ({ status: 200, data: { success: true, data: null } });
    await client.api.get('/v1/auth/me');
    expect(seen[0].headers.Authorization).toBe('Bearer access-1');
  });

  it('sends no Authorization header when there is no session', async () => {
    handler = () => ({ status: 200, data: { success: true, data: null } });
    await client.api.get('/v1/status');
    expect(seen[0].headers.Authorization).toBeUndefined();
  });

  // The header selects bearer mode on `/v1/auth/login` and, on every other
  // route, tells the server the request is not cookie-authenticated so the
  // CSRF filters skip it instead of 403-ing a mutating bearer call (§1.5).
  it('declares bearer auth mode on every request, signed in or not', async () => {
    handler = () => ({ status: 200, data: { success: true, data: null } });

    await client.api.post(
      '/v1/auth/login',
      { email: 'a', password: 'b' },
      { skipAuthRefresh: true }
    );
    expect(seen[0].headers[client.AUTH_MODE_HEADER]).toBe('bearer');

    await client.setSession(
      { accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 900 },
      USER
    );
    await client.api.get('/v1/transactions');
    expect(seen[1].headers[client.AUTH_MODE_HEADER]).toBe('bearer');
  });

  // Bearer requests are meant to skip `CsrfRequiresHeaderFilter`, but the one
  // mutating call made before a bearer token exists is `POST /v1/auth/login`,
  // where the server has no `trove.authSource` to switch on. Sending the
  // header anyway — as `apps/web` does — costs nothing and cannot 403.
  it('sends X-Requested-With on every request, anonymous ones included', async () => {
    handler = () => ({ status: 200, data: { success: true, data: null } });

    await client.api.post(
      '/v1/auth/login',
      { email: 'a', password: 'b' },
      { skipAuthRefresh: true }
    );
    expect(seen[0].headers[client.REQUESTED_WITH_HEADER]).toBe('XMLHttpRequest');

    await client.setSession(
      { accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 900 },
      USER
    );
    await client.api.get('/v1/receipts');
    expect(seen[1].headers[client.REQUESTED_WITH_HEADER]).toBe('XMLHttpRequest');
  });

  it('declares bearer auth mode on the refresh call too', async () => {
    await client.setSession(
      { accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 900 },
      USER
    );
    handler = () => ({
      status: 200,
      data: {
        success: true,
        data: { accessToken: 'access-2', refreshToken: 'refresh-2', expiresIn: 900 },
      },
    });

    await client.refreshAccessToken();

    expect(seen[0].url).toBe('/v1/auth/refresh');
    expect(seen[0].headers[client.AUTH_MODE_HEADER]).toBe('bearer');
    // The refresh goes out on a separate axios instance with no interceptors,
    // so its headers are set by hand and are easy to forget.
    expect(seen[0].headers[client.REQUESTED_WITH_HEADER]).toBe('XMLHttpRequest');
  });

  it('refuses to dial when no server is connected', async () => {
    await serverConfig.clearServerConnection();
    await expect(client.api.get('/v1/auth/me')).rejects.toBeInstanceOf(
      client.NoServerConnectedError
    );
  });
});

describe('refresh on 401', () => {
  beforeEach(async () => {
    await client.setSession(
      { accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 900 },
      USER
    );
  });

  it('rotates the pair and retries the original request', async () => {
    let refreshed = false;
    handler = (config) => {
      if (config.url === '/v1/auth/refresh') {
        refreshed = true;
        return {
          status: 200,
          data: {
            success: true,
            data: { accessToken: 'access-2', refreshToken: 'refresh-2', expiresIn: 900 },
          },
        };
      }
      if (!refreshed) return { status: 401 };
      return { status: 200, data: { success: true, data: { userId: 'u1' } } };
    };

    const response = await client.api.get('/v1/auth/me');

    expect(response.data).toEqual({ success: true, data: { userId: 'u1' } });
    expect(pathsCalled()).toEqual(['GET /v1/auth/me', 'POST /v1/auth/refresh', 'GET /v1/auth/me']);
    // The retry carries the *new* access token, not the stale one.
    expect(seen[2].headers.Authorization).toBe('Bearer access-2');
    // And the rotated refresh token replaced the old one, in memory…
    expect(client.getAccessToken()).toBe('access-2');
    expect(client.getRefreshToken()).toBe('refresh-2');
  });

  it('persists the rotated pair atomically, in one SecureStore item', async () => {
    handler = (config) =>
      config.url === '/v1/auth/refresh'
        ? {
            status: 200,
            data: {
              success: true,
              data: { accessToken: 'access-2', refreshToken: 'refresh-2', expiresIn: 900 },
            },
          }
        : { status: 200, data: { success: true, data: null } };

    await client.refreshAccessToken();
    client.__resetClientForTests();

    // …and at rest: a fresh hydrate sees the rotated token, never the old one.
    const hydrated = await client.hydrateSession();
    expect(hydrated?.refreshToken).toBe('refresh-2');
    expect(hydrated?.user.id).toBe('u1');
  });

  it('coalesces concurrent 401s into a single refresh (single-flight)', async () => {
    // Two parallel requests both 401. Without single-flight both would POST
    // /v1/auth/refresh, the second presenting a token the first had already
    // rotated away — which the server reads as reuse and family-revokes,
    // force-logging-out the whole household (§1.5).
    let refreshCalls = 0;
    let refreshed = false;
    handler = (config) => {
      if (config.url === '/v1/auth/refresh') {
        refreshCalls += 1;
        refreshed = true;
        return {
          status: 200,
          data: {
            success: true,
            data: { accessToken: 'access-2', refreshToken: 'refresh-2', expiresIn: 900 },
          },
        };
      }
      if (!refreshed) return { status: 401 };
      return { status: 200, data: { success: true, data: { url: config.url } } };
    };

    const [a, b] = await Promise.all([
      client.api.get('/v1/transactions'),
      client.api.get('/v1/budgets'),
    ]);

    expect(refreshCalls).toBe(1);
    expect(a.status).toBe(200);
    expect(b.status).toBe(200);
  });

  it('does not refresh on an auth route marked skipAuthRefresh', async () => {
    handler = () => ({ status: 401 });
    await expect(
      client.api.post('/v1/auth/login', { email: 'a', password: 'b' }, { skipAuthRefresh: true })
    ).rejects.toBeDefined();
    expect(pathsCalled()).toEqual(['POST /v1/auth/login']);
  });

  it('retries a request at most once', async () => {
    handler = (config) =>
      config.url === '/v1/auth/refresh'
        ? {
            status: 200,
            data: {
              success: true,
              data: { accessToken: 'access-2', refreshToken: 'refresh-2', expiresIn: 900 },
            },
          }
        : { status: 401 };

    const ended: string[] = [];
    client.onSessionEnded((reason) => ended.push(reason));

    await expect(client.api.get('/v1/auth/me')).rejects.toBeDefined();

    expect(pathsCalled()).toEqual(['GET /v1/auth/me', 'POST /v1/auth/refresh', 'GET /v1/auth/me']);
    expect(ended).toEqual(['UNAUTHORIZED']);
  });
});

describe('reuse detection', () => {
  beforeEach(async () => {
    await client.setSession(
      { accessToken: 'access-1', refreshToken: 'stale-token', expiresIn: 900 },
      USER
    );
  });

  it('forces a logout when the refresh itself is rejected', async () => {
    // A rejected refresh means the token was stale or its family was revoked
    // by the server's reuse detection. There is no recovery on the client.
    handler = () => ({ status: 401 });

    const ended: string[] = [];
    client.onSessionEnded((reason) => ended.push(reason));

    await expect(client.api.get('/v1/auth/me')).rejects.toBeDefined();

    expect(ended).toEqual(['REFRESH_REJECTED']);
    expect(client.getAccessToken()).toBeNull();
    expect(client.getRefreshToken()).toBeNull();
    expect(await client.hydrateSession()).toBeNull();
  });

  it('does not retry the original request after a rejected refresh', async () => {
    handler = () => ({ status: 401 });
    await expect(client.api.get('/v1/auth/me')).rejects.toBeDefined();
    expect(pathsCalled()).toEqual(['GET /v1/auth/me', 'POST /v1/auth/refresh']);
  });

  it('ends the session on a 401 when there is no refresh token at all', async () => {
    client.__resetClientForTests();
    const ended: string[] = [];
    client.onSessionEnded((reason) => ended.push(reason));
    handler = () => ({ status: 401 });

    await expect(client.api.get('/v1/auth/me')).rejects.toBeDefined();

    expect(ended).toEqual(['NO_REFRESH_TOKEN']);
    expect(pathsCalled()).toEqual(['GET /v1/auth/me']);
  });
});
