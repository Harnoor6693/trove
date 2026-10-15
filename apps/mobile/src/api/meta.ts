/**
 * The connect-time server probe (§4.2 `GET /v1/status`).
 *
 * Called before anything is persisted, against a candidate base URL that is
 * not yet the app's configured server — so it uses its own axios call rather
 * than `api`, whose interceptors resolve the *configured* base URL.
 *
 * The probe answers three questions in one round trip:
 *   1. Is anything listening, and is TLS acceptable?
 *   2. Is it a Trove server? (`{status, version}` with a recognised status)
 *   3. Can this app build speak to it? (`compatibility.ts`)
 */

import axios from 'axios';
import Constants from 'expo-constants';
import { checkCompatibility } from './compatibility';
import type { ApiResponse, AuthProviders, ServerMeta } from './types';
import { API_CONTEXT_PATH, api } from './client';

/** Probes must not hang on a wrong address for the full 15-second timeout. */
const PROBE_TIMEOUT_MS = 8_000;

export type ProbeFailureCode =
  'UNREACHABLE' | 'TLS_UNTRUSTED' | 'NOT_TROVE' | 'INCOMPATIBLE' | 'SERVER_ERROR';

export type ProbeResult =
  { ok: true; meta: ServerMeta } | { ok: false; code: ProbeFailureCode; message: string };

/** This build's marketing version, from `app.config.ts` (`version`). */
export function appVersion(): string {
  const version = Constants.expoConfig?.version;
  return typeof version === 'string' && version !== '' ? version : '0.0.0';
}

function isServerMeta(value: unknown): value is ServerMeta {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Partial<ServerMeta>;
  return (
    (candidate.status === 'UP' || candidate.status === 'DEGRADED') &&
    typeof candidate.version === 'string'
  );
}

/**
 * TLS failures surface as opaque network errors on React Native, so they are
 * identified by message. Self-signed certificates are unsupported policy
 * (gap P4-19) — the copy points at ACME/Tailscale rather than an override.
 */
function looksLikeTlsFailure(message: string): boolean {
  return /certificate|ssl|tls|trust anchor|handshake/i.test(message);
}

/**
 * Probe a candidate server.
 *
 * @param baseUrl origin only, already normalised by `serverUrl.ts`
 */
export async function probeServer(baseUrl: string): Promise<ProbeResult> {
  let body: unknown;
  try {
    const response = await axios.get<ApiResponse<ServerMeta> | ServerMeta>(
      `${baseUrl}${API_CONTEXT_PATH}/v1/status`,
      { timeout: PROBE_TIMEOUT_MS, validateStatus: (status) => status < 500 }
    );
    body = response.data;
  } catch (err) {
    const message = err instanceof Error ? err.message : '';
    if (looksLikeTlsFailure(message)) {
      return {
        ok: false,
        code: 'TLS_UNTRUSTED',
        message:
          'That server’s certificate isn’t trusted. Trove doesn’t support self-signed certificates — use Let’s Encrypt or a Tailscale certificate.',
      };
    }
    return {
      ok: false,
      code: 'UNREACHABLE',
      message: 'Couldn’t reach that address. Check the name, the port, and that the server is up.',
    };
  }

  // `/v1/status` is the server-detection endpoint and its body is **bare**,
  // not wrapped in `ApiResponse` (confirmed with the server side) — it has to
  // be readable by a client that has not yet established the server speaks
  // Trove at all. The enveloped shape is still accepted so a server that
  // wraps it uniformly is not rejected as "not Trove".
  const enveloped = (body as ApiResponse<ServerMeta> | null)?.data;
  const meta = isServerMeta(enveloped) ? enveloped : isServerMeta(body) ? body : null;

  if (meta === null) {
    return {
      ok: false,
      code: 'NOT_TROVE',
      message: 'Something answered at that address, but it isn’t a Trove server.',
    };
  }

  const compatibility = checkCompatibility(meta, appVersion());
  if (!compatibility.compatible) {
    return { ok: false, code: 'INCOMPATIBLE', message: compatibility.message };
  }

  return { ok: true, meta };
}

/**
 * `GET /v1/auth/providers` (§4.1) — gates the OIDC button and the
 * closed-registration message. Best-effort: a server that predates the route
 * is treated as local-only with registration closed.
 */
export async function fetchAuthProviders(): Promise<AuthProviders> {
  const fallback: AuthProviders = {
    local: true,
    oidc: { enabled: false, startUrl: null, displayName: null },
    registrationOpen: false,
  };
  try {
    const response = await api.get<ApiResponse<AuthProviders>>('/v1/auth/providers', {
      skipAuthRefresh: true,
    });
    return response.data?.data ?? fallback;
  } catch {
    return fallback;
  }
}

/** `GET /v1/status` against the already-connected server (Settings › About). */
export async function fetchServerMeta(): Promise<ServerMeta | null> {
  try {
    const response = await api.get<ApiResponse<ServerMeta> | ServerMeta>('/v1/status', {
      skipAuthRefresh: true,
    });
    const enveloped = (response.data as ApiResponse<ServerMeta> | null)?.data;
    if (isServerMeta(enveloped)) return enveloped;
    return isServerMeta(response.data) ? response.data : null;
  } catch {
    return null;
  }
}
