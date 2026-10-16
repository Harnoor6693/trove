# Release (sideload APK)

Trove ships **no app store distribution** — no App Store, no Play Store listing. A
release is one signed Android APK, sideloaded by the person who wants it, plus one
`core` container image. This was an explicit decision (owner, plan step 19/22), not
an oversight; see the README's "What is not here".

## What the `Release` workflow does

`.github/workflows/release.yml` runs on a `v*.*.*` tag push:

1. Runs the same backend gate as ci.yml (`./mvnw --batch-mode -pl core -am clean verify checkstyle:check -Pintegration,coverage`).
2. Builds and pushes a multi-arch (linux/amd64 + linux/arm64) `core` image to `ghcr.io/<lower-case owner>/trove-core`, tagged `<tag>`, plus `latest` when the tag is a stable release (a pre-release tag containing `-`, for example v1.0.0-rc1, does not move `latest`).
3. Builds a signed Android APK via `eas build --profile production --platform android`
   (`apps/mobile/eas.json`'s `production` profile is `distribution: "internal"`,
   `buildType: "apk"` — never an AAB, never submitted to Play).
4. Creates a GitHub Release with generated notes and the signed APK attached. The core image is on GHCR, not on the release. Tags containing `-` (for example v1.0.0-rc1) are marked prerelease; the semver check only warns.

Only `EXPO_TOKEN` is required as a secret. There is no Apple/Google credential, no
`eas submit` call, and no iOS build in CI — iOS EAS builds remain available locally
(see [EAS Build Setup](./build)) for anyone who wants to run one on their own Apple
account, but nothing in this repository automates or ships one.

## Installing a release

1. Download the `.apk` asset from the tag's [GitHub Release](https://github.com/Harnoor6693/trove/releases).
2. Sideload it (enable "install from unknown sources" for the browser/file manager
   used, then open the file).
3. On first launch, set the server URL to the household's own Trove deployment —
   see `EXPO_PUBLIC_API_BASE_URL` / `apps/mobile/src/api/serverConfig.ts`; there is
   no bundled default in a release build.

## About the `submit:ios` / `submit:android` npm scripts

`apps/mobile/package.json` still has `eas submit` scripts left over from before this
decision. They work if someone supplies their own Apple/Google credentials, but
nothing in this project's CI or documentation depends on them, and no store listing
exists to submit to.
