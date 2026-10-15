import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { probeServer } from './meta';

/**
 * The connect-time probe: is anything there, is it Trove, and can this build
 * speak to it (readiness gap P4-19). A mock adapter stands in for the network
 * so the failure branches are reachable.
 */

interface StubResponse {
  status: number;
  data?: unknown;
}

let handler: (config: InternalAxiosRequestConfig) => StubResponse | Error = () => ({
  status: 200,
});
const seen: InternalAxiosRequestConfig[] = [];

axios.defaults.adapter = async (config) => {
  seen.push(config as InternalAxiosRequestConfig);
  const stub = handler(config as InternalAxiosRequestConfig);
  if (stub instanceof Error) throw stub;
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

beforeEach(() => {
  seen.length = 0;
});

describe('probeServer', () => {
  it('probes the unauthenticated GET /api/v1/status the plan chose (§4.2)', async () => {
    handler = () => ({
      status: 200,
      data: { success: true, data: { status: 'UP', version: '1.0.0' } },
    });
    await probeServer('https://trove.example.com');
    expect(seen[0].url).toBe('https://trove.example.com/api/v1/status');
  });

  it('accepts an enveloped body', async () => {
    handler = () => ({
      status: 200,
      data: { success: true, data: { status: 'UP', version: '1.2.0' } },
    });
    const result = await probeServer('https://trove.example.com');
    expect(result).toEqual({ ok: true, meta: { status: 'UP', version: '1.2.0' } });
  });

  it('accepts a bare body', async () => {
    handler = () => ({ status: 200, data: { status: 'DEGRADED', version: '1.2.0' } });
    const result = await probeServer('https://trove.example.com');
    expect(result.ok).toBe(true);
  });

  it('rejects a server that answers with something that is not Trove', async () => {
    handler = () => ({ status: 200, data: { hello: 'world' } });
    const result = await probeServer('https://not-trove.example.com');
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.code).toBe('NOT_TROVE');
      expect(result.message).toMatch(/isn’t a Trove server/);
    }
  });

  it('rejects an HTML 404 page from an unrelated web server', async () => {
    handler = () => ({ status: 404, data: '<!doctype html><h1>Not Found</h1>' });
    const result = await probeServer('https://blog.example.com');
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe('NOT_TROVE');
  });

  it('rejects a server whose version this build cannot speak', async () => {
    handler = () => ({
      status: 200,
      data: { success: true, data: { status: 'UP', version: '9.0.0' } },
    });
    const result = await probeServer('https://trove.example.com');
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.code).toBe('INCOMPATIBLE');
      expect(result.message).toMatch(/9\.0\.0/);
    }
  });

  // `core`'s HealthController answers `"unknown"` when Spring's BuildProperties
  // is absent, which is every build that skipped the `build-info` goal. A
  // freshly stood-up server must still be pairable.
  it('pairs with a server that cannot name its own version', async () => {
    handler = () => ({ status: 200, data: { status: 'UP', version: 'unknown' } });
    const result = await probeServer('https://trove.example.com');
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.meta.version).toBe('unknown');
  });

  it('rejects a server that demands a newer app than this build', async () => {
    handler = () => ({
      status: 200,
      data: {
        success: true,
        data: { status: 'UP', version: '1.0.0', minMobileVersion: '99.0.0' },
      },
    });
    const result = await probeServer('https://trove.example.com');
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe('INCOMPATIBLE');
  });

  it('reports an unreachable host distinctly', async () => {
    handler = () => new Error('getaddrinfo ENOTFOUND trove.example.com');
    const result = await probeServer('https://trove.example.com');
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.code).toBe('UNREACHABLE');
  });

  it('reports an untrusted certificate distinctly, and points at ACME rather than an override', async () => {
    handler = () => new Error('unable to verify the first certificate');
    const result = await probeServer('https://trove.example.com');
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.code).toBe('TLS_UNTRUSTED');
      expect(result.message).toMatch(/Let’s Encrypt|Tailscale/);
    }
  });
});
