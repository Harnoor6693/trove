import { isPrivateHost, normaliseServerUrl } from './serverUrl';

/**
 * The TLS policy for a user-supplied server address (readiness gap P4-19).
 * These cases are the policy; the screen is just a text field on top of them.
 */
describe('normaliseServerUrl', () => {
  describe('accepts https', () => {
    const cases: ReadonlyArray<[string, string]> = [
      ['https://trove.example.com', 'https://trove.example.com'],
      ['trove.example.com', 'https://trove.example.com'],
      ['  https://trove.example.com/  ', 'https://trove.example.com'],
      ['https://trove.example.com:8443', 'https://trove.example.com:8443'],
      ['https://TROVE.example.com', 'https://trove.example.com'],
      // Path, query and fragment are stripped: the base is origin-only,
      // because `/api/v1/...` is appended to it verbatim.
      ['https://trove.example.com/api/v1/status?x=1#frag', 'https://trove.example.com'],
      // https to a LAN address needs no opt-in.
      ['https://192.168.1.9:8443', 'https://192.168.1.9:8443'],
    ];

    it.each(cases)('%s → %s', (input, expected) => {
      const result = normaliseServerUrl(input, false);
      expect(result.ok).toBe(true);
      if (result.ok) expect(result.url).toBe(expected);
    });
  });

  it('rejects http to a public host even with the LAN opt-in on', () => {
    for (const allow of [false, true]) {
      const result = normaliseServerUrl('http://trove.example.com', allow);
      expect(result.ok).toBe(false);
      if (!result.ok) expect(result.code).toBe('INSECURE_PUBLIC_HOST');
    }
  });

  describe('rejects http to a LAN host unless the user opts in', () => {
    const lanHosts = [
      'http://localhost:8080',
      'http://127.0.0.1:8080',
      'http://192.168.1.9:8080',
      'http://10.0.0.4:8080',
      'http://172.16.4.4:8080',
      'http://trove.local:8080',
      'http://100.101.102.103:8080',
    ];

    it.each(lanHosts)('%s is rejected without the opt-in', (input) => {
      const result = normaliseServerUrl(input, false);
      expect(result.ok).toBe(false);
      if (!result.ok) expect(result.code).toBe('INSECURE_LAN_NOT_ALLOWED');
    });

    it.each(lanHosts)('%s is accepted with the opt-in', (input) => {
      const result = normaliseServerUrl(input, true);
      expect(result.ok).toBe(true);
      if (result.ok) {
        expect(result.scheme).toBe('http');
        expect(result.isPrivateHost).toBe(true);
      }
    });
  });

  describe('rejects malformed input', () => {
    const cases: ReadonlyArray<[string, string]> = [
      ['', 'EMPTY'],
      ['   ', 'EMPTY'],
      ['ftp://trove.example.com', 'UNSUPPORTED_SCHEME'],
      ['ws://trove.example.com', 'UNSUPPORTED_SCHEME'],
      ['https://', 'INVALID_URL'],
      ['https://not a host', 'INVALID_URL'],
    ];

    it.each(cases)('%s → %s', (input, code) => {
      const result = normaliseServerUrl(input, true);
      expect(result.ok).toBe(false);
      if (!result.ok) expect(result.code).toBe(code);
    });
  });

  it('carries a message the connect screen can show verbatim', () => {
    const result = normaliseServerUrl('http://trove.example.com');
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.message).toMatch(/https/);
  });
});

describe('isPrivateHost', () => {
  const privateHosts = [
    'localhost',
    'trove.localhost',
    '127.0.0.1',
    '10.1.2.3',
    '192.168.0.1',
    '172.16.0.1',
    '172.31.255.254',
    '169.254.1.1',
    '100.64.0.1',
    'nas.local',
    'trove.internal',
    'trove.home.arpa',
    '::1',
    'fd00::1',
    'fe80::1',
  ];

  const publicHosts = [
    'trove.example.com',
    '8.8.8.8',
    '172.32.0.1',
    '172.15.0.1',
    '100.128.0.1',
    '9.9.9.9',
    '2606:4700:4700::1111',
  ];

  it.each(privateHosts)('%s is private', (host) => {
    expect(isPrivateHost(host)).toBe(true);
  });

  it.each(publicHosts)('%s is public', (host) => {
    expect(isPrivateHost(host)).toBe(false);
  });
});
