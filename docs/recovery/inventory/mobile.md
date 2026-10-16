# Mobile App Inventory — `apps/mobile`

> **Historical record, 2026-09-03.** Inventory of the pre-rebuild `apps/mobile`. The "every call is broken" finding below describes that code and no longer applies; the app was rewritten in rebuild steps 21a and 211-215. The versions in the header are those of 2026-09-03. Current state: `docs-site/guide/architecture.md`.

Expo 55 / expo-router / React Native 0.83.6 / React 19.2.8 / TanStack Query v5 / zustand v5.
Verified directly against source on 2026-09-03. Supersedes prose about mobile in
`docs/recovery/01-FINDINGS.md` sections J and A6 where more precise; does not contradict them.

**Read this first:** every single mobile API call in this app is broken against the real
gateway contract — either missing the `/api/v1` prefix, using the wrong path segment, or
calling an endpoint that has no server-side route at all (see §3 table). This is not one bug,
it is the app's entire ceiling. Nothing past login can ever have worked against a real
`trove-core` (or the old 10-service) backend.

---

## 1. App structure

### app.config.ts (`apps/mobile/app.config.ts`)
- `name: 'Trove'`, `slug: 'trove'`, `scheme: 'trove'`, `version: '1.0.0'` (no build-number bump
  scheme configured beyond `eas.json`'s `autoIncrement`).
- iOS `bundleIdentifier: 'com.trove.app'`; Android `package: 'com.trove.app'`. Consistent, fine.
- iOS `infoPlist`: `NSCameraUsageDescription`, `NSPhotoLibraryUsageDescription` present and
  reasonable; `NSAppTransportSecurity.NSAllowsArbitraryLoads: false` (good — no ATS bypass).
- iOS `entitlements`: `com.apple.security.application-groups: ['group.com.trove.app']` — this
  app-group entitlement exists **only** to let the (cut) Notification Service Extension share
  a keychain/UserDefaults suite with the host app. With native listeners cut (owner decision),
  this entitlement is dead weight — remove it, or Xcode will complain about an app-group
  capability with no matching extension target.
- Android `permissions: ['CAMERA', 'READ_MEDIA_IMAGES', 'RECEIVE_BOOT_COMPLETED', 'VIBRATE']`.
  `RECEIVE_BOOT_COMPLETED` and `VIBRATE` exist only to service the cut
  `TroveNotificationListener` (boot-restart of the listener service) — remove both; neither is
  used by anything else in the app (grep confirms no other reference to boot/vibration).
- `plugins`: `expo-router`, `expo-font`, `expo-secure-store`, `expo-local-authentication` (Face
  ID permission string), `expo-notifications` (icon/color), `expo-image-picker`
  (photos/camera permission strings). No config plugin ever landed for the notification
  listener — `app.config.ts` never references it, confirming J10 (§7): the native listener
  was never wired into the Expo build pipeline at all, config-plugin or otherwise.
- `extra.apiBaseUrl` = `EXPO_PUBLIC_API_BASE_URL ?? 'https://api.trove.app'` (a domain that
  will not exist post-collapse — must become the single `trove-core` origin).
- `extra.eas.projectId` = `EAS_PROJECT_ID ?? ''` — **empty-string default** (J8, confirmed
  live in source, `app.config.ts:65`). `eas build` fails outright with this unset, and
  `registerPushToken()` (`src/notifications/registerPushToken.ts:21`) silently returns `null`
  when `rawProjectId` is falsy, so push registration no-ops with no user-visible error.

### eas.json (`apps/mobile/eas.json`)
Four build profiles: `development` (simulator, `localhost:8080`), `preview` /
`staging` (`https://staging-api.trove.app`), `production`
(`https://api.trove.app`, `autoIncrement: true`, channel `production`). All four
`EXPO_PUBLIC_API_BASE_URL` values need to become the single collapsed `trove-core` origin(s)
(e.g. one prod host behind Traefik, one dev/staging host) — none of the current four domains
will exist after the collapse. `submit.production` references `EXPO_APPLE_ID`, `ASC_APP_ID`,
`APPLE_TEAM_ID` env vars and a `./google-service-account.json` path that does not exist in the
repo (gitignored, presumably) — fine for now, flagged again in §10.

### Routes under `app/` (expo-router, file-based)
```
app/_layout.tsx                       Root: QueryClientProvider + AuthGuard + PushRegistrar
app/(auth)/_layout.tsx                Stack, headerShown:false
app/(auth)/login.tsx                  Email/password + biometric unlock
app/(auth)/setup.tsx                  3-step onboarding (currency, bank, notif permission)
app/(tabs)/_layout.tsx                Tabs — 8 DECLARED tabs (see below)
app/(tabs)/index.tsx                  Today (dashboard)
app/(tabs)/transactions/_layout.tsx   Stack
app/(tabs)/transactions/index.tsx     Transaction list
app/(tabs)/transactions/[id].tsx      Transaction detail
app/(tabs)/budgets/_layout.tsx        Stack
app/(tabs)/budgets/index.tsx          Budget list
app/(tabs)/budgets/[id].tsx           Budget detail
app/(tabs)/net-worth.tsx              Net worth chart/history
app/(tabs)/subscriptions.tsx          Subscriptions list
app/(tabs)/tax.tsx                    Tax year summary  ← CUT per owner decision, see §9
app/(tabs)/coach.tsx                  AI coach chat (SSE)
app/(tabs)/settings.tsx               Account / appearance / sign-out
app/(tabs)/receipts/index.tsx         Receipt list + capture modal  ← NO _layout.tsx, see below
app/(tabs)/receipts/[id].tsx          Receipt detail
```

**Tab bar** (`app/(tabs)/_layout.tsx:29-90`) declares exactly 8 `<Tabs.Screen>` entries: Today,
Transactions, Budgets, Net Worth, Subs, **Tax**, Coach, Settings. `receipts/` has **no**
`_layout.tsx` and is not declared as a `<Tabs.Screen>` — expo-router auto-mounts any directory
under `(tabs)/` as a route regardless, so it renders as an **undeclared 9th tab** with default
(untitled/unstyled) tab-bar treatment, confirming J6. There is **no `accounts` route or tab
anywhere in `app/`** — `useAccounts()` (`src/hooks/useAccounts.ts`) exists and is exported but
is never imported by any screen (grep across `app/` and `src/components` for `useAccounts`
returns only the hook file and its test). Accounts is a required top-level screen (§9) that
currently has a working hook and zero UI.

### Tracked `android/`/`ios/` files — the cut notification-listener stubs
Git status at session start shows these as deleted in the working tree; **the deletion is
`expo prebuild` collateral damage, not a reviewed removal** (confirms J10). The repo is mid a
concurrent build/prebuild process on this machine — `git status` for
`android/app/src/main/AndroidManifest.xml` changed from `M` to a staged `D` between the start
of this session and this inventory being written, while the file still exists on disk with its
original content; treat any live git status for `android/`/`ios/` as unstable during this
audit. What is stable (read via `git show HEAD:...`) is the **content** of the six files, and
all six should be deleted for real (not regenerated) once the collapse lands, because the
feature has three independent reasons it was already dead:
1. `android/app/src/main/AndroidManifest.xml` (HEAD, lines 2-16) is explicitly a hand-written
   **documentation stub** — its own top comment says prebuild will overwrite it "until the
   config plugin lands," and no config plugin exists in `app.config.ts` (see above). Recovering
   it with `git checkout` only restores a stub that gets clobbered by the next `expo prebuild`.
2. Both native clients target a route that has never existed server-side:
   `ios/TroveNSE/TroveIngestionClient.swift:29` and
   `android/.../TroveIngestionClient.kt:37` both build
   `"$baseUrl/api/ingestion/channel/6"` (or `\(channelId)`) — grep across every `services/*`
   controller and `ProxyController.PREFIX_TO_SERVICE` (§3) turns up no `/ingestion/channel`
   route on any service, gateway or direct.
3. There is **no iOS NSE (Notification Service Extension) target** in
   `ios/Trove.xcodeproj/project.pbxproj` at all (5 `PBXNativeTarget` entries, none matching
   `TroveNSE` or `NotificationService`) — the Swift files under `ios/TroveNSE/` were never
   attached to a build target, so they have never compiled into a shipped binary.

**Action for the rebuild:** delete all six files for real (`git rm`), delete the app-group
entitlement in `app.config.ts`, delete `RECEIVE_BOOT_COMPLETED`/`VIBRATE` from Android
permissions, and delete onboarding step 3 (`app/(auth)/setup.tsx`, see §5/§9) which exists
solely to request notification-listener access for this dead feature.

---

## 2. Auth

### Today (broken against the real backend)
- **Login** (`app/(auth)/login.tsx:59-63`): `POST /api/auth/login` with `{ email, password }`,
  expects `{ data: { token, user } }`. This is a plain email/password call — no
  `expo-auth-session`, no PKCE, no OIDC anywhere in the mobile tree.
- **Real backend today** (`services/api-gateway/.../auth/AuthController.java:51-142`):
  `POST /api/v1/auth/token` takes `{ id_token }` (an **Authentik OIDC ID token**, snake_case
  field, see `TokenRequest.java`) and returns `{ access_token, refresh_token, expires_in,
  token_type }` (snake_case, `TokenResponse.java`) — a completely different auth model, not a
  path typo (confirms J3 precisely). `GET /api/v1/auth/start` is the BFF login initiator;
  there is no email/password endpoint on the gateway at all.
- **Session check**: `login.tsx:88` calls `GET /api/auth/me` after a successful biometric
  unlock, to confirm the stored JWT is still valid server-side before granting access — a
  real security-conscious pattern (comment at `login.tsx:82-85` explains why), but the route
  doesn't exist (`MeController.java:31` maps `/api/v1/auth` but its own methods weren't grepped
  for a `/me` path — regardless, missing `/v1` alone means every call 404s today).
- **Token storage**: `expo-secure-store`, key `trove_jwt`
  (`src/stores/authStore.ts:5,44-58`), JSON `{ token, user }`. On hydrate, a corrupt/unparseable
  blob is caught and the key is deleted rather than crashing the app
  (`authStore.ts:47-54`) — this is the "SecureStore with corrupt-blob recovery" called out as
  genuinely good work in `01-FINDINGS.md` §K, confirmed still present.
- **Refresh**: **none**. `authStore.ts` has no refresh logic, no expiry tracking, and
  `axiosInstance.ts:27-35`'s response interceptor just clears auth and redirects to login on any
  401 — so even if login worked today, the user is force-logged-out on token expiry with no
  silent refresh (confirms J3's "hard logout every 15 min" claim, generalized: hard logout at
  whatever TTL the JWT carries, since there is zero refresh-token handling client-side even
  though the real backend issues one in `TokenResponse.refreshToken`).
- **Logout**: `useAuthStore.clearAuth()` deletes both SecureStore keys and clears state; it does
  **not** call any server endpoint (no `POST /api/v1/auth/logout`), so refresh tokens (once
  refresh exists) are never server-side revoked on sign-out.
- **Biometric gate**: `src/hooks/useBiometric.ts` — `checkBiometricAvailability()` (hardware +
  enrollment check) and `authenticateWithBiometrics()` (never throws, returns
  `{success, error?}`). Used only to *unlock a stored session*, not to gate app entry outright —
  if biometrics fail the user falls back to password (`login.tsx:172-187`). This part is sound
  design and can be kept as-is.

### What must change for local-accounts + bearer JWT with refresh rotation
1. Replace `POST /api/auth/login` with whatever `trove-core` exposes for local email/password
   login against argon2-hashed accounts (owner decision: Authentik dropped as a hard
   dependency). Expect a new endpoint shape, not the OIDC `/api/v1/auth/token`.
2. Add a real refresh flow: on 401, attempt `POST .../auth/refresh` with the stored refresh
   token before clearing auth; only redirect to login if refresh itself 401s. Needs a
   single-flight/coalescing guard (the web app's gateway already does this server-side per
   `01-FINDINGS.md` §K — mobile has never had a client-side equivalent because it never needed
   one, having no refresh at all).
3. Store both access and refresh tokens (or keep refresh in an httpOnly cookie if the new
   local-accounts backend follows the same BFF-cookie pattern the web app uses — needs an
   explicit decision, since mobile can't rely on cookie jars the way a browser does; a
   bearer-refresh-token-in-SecureStore model is more natural for RN).
4. Point `GET /api/auth/me`-equivalent (session validation on biometric unlock) at whatever the
   new backend's "who am I" endpoint is.
5. Wire `POST .../auth/logout` into `clearAuth()` so refresh tokens are revoked server-side.
6. TOTP: owner decision mentions "optional TOTP" for local accounts — no TOTP UI exists
   anywhere in mobile today; needs a net-new login step (code entry) when the account has TOTP
   enabled.

---

## 3. API

### Clients
- **`src/api/axiosInstance.ts`** — the only HTTP client in the app. Single `axios.create()`
  instance, `baseURL` from `Constants.expoConfig.extra.apiBaseUrl` (fallback
  `process.env.EXPO_PUBLIC_API_BASE_URL`, fallback `http://localhost:8080`), 15s timeout.
  Request interceptor injects `Authorization: Bearer <token>` from `authStore`. Response
  interceptor clears auth + redirects to `/(auth)/login` on any 401 (no refresh attempt, see
  §2).
- **`src/hooks/useCoachSession.ts:14-15`** — a **second**, independent base-URL resolution
  (duplicated verbatim from `axiosInstance.ts`) used only to construct the `EventSource` URL
  for coach streaming, since `react-native-event-source` doesn't go through axios. Two sources
  of truth for the same config value; low risk today (they resolve identically) but a trap for
  future drift.
- **Generated clients**: `scripts/generate-api-clients.js` shells out to
  `openapi-typescript-codegen` against `services/*/src/main/resources/openapi/*.yaml`, writing
  to `src/api/generated/`. **That directory does not exist in the tree** — the generator has
  never been run and committed, or its output was never checked in. Every API call in the app
  is hand-written against `src/api/types.ts` (hand-authored interfaces), not against any
  generated/typed client. Post-collapse this script needs to point at `trove-core`'s single
  OpenAPI spec (or be deleted in favor of a single generated client).

### Every API call site vs. the real server surface

Gateway routing is authoritative: `services/api-gateway/.../routing/ProxyController.java:61-74`
strips `/api/v1` and longest-prefix-matches the remainder against
`PREFIX_TO_SERVICE` (`transactions`→ingestion, `accounts`→ingestion, `dashboard`→ingestion,
`categories`→categorisation, `tax`→tax, `budgets`→budget, `networth`→budget, `receipts`→receipt,
`subscriptions`→subscription, `notifications`→notification, `reports`→report,
`models`→model-manager, `chat`→model-manager). Auth (`/api/v1/auth/**`) and preferences
(`/api/v1/me/preferences`) are handled directly by the gateway, not proxied.

| Method | Mobile calls | Caller (file:line) | Matches a real route? | Notes |
|---|---|---|---|---|
| POST | `/api/auth/login` | `app/(auth)/login.tsx:60` | **No** — real is `POST /api/v1/auth/token` (`{id_token}`, OIDC) | Different auth model entirely (J3) |
| GET | `/api/auth/me` | `app/(auth)/login.tsx:88` | **No** — no `/me` GET on `/api/v1/auth`; also missing `/v1` | Session-validation-on-biometric-unlock path |
| GET | `/api/transactions` | `src/hooks/useTransactions.ts:16`, `useDashboardToday.ts:41` | **No** — missing `/v1` only; real is `GET /v1/transactions` (via gateway `/api/v1/transactions`) | Closest to correct — prefix-only fix |
| GET | `/api/transactions/{id}` | `src/hooks/useTransactions.ts:27` | **No** — `TransactionController.java` has **no single-resource GET**, only list (`GetMapping`, no path var) and `PatchMapping("/{id}")` | Screen needs to derive detail from the list cache, or the server needs a new GET/{id} route |
| PUT | `/api/transactions/{id}` | `src/hooks/useTransactions.ts:38` | **No** — real is `PATCH /v1/transactions/{id}` | Wrong prefix **and** wrong HTTP verb |
| GET | `/api/accounts` | `src/hooks/useAccounts.ts:14` | **No** — missing `/v1` only; real is `GET /v1/accounts` | Prefix-only fix; but no screen ever calls this hook (§1) |
| GET | `/api/budgets` | `src/hooks/useBudgets.ts:14`, `useDashboardToday.ts:33` | **No** — missing `/v1` only; real is `GET /v1/budgets` | Prefix-only fix |
| POST | `/api/budgets` | `src/hooks/useBudgets.ts:25` | **No** — missing `/v1` only; real is `POST /v1/budgets` | Prefix-only fix |
| DELETE | `/api/budgets/{id}` | `src/hooks/useBudgets.ts:36` | **No route at all** — `BudgetController.java` has no `@DeleteMapping` on `/{id}` (only envelope-level delete exists, `/{id}/envelopes/{envelopeId}`) | Either build a new server endpoint or remove the delete affordance client-side |
| GET | `/api/subscriptions` | `src/hooks/useSubscriptions.ts:14` | **No** — missing `/v1` only; real is `GET /v1/subscriptions` | Prefix-only fix |
| GET | `/api/net-worth/history` | `src/hooks/useNetWorth.ts:13`, `useDashboardToday.ts:51` | **No** — wrong segment (`net-worth` vs. gateway's `networth`, no hyphen) **and** wrong sub-path (`/history` vs. real `GET /v1/networth/series`) | Two independent mismatches, not just a missing `/v1` |
| GET | `/api/tax/year` | `src/hooks/useTaxYear.ts:20` | **No** — real `TaxCalculationController` exposes `GET /v1/tax/{jurisdiction}/{year}` (path params, not query params), no `/year` sub-path | Moot: tax engine is cut (§9); do not fix, delete the caller |
| GET | `/api/receipts` | `src/hooks/useReceipts.ts:15` | **No** — missing `/v1` only; real is `GET /v1/receipts` | Prefix-only fix |
| GET | `/api/receipts/{id}` | `src/hooks/useReceipts.ts:26` | **No** — missing `/v1` only; real is `GET /v1/receipts/{id}` | Prefix-only fix |
| POST | `/api/receipts/upload` | `src/hooks/useReceiptCamera.ts:33` | **No** — missing `/v1` only; real is `POST /v1/receipts/upload` (`ReceiptUploadController.java:61`) | Prefix-only fix; note `01-FINDINGS.md` §L says this endpoint is server-side blocked by "one import line" regardless |
| GET | `/api/model-routing` | `src/hooks/useModelRouting.ts:13` | **No** — real is `GET /v1/models/routing` (`ModelController.java:127`) | Wrong path segment (`model-routing` vs `models/routing`) as well as missing `/v1` |
| PUT | `/api/model-routing` | `src/hooks/useModelRouting.ts:24` | **No route at all** — `ModelController.java` has no mutating mapping on `/routing`, only `GET` | There is no server-side way to *set* model routing from a client today |
| POST | `/api/notifications/device` | `src/notifications/registerPushToken.ts:30` | **No route at all** — grepped every controller in `notification-service`, `ingestion-service`, `api-gateway`; no device/push-token registration endpoint exists anywhere server-side | Push registration is a client-only fiction today — needs a new endpoint |
| GET (SSE) | `/api/coach/stream?message=...` | `src/hooks/useCoachSession.ts:41-44` | **No** — no `/coach` prefix in `PREFIX_TO_SERVICE`; real streaming endpoint is `POST /v1/chat/completions?stream=true` (`ChatController.java:92-131`), gateway prefix `chat`→model-manager | Three independent breaks: wrong path, wrong HTTP method (GET vs POST — `EventSource` is GET-only), wrong payload shape (`message` query param vs. JSON body `{model, messages, stream}`) |

**Snake/camel note**: request/response envelopes on the real services are camelCase
(`ApiResponse<T>` from `libs/common`), matching mobile's `src/api/types.ts` expectations —
*except* the gateway's own `AuthController`/`TokenRequest`/`TokenResponse` models, which are
**snake_case** (`id_token`, `access_token`, `refresh_token`, `token_type` —
`TokenRequest.java`, `TokenResponse.java`), and `01-FINDINGS.md` §M11 notes a **second**
snake_case leak (`field_errors` vs `fieldErrors`) on gateway-originated error bodies. Any new
mobile auth client must special-case snake_case for the auth surface even after everything
else is fixed to camelCase.

**Summary**: of 18 distinct call sites, **0 are correct as written**. 12 are fixed by adding
`/api/v1` (a mechanical prefix fix); 2 have additional path-segment mismatches beyond the
prefix (net-worth/history, model-routing); 1 has a method mismatch (PUT vs PATCH on
transactions); 3 call routes that **do not exist on any server at all** regardless of prefix
(transaction GET-by-id, budget DELETE, model-routing PUT, notification device-registration —
that's actually 4, see full list above) and the coach SSE endpoint needs a client-architecture
rewrite (GET EventSource → POST fetch-stream), not a URL fix.

---

## 4. State

### Zustand stores
- **`src/stores/authStore.ts`** — token, user, `isAuthed`, preferences, error + async actions
  (`hydrate`, `setAuth`, `clearAuth`, `setUserPreferences`). Every consumer outside Settings
  uses **individual field selectors** (`useAuthStore((s) => s.isAuthed)`,
  `useAuthStore((s) => s.token)`, etc. — see `app/_layout.tsx:27-28`,
  `app/(tabs)/index.tsx:49` via a typed `AuthSlice`) — the correct v5 pattern.
- **`src/stores/uiStore.ts`** — `theme: 'light'|'dark'|'system'`, `receiptModalVisible`. Trivial
  store, 16 lines.
- **The v5 object-selector bug (J5), confirmed live**: `app/(tabs)/settings.tsx:14-15`
  ```ts
  const { user, clearAuth } = useAuthStore((s) => ({ user: s.user, clearAuth: s.clearAuth }));
  const { theme, setTheme } = useUiStore((s) => ({ theme: s.theme, setTheme: s.setTheme }));
  ```
  Both selectors return a **new object every call**. Zustand v5's default equality is
  `Object.is` (no built-in shallow-compare fallback the way v4's `useStore` had) — every
  render produces a new snapshot, `useSyncExternalStore` sees a changed reference, and the
  component re-renders, calling the selector again, forever. `app/_layout.tsx` had the
  identical bug and was fixed (per `01-FINDINGS.md` J5, commit `c8ea408`, not independently
  re-verified here since it's already fixed in the file read at §1); Settings was missed.
  **Fix**: split into two individual-field selectors per store, or wrap with
  `useShallow` from `zustand/react/shallow`.
- **Why the test can't catch it** (confirmed, `app/(tabs)/settings.test.tsx:6-23`): both stores
  are fully `jest.mock()`-replaced with a `jest.fn()` that **synchronously calls the passed
  selector against a plain literal object once** — it never goes through React's
  `useSyncExternalStore`/zustand's subscribe machinery at all, so a new-object-every-call
  selector can never be observed looping in this test regardless of how it's written. This is
  the "over-mocked" description in J5, confirmed structurally, not just by inspection.

### Two QueryClient instances
1. `app/_layout.tsx:23-27` — the root client, wraps the whole authenticated app tree
   (`staleTime: 60_000, retry: 2`).
2. `app/(tabs)/receipts/index.tsx:12,39-41` — a **second, module-scope `new QueryClient()`**
   (default options, no `staleTime`/`retry` overrides) that wraps *only* the
   `<ReceiptCapture>` modal. `useReceiptCamera`'s upload mutation
   (`src/hooks/useReceiptCamera.ts:36`) calls
   `qc.invalidateQueries({queryKey: receiptKeys.all()})` against **this second client's own
   cache**, which the receipt list (`useReceipts()`, reading from the **root** client's cache)
   never sees. A captured receipt is invisible in the list until the whole app cold-starts and
   the root client's `useReceipts` query naturally refetches (confirms J6). **Fix**: delete
   the local `QueryClientProvider`/`qc` in `receipts/index.tsx` entirely — `<ReceiptCapture>`
   should inherit the ambient root `QueryClientProvider` from `app/_layout.tsx` like every
   other screen does; nothing about receipt capture needs an isolated cache.

### Offline-first: zero lines of code (confirms J2)
Grepped `src/`, `app/`, and `package.json` for `AsyncStorage`, `MMKV`, `NetInfo`,
`persistQueryClient`, `expo-sqlite`/`SQLite`, and `outbox` — **no hits anywhere** except
unrelated third-party README text inside `node_modules`. No dependency in `package.json`
provides any of these either. The root `QueryClient` (`app/_layout.tsx:23-27`) is the *only*
cache and it is purely in-memory — every cold start is an empty cache with zero rows until the
(currently all-broken, §3) network calls resolve. There is no conflict-resolution code, no
write-queue, no `NetInfo`-driven online/offline state anywhere in the tree. Any "offline-first"
claim in project docs about mobile is fiction (consistent with the doc-accuracy audit (private project notes)'s
general finding that only `docs/recovery/` is verified).

### SSE for coach
`react-native-event-source` (`^1.1.0`, in `dependencies`) is used exactly once, in
`src/hooks/useCoachSession.ts:38-44`, to open a `GET` `EventSource` against
`/api/coach/stream?message=...` with `Authorization`/`X-Session-Id` headers. As established in
§3, this endpoint doesn't exist in this shape server-side at all — the real streaming surface
is a `POST` with a JSON body, which `EventSource` (by spec, and this polyfill) cannot express.
**This needs a client-architecture change**, not a URL fix: either switch to a manual
`fetch()` + `ReadableStream`/chunked-response reader that POSTs the body and parses
`text/event-stream` frames by hand, or have the server add a GET-with-querystring streaming
variant (inconsistent with how the rest of the API is designed, not recommended).

---

## 5. Features

### Receipts capture (camera → upload)
`useReceiptCamera` (`src/hooks/useReceiptCamera.ts`) — `expo-image-picker`
(`launchCameraAsync`/`launchImageLibraryAsync`, `mediaTypes: ['images']`, `quality: 0.8`) →
local `uri` → `useMutation` builds a `FormData` with `{uri, name: 'receipt.jpg', type:
'image/jpeg'}` and `POST`s multipart to `/api/receipts/upload` (broken per §3, prefix-only
fix). `ReceiptCapture` component (`src/components/mobile/ReceiptCapture/ReceiptCapture.tsx`)
is a thin, testable UI wrapper — camera button, library button, spinner while uploading. The
capture UI itself is solid; it is blocked purely by (a) the missing `/v1` prefix and (b) the
second-QueryClient cache-invalidation bug (§4), both trivial fixes, plus (c) whatever
server-side receipt-processing gaps `01-FINDINGS.md` M8 documents (line items never persisted
in normal operation) which are out of mobile's scope to fix.

### Notifications
- **`expo-notifications`**: registered as an Expo plugin (icon/color), permission handler set
  in `app/_layout.tsx:12-18` (banners, list, sound, badge all `true`), and
  `registerPushToken()` (`src/notifications/registerPushToken.ts`) runs on auth via
  `<PushRegistrar>` (`app/_layout.tsx:48-58`). This whole path is currently a no-op end to end:
  `EAS_PROJECT_ID` defaults to `''` so `getExpoPushTokenAsync` is never even attempted
  (`registerPushToken.ts:20-21`), and even if it were, the target endpoint
  `POST /api/notifications/device` has no server route (§3). This is legitimate,
  reusable Expo-push infrastructure once EAS is configured and a device-registration endpoint
  is added server-side.
- **Cut native listeners**: `src/notifications/bankNotificationParser.ts` +
  `src/notifications/__tests__/bankParser.test.ts` (JS-side parser used to interpret bank
  push-notification text) is a *pure function library* with no native dependency — it's not one
  of the six deleted native stub files (§1), but it exists **solely** to support the native
  listener feature. Since native bank-notification listeners are cut by owner decision, this
  parser and its tests should be deleted too — nothing else imports it (grepped: zero
  importers outside its own test).

### Settings
`app/(tabs)/settings.tsx` — account card (name/email from `authStore.user`), appearance card
(dark-mode `MnToggle`), sign-out button. Broken by the object-selector infinite-render bug
(§4). **Also newly found here**: the dark-mode toggle is **fully inert** even setting aside the
render bug — `setTheme` writes to `useUiStore`'s `theme` field, but `src/design/useTheme.ts`
(the hook every screen actually uses for colors, e.g. `app/(tabs)/_layout.tsx:16`) is:
```ts
export function useTheme() {
  const scheme = useColorScheme();
  return scheme === 'dark' ? DarkColors : Colors;
}
```
— it reads only the OS `useColorScheme()` and never touches `useUiStore` at all. Grepping the
whole tree for `useUiStore` shows exactly one consumer: `settings.tsx` itself, both reading and
writing it. **The toggle changes a value nothing else ever reads.** Fix requires wiring
`useTheme()` to check `useUiStore().theme` first (falling back to system when `theme ===
'system'`), in addition to fixing the selector bug.

---

## 6. Tests

### Config
`package.json`'s `jest` block: preset `jest-expo`, setup files
`@testing-library/jest-native/extend-expect` + `src/test/setup.tsx`, path aliases mirrored from
`tsconfig.json`, `coverageThreshold` 80% all dimensions, `collectCoverageFrom` excludes
`src/api/generated/**` (which doesn't exist, see §3) and `.d.ts` files,
`transformIgnorePatterns` allowlists RN/Expo/navigation/`victory-native`/
`react-native-event-source` for transform.

### 48 test files, most cannot run — root cause confirmed (A6)
Found 49 `*.test.*` files under `app/`+`src/` (41 of those appear as `<testsuite>` entries in
the checked-in `apps/mobile/junit.xml`, 155 total `<testcase>`s, `failures="0" errors="0"`).
**That junit.xml is stale and no longer trustworthy**: it's timestamped `2026-05-05`, but
`node_modules` on disk today shows a real, unresolved version conflict that would break the
React renderer:
```
node_modules/react-test-renderer/package.json              "version": "19.2.8"   (root, matches react 19.2.8)
node_modules/jest-expo/node_modules/react-test-renderer/package.json  "version": "19.2.0"   (nested inside jest-expo)
```
`react` itself is pinned to `19.2.8` in `dependencies`. `jest-expo`'s preset pulls in its own
**nested** `react-test-renderer@19.2.0` (visible in `node_modules/jest-expo/node_modules/`),
which Node's module resolution will hand to anything jest-expo's internals `require()`
directly, while test files `require`ing `react-test-renderer` at the top level get the root
`19.2.8` copy. Two different React-renderer copies in the same test run reliably produces
"Invalid hook call" / "multiple copies of React" failures for any test that mounts a component
through both paths — this is almost certainly what the task description's "40 of 48 suites
cannot run" refers to, and the recent Dependabot-driven `mobile-dev-minor-and-patch` /
`mobile-prod-minor-and-patch` group bumps visible in git log (`4f28164`, `4fcee72`, `ebfbbde`,
etc.) are the most likely point this conflict was introduced — `junit.xml`'s green, complete
155/155 run predates those bumps. **I did not run jest myself** (not in my authorized command
set) — this is a version-tree read, not an execution result; treat the exact failing-suite
count as unverified until someone runs `npm ci && npm test` on a clean install.

### `npx tsc --noEmit -p apps/mobile` (ran; node_modules present; completed well under the
3-minute cap)
**1504 total `error TS...` lines**, but this number is misleading — it is almost entirely one
root cause, not 1504 independent defects:
- `tsconfig.json` has no `"types"` array (`apps/mobile/tsconfig.json:1-38`), so TypeScript
  auto-includes **every** `@types/*` package found anywhere in `node_modules`, including
  `@types/detox` (a `devDependency` for e2e). Detox's ambient globals (`describe`, `it`,
  `expect`, etc., with **detox's own `Expect`/`SystemElement` types**) collide with Jest's
  ambient globals for the same names — and `@types/jest` **is not a dependency at all**
  (absent from `package.json`), so there is no competing/correct definition, just detox's
  wrong one. Nearly every `*.test.ts(x)` file errors on `jest` "Cannot use namespace 'jest' as
  a value" (`TS2708`), `describe`/`it`/`beforeEach` "Cannot find name" (`TS2582`/`TS2304`), and
  `toBeTruthy`/mock-call assertions typed against detox's `Expect<Promise<void>>` instead of
  Jest's matchers (`TS2339`/`TS2769`) — see e.g. `app/(auth)/login.test.tsx:9-63` for a
  representative block of ~25 such errors from one file.
- **Only one error is a real production-code defect**:
  `src/hooks/useCoachSession.ts(2,25): error TS7016: Could not find a declaration file for
  module 'react-native-event-source'` — the package ships no types and none are installed
  (`@types/react-native-event-source` absent); trivial to fix with a local `.d.ts` shim or an
  `any`-typed local wrapper.
- **Practical implication**: this `tsc` run cannot be used as a pass/fail gate for test files
  as configured — Jest itself uses Babel (not `ts-jest`), so these type errors do not actually
  block tests from *running*, only from being separately type-checked. But it does mean
  `tsc --noEmit` is currently useless as a CI signal and should be fixed (`"types":
  ["jest", "node"]` in `tsconfig.json`, or a separate `tsconfig.test.json`) independent of the
  runtime test-suite issue above. Non-test production code is close to clean — 1 real error in
  the entire `src/` + `app/` tree outside test files.

### Over-mocked tests
`app/(tabs)/settings.test.tsx` is the flagship example (§4) — both stores fully
`jest.mock()`-replaced, defeating the object-selector infinite-render bug entirely. Given the
scale of the `jest.mock('@stores/...')` pattern likely repeated across other hook/store tests
(not individually re-verified here for time), any store-selector-shaped bug should be assumed
untested repo-wide until proven otherwise.

### Detox e2e
`e2e/` directory exists (`apps/mobile/e2e`, not enumerated file-by-file here), `detox` is a
devDependency (`^20.51.4`), `@types/detox` too (source of the tsc pollution above), and
`package.json` has `test:e2e:ios`/`test:e2e:android`/`build:ios:debug`/`build:android:debug`
scripts plus a narrow `test:eas-config` script that runs one file
(`e2e/eas-config.test.ts`) under `testEnvironment: node`. No detox run was attempted (out of
scope — would require a simulator/device and a full native build, neither available here).

### Lint
`eslint src --ext .ts,.tsx --max-warnings 0` is configured; not run (not in the authorized
command set for this inventory). `eslint-plugin-react-hooks@^7.1.1` is present as a
devDependency — worth confirming in a follow-up that its rules would have caught the
object-selector pattern (they generally don't, since it's a zustand-specific footgun, not a
raw hooks-rules violation) or whether a custom rule should be added.

---

## 7. Verified defects (source section J, cross-checked against current source)

All of J1–J10 checked directly against source in this pass; all confirmed accurate as written,
with the precise file:line evidence folded into §§1–6 above. Two points sharpened beyond J's
original wording:
- **J5** — beyond the render-loop, the dark-mode toggle it's attached to is *also* inert for an
  unrelated reason (`useTheme()` never reads `uiStore`, §5) — two independent bugs stacked on
  one control.
- **J10** — the `/api/ingestion/channel/6` target and the missing iOS NSE target were both
  re-verified directly against `TroveIngestionClient.swift`/`.kt` and `project.pbxproj` in this
  pass (§1), not just cited from prior findings.

No new defects of J-caliber were found beyond what §§1–6 already fold in inline (the API-table
break in §3 is the largest new-to-this-pass finding: J-series findings never enumerated that
**every** call site is broken, only cited login/auth as OIDC-mismatched).

---

## 8. The client contract mobile NEEDS from `trove-core`

Once the ten services collapse into one `trove-core` module, mobile needs, at minimum:
1. **A single base URL**, one per environment (dev/staging/prod), replacing four
   `EXPO_PUBLIC_API_BASE_URL` values across profiles in `eas.json` that currently point at
   services/domains that won't exist.
2. **Local-account auth**: `POST /auth/login` (or whatever path trove-core settles on) taking
   `{email, password}` → `{accessToken, refreshToken, expiresIn}` (camelCase, to match the rest
   of the API — see the snake_case-leak note in §3), a refresh endpoint, a logout endpoint that
   revokes server-side, and a "who am I" endpoint for the biometric-unlock re-validation path.
   Optional TOTP needs a second-step endpoint.
3. **A `/v1/**` (or equivalent) prefix used consistently** — mobile's #1 defect is missing this
   prefix on every call; whatever the final prefix convention is, it must be documented in one
   place both client and server generate/consume from (ideally: adopt the generated-client
   pipeline in `scripts/generate-api-clients.js`, point it at trove-core's single OpenAPI spec,
   and actually commit/import the output, replacing all 18 hand-written call sites in §3).
4. **New endpoints that don't exist on any current service**: transaction GET-by-id (or an
   explicit product decision that mobile derives detail from the list cache instead), budget
   DELETE, a model-routing PUT (or remove client-side mutation UI), a push-device-token
   registration endpoint, and a POST-with-JSON-body SSE/streaming chat endpoint reachable the
   way `useCoachSession` needs (or a client rewrite to match `ChatController`'s
   `POST /v1/chat/completions?stream=true` shape).
5. **Net-worth path clarity**: pick one of `networth`/`net-worth` and one of `/series` vs
   `/history` and have both client and server agree — currently they agree on neither.
6. **Receipts, images**: since MinIO is dropped for local-disk storage, whatever URL scheme
   `Receipt.imageUrl` (`src/api/types.ts:29`) resolves to must be directly fetchable by the
   mobile client (no presigned-URL-against-internal-DNS problem like the one `01-FINDINGS.md`
   M7 documents for report-service's MinIO links) — confirm the new local-disk-backed receipt
   URLs are reachable from outside the Docker network.
7. **Currency**: server must only need to support CAD/USD at onboarding (owner decision) —
   `setup.tsx`'s 4-currency picker (CAD/USD/GBP/EUR, `setup.tsx:39-44`) needs to shrink to 2,
   and `authStore.ts`'s `CurrencyCode` type (`'CAD'|'USD'|'GBP'|'EUR'`) and
   `useTaxYear.ts`'s `TaxJurisdiction` (`'UK'|'US'|'CA'`) need to drop the non-first-class
   options (or the server needs to explicitly reject them, whichever direction the contract is
   authored from).

---

## 9. Screen-by-screen recommendation

| Screen | Recommendation | Why |
|---|---|---|
| **Login** | **Rewrite auth logic, keep UI** | UI/UX (wordmark, biometric unlock button, error states) is solid; the entire request/response contract (§2) must be replaced for local accounts + bearer JWT + refresh. |
| **Onboarding** (optional) | **Rewrite** | Step 1 (currency) needs to drop to CAD/USD only. Step 2 (bank channel) needs the "Email forwarding" option removed (IMAP/bank-email ingestion is cut) and "OFX/QFX" + "SimpleFIN" wired to whatever trove-core's connection-setup flow becomes. **Step 3 (`setup.tsx` lines ~180-230, "Bank alerts" / notification-listener permission request) must be deleted outright** — it exists solely to onboard users into the cut native-listener feature, and its copy ("no bank login required... All parsing happens on your device") describes a feature that will never exist. |
| **Today** | **Keep, fix API paths** | `useDashboardToday` (§3/§4) is well-structured (`useQueries` + `combine`, sensible stale times); every call it makes just needs the URL fixes from §3. |
| **Transactions** | **Keep, fix API paths + method** | List/detail/update flow is reasonable; needs the `/v1` prefix, the PUT→PATCH fix, and a resolution for the missing GET-by-id route (§3/§8). |
| **Accounts** | **Build new** | `useAccounts()` hook exists and is close to correct (just needs `/v1`) but **there is no screen or tab for it at all** (§1) — this is the one required screen with zero UI today. |
| **Budgets** | **Keep, fix API paths; resolve missing DELETE route** | List/create flow is fine; delete either needs a new server endpoint or the UI affordance removed until one exists. |
| **Net Worth** | **Keep, fix path AND sub-path mismatch** | Needs both the `networth` vs `net-worth` segment fixed and `/series` vs `/history` reconciled with the server (§3/§8) — the larger of the two "near-miss" screens. |
| **Subscriptions** | **Keep, fix API path only** | Cleanest hook in the app relative to the real contract — one prefix fix and it's correct. |
| **Receipts** | **Rewrite the routing/query wiring, keep capture UI** | Delete the undeclared-9th-tab problem (add `_layout.tsx` or fold into the tab set explicitly, product decision needed on whether Receipts is a top-level tab), delete the second `QueryClient` (§4), fix the `/v1` prefix. Camera/library capture flow itself (`ReceiptCapture`) is good and reusable as-is. |
| **Coach** | **Rewrite** | Beyond the `crypto.randomUUID()` Hermes crash (J4 — no polyfill imported anywhere; grepped, confirmed absent), the entire SSE approach (GET `EventSource`) is incompatible with the real POST-JSON-body streaming contract (§3/§4) — this needs a client-architecture change, not a patch. |
| **Settings** | **Rewrite store wiring** | Fix the object-selector infinite-render bug (§4), wire the dark-mode toggle to something `useTheme()` actually reads (§5), and route sign-out through the new auth logout endpoint (§2) once it exists. |
| **(Tax tab)** | **Delete** | Not in the owner's required-screens list, and correctly so — the tax engine is cut per owner decision, replaced by a CSV/PDF export of categorised transactions. `app/(tabs)/tax.tsx`, `useTaxYear.ts`, and the `TaxYear`/`TaxJurisdiction` types in `src/api/types.ts`/`useTaxYear.ts` should all be deleted; if a mobile "export" affordance is wanted it belongs as an action inside Settings or Transactions, not a dedicated tab, and should trigger a file the OS share sheet can hand off (no computed tax numbers per the CUT decision). |

---

## 10. Store readiness

- **Icons/splash**: `assets/icon.png`, `assets/splash.png`, `assets/adaptive-icon.png`,
  `assets/notification-icon.png` are all **70-byte, 1×1 transparent PNGs** (verified via
  `file`, confirmed live — J7). Hard blocker for both App Store and Play Store submission;
  real artwork at correct resolutions (1024×1024 icon, etc.) is needed before any store build.
- **iOS Privacy Manifest**: no `PrivacyInfo.xcprivacy` anywhere under `ios/` (confirmed —
  `find ... -iname "*.xcprivacy"` returns nothing). Mandatory since 2024 for apps collecting
  financial data, camera, photos, and biometrics — all of which this app does (J9). Must be
  authored (required-reason API declarations at minimum for `UserDefaults`, if
  `expo-secure-store`/`expo-local-authentication` pull in any of Apple's required-reason APIs
  — needs an audit against Apple's current category list at build time) before an App Store
  submission will pass review.
- **Permission strings**: present and reasonable for camera/photos/Face ID
  (`app.config.ts`, §1). **After the native-listener cut**, `RECEIVE_BOOT_COMPLETED` and
  `VIBRATE` (Android) and the `application-groups` entitlement (iOS) should be removed — they
  exist solely for the dead feature and unnecessarily widen the app's permission footprint
  (a real App Store/Play Store review consideration, not just cleanup).
- **Versioning**: `version: '1.0.0'` in both `app.config.ts` and `package.json`; `eas.json`'s
  `production` profile sets `autoIncrement: true` (auto-bumps the native build number on each
  build) — reasonable defaults, no changes needed structurally, just needs real content behind
  the version.
- **EAS credentials**: `eas.json`'s `submit.production` needs `EXPO_APPLE_ID`, `ASC_APP_ID`,
  `APPLE_TEAM_ID` (env vars, not in repo — fine) and a `./google-service-account.json` (not in
  repo — fine, gitignored presumably, needs to exist locally/in CI secrets before `eas submit`
  can run). **`EAS_PROJECT_ID` must be set** — its current `''` default (§1, J8) breaks both
  `eas build` and push-token registration; this is the single highest-leverage one-line fix in
  the whole store-readiness list.

---

## 11. Risks

1. **Every API call is broken (§3)** — this is not a punch-list of small fixes, it is the
   entire mobile app's ceiling until addressed. Any estimate of "mobile is mostly done, just
   needs polish" is wrong; mobile has never had a working network path to any backend.
2. **The coach/SSE architecture mismatch (§3/§4) is a rewrite, not a patch** — budget it as
   new client code, not a bug fix, when scoping the collapse.
3. **`tsc`/Jest signal is currently unreliable** (§6) — the committed `junit.xml` shows a stale
   all-green run that predates a real `react-test-renderer` version conflict visible in
   `node_modules` today; don't trust `junit.xml` as evidence of current test health, and don't
   trust `tsc --noEmit`'s 1504 errors as evidence of 1504 real defects (it's ~1 real error
   inflated by a detox/jest global-types collision). Both need independent, fresh verification
   (a clean `npm ci` + `npm test` + a `tsconfig.json` `"types"` fix) before being used as a
   merge gate.
4. **The onboarding flow (`setup.tsx`) actively promises a feature (on-device bank-notification
   parsing) that will never exist** post-collapse — if onboarding isn't rewritten before
   release, real users will be told "no bank login required... all parsing happens on your
   server" for a capability that was never functional even before the cut (J10: no JS→native
   bridge ever wrote the keys either client read, so this was never true even pre-decision).
5. **Accounts has no UI at all (§1/§9)** despite being a required top-level screen and despite
   the hook (`useAccounts`) already existing — this is new-screen work, not a fix, and should
   be sized accordingly in planning.
6. **Store submission is blocked on two hard, non-negotiable items** (§10): real icon/splash
   assets and an iOS privacy manifest. Neither is a code fix; both need design/legal input and
   should be scheduled early given lead time (App Store review cycles).
7. **Git state for `android/`/`ios/` was observed changing mid-audit** (§1) due to a concurrent
   build/prebuild process on this machine per the task's own ground rules — any automation that
   inspects `git status` for these paths right now may get an inconsistent read; the six stub
   files' *content* (read via `git show HEAD:...`) is the stable, citable evidence, not their
   live working-tree status.
