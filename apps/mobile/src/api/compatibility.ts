/**
 * API compatibility handshake between this app build and the user's server.
 *
 * A sideloaded APK and a self-hosted `trove-core` upgrade on completely
 * different schedules (readiness gap P4-19), so the connect flow refuses to
 * pair with a server this build cannot speak to, rather than failing later
 * with an unexplained 404 on some route.
 *
 * The contract is `GET /api/v1/status` (§4.2 — the unauthenticated,
 * detail-free probe the plan chose). It commits to `{status, version}` only,
 * so compatibility is decided in two passes:
 *
 *  1. If the server advertises `minMobileVersion` / `maxMobileVersion` (the
 *     fields readiness gap P4-19 asked for), the server's opinion wins.
 *  2. Otherwise the client compares the server's own `version` against the
 *     major range this build was written against.
 *
 * A version this client cannot parse is **not** treated as incompatible.
 * `core`'s `HealthController` reports `"unknown"` whenever Spring's
 * `BuildProperties` is absent — which is every build that did not run the
 * `build-info` goal — and refusing to pair with a server the user just stood
 * up, because it could not name its own version, is the worst possible
 * failure for a self-hosted product. Absence of evidence is not evidence of
 * incompatibility; a genuine mismatch still blocks, and an unreadable version
 * is surfaced verbatim in Settings › About.
 */

import type { ServerMeta } from './types';

/** Lowest `trove-core` major version this build speaks. */
export const MIN_SERVER_MAJOR = 1;
/** Highest `trove-core` major version this build speaks. */
export const MAX_SERVER_MAJOR = 1;

export type CompatibilityCode = 'OK' | 'APP_TOO_OLD' | 'APP_TOO_NEW' | 'UNREADABLE_VERSION';

export interface CompatibilityResult {
  compatible: boolean;
  code: CompatibilityCode;
  message: string;
}

const OK: CompatibilityResult = { compatible: true, code: 'OK', message: '' };

/** Parse `"1.4.2"`, `"1.4.2-SNAPSHOT"` or `"1.4"` into `[major, minor, patch]`. */
export function parseVersion(version: string): [number, number, number] | null {
  const match = /^\s*v?(\d+)(?:\.(\d+))?(?:\.(\d+))?/.exec(version);
  if (!match) return null;
  return [Number(match[1]), Number(match[2] ?? 0), Number(match[3] ?? 0)];
}

/** -1, 0 or 1. Returns null when either side is unparseable. */
export function compareVersions(a: string, b: string): number | null {
  const left = parseVersion(a);
  const right = parseVersion(b);
  if (!left || !right) return null;
  for (let i = 0; i < 3; i += 1) {
    if (left[i] !== right[i]) return left[i] < right[i] ? -1 : 1;
  }
  return 0;
}

/**
 * @param meta       the body of `GET /api/v1/status`
 * @param appVersion this build's marketing version (`app.config.ts` `version`)
 */
export function checkCompatibility(meta: ServerMeta, appVersion: string): CompatibilityResult {
  if (meta.minMobileVersion) {
    const cmp = compareVersions(appVersion, meta.minMobileVersion);
    if (cmp !== null && cmp < 0) {
      return {
        compatible: false,
        code: 'APP_TOO_OLD',
        message: `That server needs Trove ${meta.minMobileVersion} or newer. This app is ${appVersion} — update the app.`,
      };
    }
  }

  if (meta.maxMobileVersion) {
    const cmp = compareVersions(appVersion, meta.maxMobileVersion);
    if (cmp !== null && cmp > 0) {
      return {
        compatible: false,
        code: 'APP_TOO_NEW',
        message: `That server only supports Trove up to ${meta.maxMobileVersion}. This app is ${appVersion} — update your server.`,
      };
    }
  }

  const serverVersion = parseVersion(meta.version);
  if (!serverVersion) {
    // Non-blocking: it answered `/v1/status` with a Trove-shaped body and did
    // not advertise a window this app falls outside of. See the header note.
    return {
      compatible: true,
      code: 'UNREADABLE_VERSION',
      message: `That server reported an unrecognised version (“${meta.version}”).`,
    };
  }

  const [major] = serverVersion;
  if (major < MIN_SERVER_MAJOR) {
    return {
      compatible: false,
      code: 'APP_TOO_NEW',
      message: `That server is version ${meta.version}. This app needs ${MIN_SERVER_MAJOR}.x or newer — update your server.`,
    };
  }
  if (major > MAX_SERVER_MAJOR) {
    return {
      compatible: false,
      code: 'APP_TOO_OLD',
      message: `That server is version ${meta.version}. This app only speaks ${MAX_SERVER_MAJOR}.x — update the app.`,
    };
  }

  return OK;
}
