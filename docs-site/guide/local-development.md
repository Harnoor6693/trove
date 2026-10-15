# Local Development

Full setup for running `core` and both clients straight from source, outside Docker.
For a self-hosted install instead, see [Docker Compose](../self-hosting/docker-compose).

## Prerequisites

- JDK 21 (`pom.xml`'s `<java.version>`/`<maven.compiler.release>`)
- Node — two different versions, one per client, because neither `apps/web/package.json`
  nor `apps/mobile/package.json` declares an `engines` field; these are the versions each
  app's own CI pins: **Node 22** for `apps/web` (`.github/workflows/web-ci.yml`
  `node-version: '22'`) and **Node 22.13.0** for `apps/mobile`
  (`.github/workflows/mobile-ci.yml` `node-version: '22.13.0'`). Not a hard local
  requirement, though: step 27 ran both clients' full command lists (`npm ci`, generate,
  dev/start, unit tests, Playwright, Detox) on a single global **Node 22.23.3** (no `nvm`,
  nothing switched between clients) without hitting a version-specific failure — install
  the pinned versions if you want to match CI exactly, otherwise whatever recent Node you
  have on `PATH` is a reasonable bet.
- Docker, for Postgres (see step 1)
- A Postgres 16 with the `pgvector` extension available (the deploy stack uses
  `pgvector/pgvector:pg16`; any Postgres 16 with `CREATE EXTENSION vector` works)
- For iOS: Xcode. The app is on **Expo SDK 58**, which builds and runs on **Xcode 27**
  (step 27.7 ran `expo run:ios` and iOS Detox on Xcode 27.0; see [iOS](#ios) below). CI's
  iOS job still selects Xcode 26 (`.github/workflows/mobile-ci.yml`
  `xcode-select -s /Applications/Xcode_26.app`). Pick a simulator
  `xcrun simctl list devicetypes` actually prints — `apps/mobile/.detoxrc.js`'s `ios.sim`
  device is **iPhone 17 Pro**, not iPhone 15 (not installed on every Mac; CI's own runner
  image may differ from yours). Also install
  [`applesimutils`](https://github.com/wix/AppleSimulatorUtils) — Detox's hard requirement
  for driving the iOS simulator (install/launch/terminate/biometrics), not listed here
  before step 27 found it missing: `brew tap wix/brew && brew install applesimutils`.
- For Android: the Android SDK with `sdkmanager`/`avdmanager`/`adb` on `PATH`, and an AVD
  named **`Pixel_5_API_36`** (API level 36 — Expo SDK 58 / React Native 0.88 compile against SDK 37 and
  target SDK 36, `react-native/gradle/libs.versions.toml`) — the exact name Detox's `android.emu` device
  expects (`apps/mobile/.detoxrc.js`); CI's own `reactivecircus/android-emulator-runner`
  step is still pinned to `api-level: 34` (`.github/workflows/mobile-ci.yml`), a
  pre-existing CI/local mismatch this doc doesn't paper over — see step 27 notes.

## 1. Start Postgres

```bash
docker run -d --name trove-postgres -p 5432:5432 \
  -e POSTGRES_DB=trove -e POSTGRES_USER=trove -e POSTGRES_PASSWORD=trove \
  pgvector/pgvector:pg16
```

Flyway (`core`) creates the schema on first boot. To reset: `docker rm -f trove-postgres`
and rerun the command above.

## 2. Run `core`

```bash
cd core
cp .env.example .env   # then edit it — see below
export $(grep -v '^#' .env | xargs)
../mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile (`core/src/main/resources/application-dev.yml`) points at
`jdbc:postgresql://localhost:5432/trove` with user `trove` / password `trove` (all three
overridable via `TROVE_DB_URL` / `TROVE_DB_USER` / `TROVE_DB_PASSWORD`) — matching the
Postgres container started in step 1. It does **not** default `TROVE_JWT_SECRET` — export
a real value (any random string; `openssl rand -hex 32` is fine for local use) or the app
refuses to boot. `TROVE_ENCRYPTION_KEY` does default in `dev`, but only in `dev`.

### First login

On an empty `users` table, `FirstRunBootstrap` creates one household and admin user,
controlled by `trove.bootstrap.*` (`finance.trove.app.bootstrap.BootstrapProperties`):

| Variable | Default |
|---|---|
| `TROVE_ADMIN_EMAIL` | none — set it |
| `TROVE_ADMIN_PASSWORD` | none — set it |
| `TROVE_HOUSEHOLD_NAME` | `Household` |
| `TROVE_REGISTRATION_OPEN` | `false` |

Set these in `core/.env` before the first boot. Bootstrap runs exactly once (it checks
whether `users` is empty) and never fails the boot if they are absent — it just leaves you
with no admin to sign in as, so set both `TROVE_ADMIN_EMAIL` and `TROVE_ADMIN_PASSWORD`
before the first `spring-boot:run`.

`TROVE_REGISTRATION_OPEN` seeds the bootstrapped household's `registrationOpen` flag exactly
once, at the same empty-`users`-table boot — it is **not** a live setting an existing
household can flip by restarting core (use `PUT /v1/admin/settings` as an existing admin for
that). Export `TROVE_REGISTRATION_OPEN=true` before the first boot on a fresh database if
you plan to run `e2e/onboarding.e2e.ts` (Detox), which exercises the sign-up form and needs
`create-account-link` to render — a fresh stack following this guide with no
`TROVE_REGISTRATION_OPEN` set will fail that spec on a closed-registration household (step
27, task 27.4/27.5).

## 3. Run the web client

```bash
cd apps/web
npm ci
npm run generate   # regenerate src/api/generated/schema.d.ts from openapi/trove-core.yaml
npm run dev
```

`vite` runs on port 5173 and proxies `/api` to `http://localhost:8080`
(`apps/web/vite.config.ts` `server.proxy`) — edit that target if `core` isn't on its
default port.

## 4. Run the mobile client

```bash
cd apps/mobile
npm ci
npm run generate
npm start   # expo start — the Metro bundler / dev-client picker
```

The Expo dev client reads `EXPO_PUBLIC_API_BASE_URL` (`apps/mobile/src/api/serverConfig.ts`,
`app.config.ts` `extra.apiBaseUrl`) as a **development-only** default so the connect screen
can be skipped; release builds ship it empty and the user supplies the server address by
hand. Point it at wherever `core` is reachable from the device/simulator, not `localhost`
(see the iOS/Android sections below for the two platform-specific cases).

### iOS

```bash
cd apps/mobile
EXPO_PUBLIC_API_BASE_URL=http://localhost:8080 npx expo run:ios --device "iPhone 17 Pro"
```

If Metro fails with a watchman `Operation not permitted` error, see the Troubleshooting
paragraph under Tests below (prefix the command with `CI=1`, at the cost of fast refresh).

`ios/` is git-ignored (root `.gitignore`), so a fresh checkout has no native project —
`expo run:ios` runs Expo's prebuild step first and generates it (and
`ios/Trove.xcworkspace`, which Detox's build step also expects, see Testing below) before
building and launching the simulator. On the iOS Simulator, `localhost` reaches the host
Mac directly, so the dev default above works unchanged.

**Xcode 27.** Two things make this work there (step 27.7): the Expo CLI finds the
simulator through Xcode 27's DeviceHub.app (Simulator.app is gone), and Expo SDK 58 gives
the app the UIScene life cycle the iOS 27 SDK requires by default — without it the app crashes
at launch with `UIScene life cycle is required for apps built with this SDK`. (On SDK 57 this
took an `enableSceneSupport` entry in `app.config.ts`; SDK 58 made it unnecessary.) To open a reset link by hand, use
`xcrun simctl openurl booted 'trove://reset-password?token=…'`; iOS 27 first asks
"Open in "Trove"?".

### Android

```bash
cd apps/mobile
EXPO_PUBLIC_API_BASE_URL=http://10.0.2.2:8080 npx expo run:android
```

`android/` is also git-ignored and generated by the same prebuild step. The Android
emulator does not share the host's `localhost` — from inside the emulator, the host
machine is reachable at `10.0.2.2`. The app reads this from the same
`EXPO_PUBLIC_API_BASE_URL` variable named above (`apps/mobile/src/api/serverConfig.ts`),
so pointing it at `http://10.0.2.2:8080` instead of `http://localhost:8080` is the only
change needed versus the iOS command.

### Files on mobile: import, export and share

The app handles files itself (`expo-file-system` and `expo-sharing`):

- **Import** (Transactions → Import → Choose a file) opens the system document picker
  (`File.pickFileAsync`) for a `.csv`, `.ofx` or `.qfx` statement; anything else is refused
  before upload.
- **Tax export** and **data export** show **Save or share** on a finished run. The bytes come
  through the app's authenticated API client, are written to the app's cache directory,
  handed to the system share sheet (save to Files / Downloads, or send to another app), and
  deleted when the share returns to Trove. A copy left by an app killed mid-share is removed at
  the next launch.
- On iOS the picker copies the chosen statement into the app's temporary folder; that copy is
  deleted once the upload finishes or fails. On Android the picker hands back your own file,
  which is never touched.
- **Receipts** picked from the iOS photo library are requested as JPEG (PhotoKit's
  "compatible" representation), so a HEIC photo uploads as JPEG.

To seed a file for a manual test:

```bash
# Android emulator: files land in Downloads, where the picker opens.
adb push core/src/test/resources/ofx/bank-xml.ofx /sdcard/Download/
printf 'Date,Description,Amount\n2026-01-02,Coffee Shop,-4.50\n2026-01-03,Grocery Market,-62.10\n2026-01-05,Payroll,1500.00\n' > statement.csv
adb push statement.csv /sdcard/Download/

# iOS simulator: photos (including HEIC) go into the Photos library.
# Any JPEG will do; a screenshot of the booted simulator is one to hand.
xcrun simctl io booted screenshot --type=jpeg receipt.jpg
sips -s format heic receipt.jpg --out receipt.heic
xcrun simctl addmedia booted receipt.heic
```

On iOS, statement files reach the Files picker by dragging them onto the Simulator window
(they are saved to Files → On My iPhone).

## 5. The Postman flow

For exercising the API directly (no client build at all), import the generated Postman
collection and environment and sign in — see
[docs/postman/README.md](https://github.com/Harnoor6693/trove/blob/canary/docs/postman/README.md)
for the full import, sign-in and "run from the terminal" (`newman`) steps.

## 6. Tests, and how to run each one

### Maven — unit and integration (the gate)

```bash
./mvnw --batch-mode -pl core -am clean verify checkstyle:check -Pintegration,coverage
```

See [CONTRIBUTING.md](https://github.com/Harnoor6693/trove/blob/canary/CONTRIBUTING.md#the-gate)
for what this checks and why both Maven profiles must run together.

### Playwright — web end-to-end

Needs a real running stack (no mock backend) — see
[apps/web/e2e/README.md](https://github.com/Harnoor6693/trove/blob/canary/apps/web/e2e/README.md)
for how to bring one up, then:

```bash
cd apps/web
npm run test:e2e
```

### Detox — mobile end-to-end (iOS and Android)

`e2e/onboarding.e2e.ts` needs `core` started with `TROVE_REGISTRATION_OPEN=true` on a
**fresh** database (see step 2's bootstrap table above) — it can't be flipped after the
fact except through `PUT /v1/admin/settings` as an existing admin.

```bash
cd apps/mobile
EXPO_PUBLIC_API_BASE_URL=http://localhost:8080 \
  npm run build:ios:debug && npm run test:e2e:ios -- --headless   # detox build / test --configuration ios.sim.debug
EXPO_PUBLIC_API_BASE_URL=http://10.0.2.2:8080 \
  npm run build:android:debug && npm run test:e2e:android # detox build / test --configuration android.emu.debug
```

(`apps/mobile/.detoxrc.js` — same `iPhone 17 Pro` simulator and `Pixel_5_API_36` AVD as
above. Run iOS Detox `--headless`, as CI does: Xcode 27 has no Simulator.app, and a headless
run never needs one. On a memory-tight Mac the Android emulator can kill itself with
`detected a hanging thread 'QEMU2 main loop'`; boot it headless before the test run with
`emulator -avd Pixel_5_API_36 -no-snapshot-save -no-window -no-audio` — Detox uses an
emulator that is already running.)

**Both platforms need `EXPO_PUBLIC_API_BASE_URL` set on the *build* command, not (only) on
`npm start`.** `app.config.ts` bakes `process.env.EXPO_PUBLIC_API_BASE_URL` into
`extra.apiBaseUrl` once, at native-build time — `./gradlew assembleDebug`
(`npm run build:android:debug`, into the APK's `assets/app.config`) and, the same way,
`xcodebuild` (`npm run build:ios:debug`, via the `EXConstants` pod's own script phase,
into `Trove.app/EXConstants.bundle/app.config`). Metro never re-serves it — restarting
Metro, even with `--clear`, has no effect on either platform. Confirmed on iOS by reading
the built bundle's own `app.config` (`.../Trove.app/EXConstants.bundle/app.config`, no
unzip needed — it's already an extracted directory) the same way `unzip -p
android/app/build/outputs/apk/debug/app-debug.apk assets/app.config` confirms it on
Android: `extra.apiBaseUrl` is `""` if the env var was absent from the build command,
which leaves `e2e/helpers.ts`'s `connectToDevServer()` dev-default pre-fill empty and every
Detox suite timing out at `login-screen` (step 27, bug found on Android Detox, task 27.4;
same root cause confirmed on iOS, task 27.5 — set it identically on both build commands).

A Debug Detox build still loads its JS from the Metro bundler at runtime (there is no
embedded-bundle hook in `.detoxrc.js` or `apps/mobile/e2e/jest.config.js`), so start it
first, same as [above](#ios), but with `CI=1`:

```bash
cd apps/mobile
CI=1 EXPO_PUBLIC_API_BASE_URL=http://localhost:8080 npm start   # not plain `npm start`
```

**Troubleshooting: Metro hangs / never answers `/status`, or `npm start` dies with a
watchman "Operation not permitted" error.** On a Mac where the `watchman` daemon runs
under `launchd` without macOS privacy (TCC) access to the folder you cloned into (common
when the repo lives under `~/Documents`), every `watchman watch-project` call under that
folder fails with `Operation not permitted`, and plain `npm start` (Metro's file watcher)
dies with it. `CI=1` makes the Expo CLI start Metro with `watch: false`
(`instantiateMetro.js`'s `isWatchEnabled`), which falls back to Metro's node crawler
instead of watchman — Detox does not need hot reload, so this is a safe substitute, not a
compromise. `npx expo run:ios` / `run:android` start their own Metro, so prefix them the same
way (`CI=1 EXPO_PUBLIC_API_BASE_URL=… npx expo run:ios --device "iPhone 17 Pro"`, as step
27.7 ran them). Without a watcher Metro does not see edits: restart it after changing JS or
adding a file. The permanent, owner-side fix is granting the `watchman` binary Full Disk
Access (System Settings → Privacy & Security → Full Disk Access). Do **not** run the
`watchman` CLI yourself to try to clear this (`watchman shutdown-server`, `watchman
watch-project`, `watchman watch-del-all`) — against a watch already wedged by the TCC
denial, these calls can hang indefinitely instead of returning an error.

### k6 — load testing (optional, not part of the everyday dev loop)

```bash
scripts/load-stack.sh up <workdir>   # a throwaway stack, e.g. https://trove.test:18443
scripts/load-stack.sh k6 <workdir>
```

`tests/performance/trove-load.js` is the k6 script (`k6 run`, run inside Docker via the
script above — no local k6 install, no Maven module). See the script's own header comment
for the equivalent hand-run `docker run ... grafana/k6:latest run` command if you don't
want to use `load-stack.sh`.
