import { Platform } from 'react-native';

/**
 * Validation and normalisation for the user-supplied Trove server URL.
 *
 * Every Trove user runs their own `trove-core`, so the app cannot ship a
 * baked-in origin (readiness gap P4-19). The URL the user types on
 * `app/(auth)/connect.tsx` is the single base for every request, so it is
 * validated here — as a pure function, so the rules are testable without a
 * device — before anything is persisted or dialled.
 *
 * Rules:
 *  - `https://` is always accepted.
 *  - `http://` is rejected for public hosts, unconditionally.
 *  - `http://` is accepted for `localhost`, loopback, `.local` mDNS names and
 *    RFC1918/CGNAT/link-local addresses **only** when the caller passes
 *    `allowInsecureLan` (the "allow http on my network" toggle).
 *  - No other scheme is accepted; a bare host defaults to `https://`.
 *  - Any path, query or fragment is stripped: the base URL is origin-only,
 *    because `/api/v1/...` is appended to it verbatim.
 *
 * Policy (gap P4-19): no certificate pinning, and self-signed certificates are
 * not supported — users are directed at ACME or Tailscale certificates.
 */

export type ServerUrlErrorCode =
  | 'EMPTY'
  | 'INVALID_URL'
  | 'UNSUPPORTED_SCHEME'
  | 'INSECURE_PUBLIC_HOST'
  | 'INSECURE_LAN_NOT_ALLOWED'
  | 'INSECURE_HOST_BLOCKED'
  | 'MISSING_HOST';

export interface ServerUrlOk {
  ok: true;
  /** Origin-only, no trailing slash, e.g. `https://trove.example.com`. */
  url: string;
  scheme: 'http' | 'https';
  host: string;
  /** True when the host is loopback, `.local`, or a private-range address. */
  isPrivateHost: boolean;
}

export interface ServerUrlErr {
  ok: false;
  code: ServerUrlErrorCode;
  message: string;
}

export type ServerUrlResult = ServerUrlOk | ServerUrlErr;

const MESSAGES: Record<ServerUrlErrorCode, string> = {
  INSECURE_HOST_BLOCKED: 'This device will not connect to that address over http. Use https.',
  EMPTY: 'Enter the address of your Trove server.',
  INVALID_URL: "That doesn't look like a web address.",
  UNSUPPORTED_SCHEME: 'Use an https:// address.',
  MISSING_HOST: 'That address is missing a host name.',
  INSECURE_PUBLIC_HOST:
    'Trove will only talk to a public server over https. Set up a certificate (Let’s Encrypt or Tailscale) and try again.',
  INSECURE_LAN_NOT_ALLOWED:
    'This looks like an address on your own network. Turn on “Allow http on my network” to connect without https.',
};

const ANDROID_BLOCKED_MESSAGE =
  'Android only allows http for localhost and .local names. Use https, or a name ending in .local.';
const IOS_BLOCKED_MESSAGE =
  'iOS only allows http for IP addresses and .local names. Use https, or one of those.';

function err(code: ServerUrlErrorCode): ServerUrlErr {
  return { ok: false, code, message: MESSAGES[code] };
}

/** IPv4 dotted-quad, or null when the string is not one. */
function parseIpv4(host: string): number[] | null {
  const parts = host.split('.');
  if (parts.length !== 4) return null;
  const octets: number[] = [];
  for (const part of parts) {
    if (!/^\d{1,3}$/.test(part)) return null;
    const n = Number(part);
    if (n > 255) return null;
    octets.push(n);
  }
  return octets;
}

/**
 * True for hosts that are unambiguously on the user's own network: loopback,
 * mDNS `.local` names, RFC1918 ranges, link-local, CGNAT, and IPv6
 * loopback/ULA/link-local.
 */
export function isPrivateHost(rawHost: string): boolean {
  const host = rawHost.toLowerCase().replace(/^\[|\]$/g, '');

  if (host === 'localhost' || host.endsWith('.localhost')) return true;
  if (host === '::1' || host === '0:0:0:0:0:0:0:1') return true;
  // IPv6 unique-local (fc00::/7) and link-local (fe80::/10).
  if (/^f[cd][0-9a-f]{2}:/.test(host)) return true;
  if (/^fe[89ab][0-9a-f]:/.test(host)) return true;
  if (host.endsWith('.local') || host.endsWith('.internal') || host.endsWith('.home.arpa')) {
    return true;
  }

  const ip = parseIpv4(host);
  if (ip) {
    const [a, b] = ip;
    if (a === 127) return true; // loopback
    if (a === 10) return true; // RFC1918 10/8
    if (a === 192 && b === 168) return true; // RFC1918 192.168/16
    if (a === 172 && b >= 16 && b <= 31) return true; // RFC1918 172.16/12
    if (a === 169 && b === 254) return true; // link-local
    if (a === 100 && b >= 64 && b <= 127) return true; // CGNAT 100.64/10
    return false;
  }

  return false;
}

/**
 * True when the running platform will actually let an http request to `host`
 * through, given the native config in `app.config.ts`:
 *  - iOS `NSAllowsLocalNetworking`: IP addresses, `.local`.
 *  - Android network security config: names, not ranges, so only `localhost`,
 *    the emulator alias `10.0.2.2` and `.local`. Keep in step with
 *    `plugins/withAndroidNetworkSecurityConfig.ts`.
 * Anything else would pass the check on the connect screen and then fail at
 * the OS with "Couldn't reach that address" (B5-03, B5-13).
 */
function platformAllowsCleartext(host: string): boolean {
  const h = host.toLowerCase().replace(/^\[|\]$/g, '');
  if (h === 'localhost' || h.endsWith('.local')) return true;
  if (Platform.OS === 'android') return h === '10.0.2.2' || h === '127.0.0.1';
  return parseIpv4(h) !== null || h.includes(':');
}

/**
 * Normalise and validate a user-typed server address.
 *
 * @param raw               what the user typed
 * @param allowInsecureLan  the "allow http on my network" opt-in
 */
export function normaliseServerUrl(raw: string, allowInsecureLan = false): ServerUrlResult {
  const trimmed = raw.trim();
  if (trimmed === '') return err('EMPTY');

  // A bare host ("trove.example.com", "192.168.1.9:8080") means https.
  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;

  let parsed: URL;
  try {
    parsed = new URL(withScheme);
  } catch {
    return err('INVALID_URL');
  }

  const scheme = parsed.protocol.replace(/:$/, '').toLowerCase();
  if (scheme !== 'http' && scheme !== 'https') return err('UNSUPPORTED_SCHEME');
  if (parsed.hostname === '') return err('MISSING_HOST');
  // `new URL('https://a b')` throws, but some hosts survive parsing with
  // characters no DNS name or IP literal can contain.
  if (/[\s_]/.test(parsed.hostname)) return err('INVALID_URL');

  const priv = isPrivateHost(parsed.hostname);

  if (scheme === 'http') {
    if (!priv) return err('INSECURE_PUBLIC_HOST');
    if (!allowInsecureLan) return err('INSECURE_LAN_NOT_ALLOWED');
    if (!platformAllowsCleartext(parsed.hostname)) {
      return {
        ok: false,
        code: 'INSECURE_HOST_BLOCKED',
        message: Platform.OS === 'android' ? ANDROID_BLOCKED_MESSAGE : IOS_BLOCKED_MESSAGE,
      };
    }
  }

  const port = parsed.port === '' ? '' : `:${parsed.port}`;
  return {
    ok: true,
    url: `${scheme}://${parsed.hostname.toLowerCase()}${port}`,
    scheme,
    host: parsed.hostname.toLowerCase(),
    isPrivateHost: priv,
  };
}
