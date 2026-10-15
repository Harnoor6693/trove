import {
  checkCompatibility,
  compareVersions,
  MAX_SERVER_MAJOR,
  MIN_SERVER_MAJOR,
  parseVersion,
} from './compatibility';
import type { ServerMeta } from './types';

function meta(overrides: Partial<ServerMeta> = {}): ServerMeta {
  return { status: 'UP', version: '1.0.0', ...overrides };
}

describe('parseVersion', () => {
  it.each([
    ['1.4.2', [1, 4, 2]],
    ['v2.0.0', [2, 0, 0]],
    ['1.4', [1, 4, 0]],
    ['3', [3, 0, 0]],
    ['1.4.2-SNAPSHOT', [1, 4, 2]],
  ])('%s → %j', (input, expected) => {
    expect(parseVersion(input)).toEqual(expected);
  });

  it.each(['', 'unknown', 'trove'])('rejects %p', (input) => {
    expect(parseVersion(input)).toBeNull();
  });
});

describe('compareVersions', () => {
  it('orders by major, then minor, then patch', () => {
    expect(compareVersions('1.0.0', '2.0.0')).toBe(-1);
    expect(compareVersions('1.2.0', '1.10.0')).toBe(-1);
    expect(compareVersions('1.2.3', '1.2.3')).toBe(0);
    expect(compareVersions('1.2.4', '1.2.3')).toBe(1);
  });

  it('returns null when either side is unparseable', () => {
    expect(compareVersions('1.0.0', 'nope')).toBeNull();
  });
});

describe('checkCompatibility', () => {
  it('accepts a server inside the supported major range', () => {
    expect(checkCompatibility(meta({ version: `${MIN_SERVER_MAJOR}.7.3` }), '1.0.0')).toMatchObject(
      {
        compatible: true,
        code: 'OK',
      }
    );
  });

  it('rejects a server newer than this build understands', () => {
    const result = checkCompatibility(meta({ version: `${MAX_SERVER_MAJOR + 1}.0.0` }), '1.0.0');
    expect(result.compatible).toBe(false);
    expect(result.code).toBe('APP_TOO_OLD');
    expect(result.message).toMatch(/update the app/i);
  });

  it('rejects a server older than this build understands', () => {
    const result = checkCompatibility(meta({ version: `${MIN_SERVER_MAJOR - 1}.9.9` }), '1.0.0');
    expect(result.compatible).toBe(false);
    expect(result.code).toBe('APP_TOO_NEW');
    expect(result.message).toMatch(/update your server/i);
  });

  // `core`'s HealthController reports "unknown" whenever Spring's
  // BuildProperties is absent — i.e. any build that skipped the `build-info`
  // goal. Blocking on that would make a freshly built server unpairable, which
  // is the worst failure mode a self-hosted app has. It is reported, not
  // refused; the string itself is shown in Settings › About.
  it('does not refuse a server that cannot name its own version', () => {
    const result = checkCompatibility(meta({ version: 'unknown' }), '1.0.0');
    expect(result.compatible).toBe(true);
    expect(result.code).toBe('UNREADABLE_VERSION');
    expect(result.message).toMatch(/unrecognised version/i);
  });

  // An advertised window is the server's explicit opinion and still binds,
  // even when its own version string is unreadable.
  it('still refuses an unreadable version when the advertised window excludes this app', () => {
    const result = checkCompatibility(
      meta({ version: 'unknown', minMobileVersion: '2.0.0' }),
      '1.0.0'
    );
    expect(result.compatible).toBe(false);
    expect(result.code).toBe('APP_TOO_OLD');
  });

  // The server's own opinion wins when it advertises one, so a future server
  // can drop support for an old app build without this app being reissued.
  it('honours minMobileVersion when the app is too old', () => {
    const result = checkCompatibility(meta({ minMobileVersion: '2.0.0' }), '1.0.0');
    expect(result.compatible).toBe(false);
    expect(result.code).toBe('APP_TOO_OLD');
  });

  it('honours maxMobileVersion when the app is too new', () => {
    const result = checkCompatibility(meta({ maxMobileVersion: '1.0.0' }), '1.5.0');
    expect(result.compatible).toBe(false);
    expect(result.code).toBe('APP_TOO_NEW');
  });

  it('accepts when the app sits inside the advertised window', () => {
    const result = checkCompatibility(
      meta({ minMobileVersion: '1.0.0', maxMobileVersion: '2.0.0' }),
      '1.4.0'
    );
    expect(result.compatible).toBe(true);
  });
});
