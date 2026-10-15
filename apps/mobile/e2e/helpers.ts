import { element, by, expect as detoxExpect, waitFor } from 'detox';

/**
 * `(auth)/connect` always renders first (`app/(auth)/connect.tsx` — the dev
 * default only pre-fills the address field, it never auto-submits or
 * auto-navigates, see `src/api/serverConfig.ts`). Every suite that needs an
 * authenticated-session screen has to drive that one tap itself first; this
 * is the one place that tap lives so `auth.e2e.ts` and `onboarding.e2e.ts`
 * don't each duplicate (and drift on) it.
 *
 * Three things the dev default doesn't do for you:
 *  - `10.0.2.2` (the Android emulator's alias for the host) is in RFC1918
 *    `10/8`, so `serverUrl.ts`'s `normaliseServerUrl` treats the dev default
 *    `http://10.0.2.2:8080` as a LAN address and rejects it
 *    (`INSECURE_LAN_NOT_ALLOWED`) unless the "Allow http on my network"
 *    toggle is on first — it defaults to off.
 *  - `handleConnect` (`connect.tsx`) awaits a real network probe
 *    (`GET /api/v1/status`) before navigating — `toBeVisible()` alone checks
 *    the current frame with no retry, so it races the probe.
 *    `waitFor(...).withTimeout()` polls instead.
 *  - `devDefaultBaseUrl()` reads `Constants.expoConfig.extra.apiBaseUrl`,
 *    which `app.config.ts` bakes in from `process.env.EXPO_PUBLIC_API_BASE_URL`
 *    **at native-build time** on both platforms, not something Metro
 *    re-serves per launch (restarting Metro, even with `--clear`, does not
 *    change it): Android's Gradle build writes it into the debug APK's
 *    `assets/app.config`, and iOS's Xcode build writes it into
 *    `Trove.app/EXConstants.bundle/app.config` via the `EXConstants` pod
 *    script phase. If the debug build was made without that env var set,
 *    the address field starts empty and every one of these suites times out
 *    waiting for `login-screen` with no network call ever reaching `core` —
 *    rebuild via `EXPO_PUBLIC_API_BASE_URL=http://10.0.2.2:8080 npm run
 *    build:android:debug` (Android) or `EXPO_PUBLIC_API_BASE_URL=
 *    http://localhost:8080 npm run build:ios:debug` (iOS) is the fix, not
 *    anything in this file (step 27.4, bug #6f; iOS case found step 27.5).
 */
export async function connectToDevServer(): Promise<void> {
  await detoxExpect(element(by.id('connect-screen'))).toBeVisible();
  // `MnToggle`'s own testID lands on the labelled row, not the `<Switch>`
  // inside it — tapping the row does nothing (see the same note in
  // `app/(tabs)/admin/index.test.tsx`) — so it also exposes a `-switch`
  // suffixed id on the actual `Switch`.
  await element(by.id('connect-allow-http-toggle-switch')).tap();
  await element(by.id('connect-submit-btn')).tap();
  await waitFor(element(by.id('login-screen')))
    .toBeVisible()
    .withTimeout(10000);
}
