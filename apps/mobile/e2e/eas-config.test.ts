import * as fs from 'fs';
import * as path from 'path';

describe('eas.json profile validation', () => {
  const easPath = path.join(__dirname, '..', 'eas.json');
  const raw = fs.readFileSync(easPath, 'utf-8');
  const eas = JSON.parse(raw);

  it('declares all four required build profiles', () => {
    expect(eas.build.development).toBeDefined();
    expect(eas.build.preview).toBeDefined();
    expect(eas.build.staging).toBeDefined();
    expect(eas.build.production).toBeDefined();
  });

  // Trove is self-hosted: there is no vendor-run API host, so no profile may
  // bake one in. Dev builds point at a local server; release builds ship an
  // empty base URL and the user enters their own server address on first run.
  it('collapses the API base URL to dev (localhost) and prod (runtime entry)', () => {
    expect(eas.build.development.env.EXPO_PUBLIC_API_BASE_URL).toBe('http://localhost:8080');
    expect(eas.build.preview.env.EXPO_PUBLIC_API_BASE_URL).toBe('http://localhost:8080');
    expect(eas.build.staging.env.EXPO_PUBLIC_API_BASE_URL).toBe('');
    expect(eas.build.production.env.EXPO_PUBLIC_API_BASE_URL).toBe('');
  });

  it('hardcodes no remote API host in any profile', () => {
    expect(raw).not.toMatch(/trove\.app/);
    for (const profile of Object.values<any>(eas.build)) {
      const url: string | undefined = profile?.env?.EXPO_PUBLIC_API_BASE_URL;
      if (url) {
        expect(url).toMatch(/^http:\/\/localhost(:\d+)?$/);
      }
    }
  });

  // OTA is off for v1.0: `expo-updates` is not a dependency and
  // `app.config.ts` sets `updates.enabled: false`, so an EAS Update channel on
  // any profile would be a phone-home decision made by accident
  // (readiness gap P4-21). Every change ships as a new binary.
  it('declares no EAS Update channels on any profile', () => {
    for (const profile of Object.values<any>(eas.build)) {
      expect(profile.channel).toBeUndefined();
    }
    expect(raw).not.toMatch(/"channel"/);
  });

  // Store uploads are rejected when the build number does not increase, and
  // `autoIncrement` alone does nothing without a version source (gap P4-21).
  it('sources native build numbers from EAS, not the repo', () => {
    expect(eas.cli.appVersionSource).toBe('remote');
  });

  it('production profile auto-increments build numbers', () => {
    expect(eas.build.production.autoIncrement).toBe(true);
  });

  // v1.0 is sideload-only (signed APK per release + TestFlight/AltStore).
  // Every profile, production included, is internal distribution.
  it('marks every profile as internal-distribution', () => {
    expect(eas.build.development.distribution).toBe('internal');
    expect(eas.build.preview.distribution).toBe('internal');
    expect(eas.build.staging.distribution).toBe('internal');
    expect(eas.build.production.distribution).toBe('internal');
  });

  it('builds installable Android APKs, not Play-only app bundles', () => {
    expect(eas.build.development.android.buildType).toBe('apk');
    expect(eas.build.preview.android.buildType).toBe('apk');
    expect(eas.build.staging.android.buildType).toBe('apk');
    expect(eas.build.production.android.buildType).toBe('apk');
  });

  // Store submission is out of scope for v1.0, so there is no submit
  // configuration at all — and therefore no place for Apple Store Connect or
  // Google Play service-account identifiers to be committed.
  it('declares no store submission targets', () => {
    expect(eas.submit).toBeUndefined();
    expect(raw).not.toMatch(/serviceAccountKeyPath/);
    expect(raw).not.toMatch(/appleId|ascAppId|appleTeamId/);
  });
});
