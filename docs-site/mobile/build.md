# EAS Build Setup

The mobile app (`apps/mobile/`) is a React Native + Expo project. Builds go through
[Expo Application Services (EAS)](https://docs.expo.dev/eas/), but Trove's own
distribution is **sideload only** — see [Release (sideload APK)](./app-store-release)
for what actually ships. This page covers local/manual builds for development and QA.

## Prerequisites

```bash
npm install -g eas-cli   # eas.json requires >= 13.0.0; the Release workflow pins one version
eas login
```

Confirm:

```bash
eas whoami
```

## Build profiles

Defined in `apps/mobile/eas.json` — all four profiles use `distribution: "internal"`
(no App Store / Play Store profile exists):

| Profile | API base URL | Use for |
|---|---|---|
| `development` | `http://localhost:8080` | Dev client, iOS Simulator / Android emulator. |
| `preview` | `http://localhost:8080` | Internal build for manual QA. |
| `staging` | _(empty by design - the user enters the server address on first launch)_ | Internal build against a real deployment. |
| `production` | _(empty by design - the user enters the server address on first launch)_ | Signed Android APK; this is the profile the `Release` GitHub Actions workflow builds and attaches to a GitHub Release. Android only, `buildType: apk`. |

## Build commands

```bash
cd apps/mobile

npm run build:dev    # eas build --profile development --platform all
npm run build:prev   # eas build --profile preview --platform all
npm run build:prod   # eas build --profile production --platform all
# build:prod passes --platform all; the Release workflow uses `eas build --profile production --platform android`, the only platform this profile defines
```

The `submit:ios`/`submit:android` npm scripts (`eas submit`) exist in `package.json`
but nothing in this repository's CI calls them — see the note in
[Release (sideload APK)](./app-store-release).

## iOS

No iOS binary is published: there is no TestFlight or AltStore build, and the Release
workflow builds Android only. To run Trove on an iPhone, build it yourself with EAS on your
own Apple account. `eas build --profile development --platform ios` produces an iOS
Simulator build (the `development` profile sets `ios.simulator`); a build for a physical
device needs your own Apple Developer account and signing credentials
(`eas credentials --platform ios`).

## Credentials

EAS manages signing keys for you; to audit or rotate them:

```bash
eas credentials --platform ios
eas credentials --platform android
```

## Local test commands

Before pushing a build:

```bash
cd apps/mobile

npm run test:ci                 # Jest, coverage, CI reporters
npm run build:android:debug     # Detox build (Android emulator)
npm run test:e2e:android        # Detox E2E (Android emulator)

# iOS Detox targets exist (build:ios:debug / test:e2e:ios) but need a macOS runner.
```

Detox needs a generated native project and a running server. Run `npx expo prebuild --platform android --no-install --clean` once to generate `android/`. Start an empty Postgres and run `scripts/e2e-server.sh` from the repo root (it builds the core jar if missing and listens on :8080). Start Metro with `npm start`. Create an AVD named `Pixel_5_API_36` (API 36, google_apis). The Detox jobs in Mobile CI run only on manual dispatch (workflow_dispatch).
