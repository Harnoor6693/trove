import { Platform } from 'react-native';
import { normaliseServerUrl } from '@api/serverUrl';
import { ANDROID_CLEARTEXT_HOSTS, networkSecurityConfigXml } from './app.config';

describe('Android network security config (B5-03)', () => {
  const xml = networkSecurityConfigXml();

  it('forbids cleartext by default', () => {
    expect(xml).toMatch(/<base-config cleartextTrafficPermitted="false"/);
  });

  it('permits cleartext only for the listed LAN hosts', () => {
    const permitted = [
      ...xml.matchAll(
        /<domain-config cleartextTrafficPermitted="true">([\s\S]*?)<\/domain-config>/g
      ),
    ].flatMap((m) => [...m[1].matchAll(/<domain[^>]*>([^<]+)<\/domain>/g)].map((d) => d[1]));
    expect(permitted.sort()).toEqual([...ANDROID_CLEARTEXT_HOSTS].sort());
    expect(permitted).toContain('local');
    expect(permitted).not.toContain('*');
  });

  it('keeps the hosts Detox and Metro use on the emulator', () => {
    // A network security config overrides the debug manifest's
    // `usesCleartextTraffic`, so these must stay listed: the in-app Detox
    // client dials ws://localhost:<port> (adb reverse), and Metro and the e2e
    // server are reached at http://10.0.2.2.
    expect(ANDROID_CLEARTEXT_HOSTS).toEqual(expect.arrayContaining(['localhost', '10.0.2.2']));
  });
});

describe('the connect-screen validator matches the Android config (B5-03)', () => {
  const setOs = (os: 'ios' | 'android') => {
    Object.defineProperty(Platform, 'OS', { value: os, configurable: true });
  };
  afterEach(() => setOs('ios'));

  it.each(ANDROID_CLEARTEXT_HOSTS.map((h) => (h === 'local' ? 'nas.local' : h)))(
    'accepts http://%s only after the opt-in',
    (host) => {
      setOs('android');
      expect(normaliseServerUrl(`http://${host}:8080`, true).ok).toBe(true);
      expect(normaliseServerUrl(`http://${host}:8080`, false).ok).toBe(false);
    }
  );

  it('refuses an http LAN address the config does not list', () => {
    setOs('android');
    expect(normaliseServerUrl('http://192.168.1.9:8080', true)).toMatchObject({
      ok: false,
      code: 'INSECURE_HOST_BLOCKED',
    });
  });
});

describe('app.config.ts wiring (B5-03)', () => {
  it('keeps the global cleartext flag off and applies the plugin', () => {
    const build = require('./app.config').default as (c: { config: object }) => {
      android: Record<string, unknown>;
      mods?: { android?: { manifest?: unknown } };
    };
    const resolved = build({ config: {} });
    expect(resolved.android.usesCleartextTraffic).toBe(false);
    expect(resolved.mods?.android?.manifest).toBeDefined();
  });
});
