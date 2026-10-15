import {
  ANDROID_CLEARTEXT_HOSTS,
  networkSecurityConfigXml,
} from './withAndroidNetworkSecurityConfig';

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
});

describe('app.config.ts wiring (B5-03)', () => {
  it('keeps the global cleartext flag off and applies the plugin', () => {
    const build = require('../app.config').default as (c: { config: object }) => {
      android: Record<string, unknown>;
      mods?: { android?: { manifest?: unknown } };
    };
    const resolved = build({ config: {} });
    expect(resolved.android.usesCleartextTraffic).toBe(false);
    expect(resolved.mods?.android?.manifest).toBeDefined();
  });
});
