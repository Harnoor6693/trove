import {
  AndroidConfig,
  ConfigPlugin,
  withAndroidManifest,
  withDangerousMod,
} from 'expo/config-plugins';
import fs from 'fs/promises';
import path from 'path';

/**
 * Hosts the Android build may reach over plain http, and only when the user
 * has opted in on the connect screen. Android's network security config
 * matches names, not CIDR ranges, so a LAN IP address cannot be listed: those
 * need https. Keep this list in step with `androidAllowsCleartext` in
 * `src/api/serverUrl.ts`.
 *
 *  - `localhost`, `127.0.0.1`: the device itself
 *  - `10.0.2.2`: the emulator's alias for the host machine (dev and Detox)
 *  - `local`: mDNS names, `nas.local`, matched with subdomains
 */
export const ANDROID_CLEARTEXT_HOSTS = ['localhost', '127.0.0.1', '10.0.2.2', 'local'] as const;

export function networkSecurityConfigXml(): string {
  const domains = ANDROID_CLEARTEXT_HOSTS.map(
    (host) => `    <domain includeSubdomains="${host === 'local'}">${host}</domain>`
  ).join('\n');
  return `<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
  <base-config cleartextTrafficPermitted="false" />
  <domain-config cleartextTrafficPermitted="true">
${domains}
  </domain-config>
</network-security-config>
`;
}

/** Writes `res/xml/network_security_config.xml` and points the manifest at it. */
export const withAndroidNetworkSecurityConfig: ConfigPlugin = (config) =>
  withAndroidManifest(
    withDangerousMod(config, [
      'android',
      async (config) => {
        const dir = path.join(config.modRequest.platformProjectRoot, 'app/src/main/res/xml');
        await fs.mkdir(dir, { recursive: true });
        await fs.writeFile(
          path.join(dir, 'network_security_config.xml'),
          networkSecurityConfigXml()
        );
        return config;
      },
    ]),
    (config) => {
      const app = AndroidConfig.Manifest.getMainApplicationOrThrow(config.modResults);
      app.$['android:networkSecurityConfig'] = '@xml/network_security_config';
      return config;
    }
  );
