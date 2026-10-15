# EAS Build Setup

The mobile app (`apps/mobile/`) is a React Native + Expo project. Builds go through
[Expo Application Services (EAS)](https://docs.expo.dev/eas/), but Trove's own
distribution is **sideload only** — see [Release (sideload APK)](./app-store-release)
for what actually ships. This page covers local/manual builds for development and QA.

## Prerequisites

```bash
npm install -g eas-cli
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
| `staging` | _(unset — set per environment)_ | Internal build against a real deployment. |
| `production` | _(unset — set per environment)_ | Signed Android APK; this is the profile the `Release` GitHub Actions workflow builds and attaches to a GitHub Release. Android only, `buildType: apk`. |

## Build commands

```bash
cd apps/mobile

npm run build:dev    # eas build --profile development --platform all
npm run build:prev   # eas build --profile preview --platform all
npm run build:prod   # eas build --profile production --platform all
```

The `submit:ios`/`submit:android` npm scripts (`eas submit`) exist in `package.json`
but nothing in this repository's CI calls them — see the note in
[Release (sideload APK)](./app-store-release).

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
