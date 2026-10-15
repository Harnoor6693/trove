# Trove — Monolith Collapse Plan (`trove-core`)

**Status:** architect's execution plan · **Baseline:** `9ad1b5f` (main) / `6a8b3cf` (rebuild) · **Date:** 2026-09-03
**Inputs:** `docs/recovery/README.md`, the critical-path list (private project notes), `04-REVIEWS-AND-SCOPE.md` (Architect + Security), all 18
files under `docs/recovery/inventory/`. Where inventories disagreed, source was read and the resolution is stated inline.

Owner decisions are fixed and are not re-argued here: one JVM (`trove-core`) + Postgres; tax engine cut, replaced by a
categorised-transaction CSV/PDF export with no computed tax numbers; CAD and USD first-class per user; public-internet
deployment behind Traefik with TLS/CSRF/rate-limits/HSTS/generated secrets; web + Expo mobile both shipped; MinIO,
Authentik-as-hard-dependency, PaddleOCR, IMAP/bank-email, native listeners, plugin architecture all dropped; AI is a
**per-user** OpenAI-compatible endpoint (each household member configures their own base URL + key), off until
configured, governed by one outbound-URL policy shared with SimpleFIN/webhooks (https or explicit per-user LAN opt-in,
DNS-pinned resolution, private-range blocked unless allowed, per-user egress log — §1.6/§6). Four further decisions,
made after the first draft of this plan, are equally fixed: **(a)** tenancy is **one shared household ledger per
install** — a `households` table and `household_id` scoping on every ledger-owned table, one user belongs to exactly
one household, an admin role is household-scoped (§1.5/§3); **(b)** mobile v1.0 is **sideload-only** — a signed APK per
release plus TestFlight/AltStore, no Play/App Store submission (§7 step 19/21/22); **(c)** **no push notifications**
until a direct APNs/FCM path exists — no Expo relay; v1 ships an in-app notification inbox plus client-local
notifications only, and every Expo-push surface named in an earlier draft of this plan is cut (§2.8/§4.9/§6 D3); **(d)**
covered by the per-user AI-endpoint line above.

Conventions used below: **KEEP** = move with package rename and mechanical import fix only; **REWRITE** = move, then the
named change is mandatory in the same commit; **NEW** = no source exists, write it; **DELETE** = remove, never port.
"Identity" always means the `sub` claim of the validated JWT (`TroveUserDetails.getUserId()`); a client-supplied
`X-User-Id`/`X-Trove-User-Id` header is banned monolith-wide and an ArchUnit test enforces it (§9).

---

## 1. Target architecture

### 1.1 Containers

| Container | Image | Publishes | Role |
|---|---|---|---|
| `traefik` | traefik:v3 | `80`, `443` (only container that publishes) | TLS termination (ACME HTTP-01, optional DNS-01), `security-headers`, `compress`, `ratelimit` middleware, routes `Host(${TROVE_DOMAIN})` → `web` for `/` and → `trove-core:8080` for `/api` |
| `socket-proxy` | tecnativa/docker-socket-proxy | none (`internal: true` network) | Gates Traefik's Docker API access — copied verbatim from `infra/full-stack/docker-compose.yml:19-41` |
| `web` | `apps/web/Dockerfile` (nginx) | none | Serves the SPA; `nginx.conf` proxies `/api/` → `trove-core:8080` (one-line change: `set $api_gateway "trove-core";`) |
| `trove-core` | `core/Dockerfile` | none (`8080` app, `9090` management, both internal) | The monolith; `mem_limit: 2g`, `-Xmx1536m -XX:+UseZGC` (tune after load test, §9) |
| `postgres` | pgvector/pgvector:pg16 | none | One catalog `trove`, one Flyway history (§3) |
| `ollama` | ollama/ollama | none; `profiles: ["ai"]` | Optional local model; `trove-core` talks to `http://ollama:11434/v1` only if the user configures it |
| `prometheus`, `grafana` | — | none; `profiles: ["monitoring"]` | Optional; dashboard trimmed to real metrics (§5.9) |

Dropped containers: `valkey` (refresh tokens and rate-limit buckets move in-process/Postgres), `minio`, `authentik-server`,
`authentik-worker`, `litellm-gateway`, `mailhog`, `loki`, and all nine per-service JVMs.
Volumes: `postgres_data`, `trove_data` (mounted at `/data`: `receipts/`, `exports/`, `uploads/`), `traefik_acme`.

### 1.2 The `trove-core` Maven module — `core/` (chosen over `services/trove-core`)

Path: **`core/`** at repo root, artifactId `trove-core`, packaging `jar`, the **only** module in the reactor after step 24
(`pom.xml` `<modules>` shrinks from 24 entries to `core`). Reasons: (a) a directory named `services/` with one child
asserts a topology that no longer exists and invites a second service; (b) every CI/Dockerfile path becomes
`core/target/trove-core-*.jar`; (c) `apps/` + `core/` reads as "clients + backend", which is the real shape.
`apps/web` is removed from the Maven reactor (H10: `mvnw package` currently runs vitest) — the web build is npm-only.

Boot: Spring Boot 4.1 (as today), Java 21, Jackson 3 (`tools.jackson.*`) only — the explicit
`com.fasterxml.jackson.core:jackson-databind` dependencies in `services/ingestion-service/pom.xml:64-67` and siblings are
not carried forward; annotations stay `com.fasterxml.jackson.annotation.*` (unchanged in Jackson 3).
Dependencies kept from the ten poms: web, security, data-jpa, validation, actuator, flyway, postgresql, jjwt 0.12,
bucket4j-core (not bucket4j-redis), shedlock-spring + shedlock-provider-jdbc-template, thymeleaf + spring-mail, itext
kernel/layout, thumbnailator (receipts), micrometer-registry-prometheus, logstash-logback-encoder, lombok; test:
testcontainers-postgresql, wiremock, archunit (new). Dropped: spring-data-redis, lettuce, bucket4j-redis, minio,
spring-boot-starter-aspectj, oauth2-client stays but conditional at runtime, apns/fcm clients.

### 1.3 Package layout (`core/src/main/java/finance/trove/…`)

```
finance.trove.app            TroveApplication, SecurityConfig, WebConfig(CORS/Jackson), SchedulingConfig,
                             AsyncConfig, HttpClientConfig, StorageConfig, ratelimit/, audit/ (incl. SecurityEvent),
                             bootstrap/ (first run + first household)
finance.trove.common         dto/, exception/, domain/ (Money, CurrencyCode, Jurisdiction, PagedResult),
                             observability/, crypto/ (FieldEncryptionService), storage/ (LocalDiskStorage), events/,
                             net/ (OutboundUrlPolicy, pinned DnsResolver), repository/ (UserScopedRepository<T>),
                             time/ (UserClock)
finance.trove.auth           jwt/, local/ (argon2id, TOTP, lockout via login_attempt), oidc/ (optional),
                             session/ (refresh tokens), AuthController
finance.trove.households     Household, HouseholdRepository, invites/ (admin invite links)
finance.trove.users          User, UserRepository, UserService, preferences/ (region, locale, timezone, onboarding),
                             admin/, export/ (data export + account deletion, gap P2-16)
finance.trove.accounts       ConnectedAccount, AccountController, AccountService, simplefin setup-token exchange
finance.trove.transactions   Transaction, TransactionController, TransactionService, dashboard/
finance.trove.ingestion      BankSource SPI, RawTransaction, SourceRegistry, SyncOrchestrator, dedup/, ImportController,
                             simplefin/, ofx/, csv/
finance.trove.categorisation Category, MerchantCategoryMap (+user rules), CategoryOverride, CategorisationService, ai fallback
finance.trove.budgets        period/, envelope/ (+spend writer), goal/, rollover/
finance.trove.networth       Asset, Liability, NetWorthSnapshot, NetWorthController, per-currency aggregation
finance.trove.subscriptions  detection/, subscription/, alert/, scheduler/
finance.trove.notifications  channel/ (email only — no push, owner decision), inapp/, preference/, send/, webhook/, digest/
finance.trove.receipts       receipt/, upload/, pipeline/, extraction/ (heuristic + vision), matching/, taxsplit/
finance.trove.ai             client/ (OpenAiCompatibleClient — the ONLY class that opens a connection to an AI endpoint),
                             settings/, chat/, insight/, audit/, privacy/
finance.trove.taxexport      TaxLineMapping (CA/US), TaxExportRun, CsvExportBuilder, PdfExportBuilder, TaxExportController
```

Rule enforced by ArchUnit (§9): every other package may depend on `common` and `users` (for `UserSettingsService`);
domain packages talk to each other through `common.events` or through a small set of named service interfaces
(`TransactionQueryService`, `CategorisationService`, `EnvelopeSpendService`, `NotificationService`, `AiGateway`).

### 1.4 How web and mobile reach it under `/api/v1`

`server.servlet.context-path: /api`. Every controller keeps its existing `@RequestMapping("/v1/…")`; the gateway's three
controllers (`/api/v1/auth`, `/api/v1/auth/me`, `/api/v1/me/preferences`) drop their `/api` prefix. Result: the wire paths
are exactly what `apps/web/src/api/*.ts` (baseURL `/api`) and nginx (`proxy_pass http://$api_gateway:8080$request_uri`)
already assume, and the OAuth2 endpoints Spring generates land under `/api/oauth2/authorization/oidc` and
`/api/login/oauth2/code/oidc`, so nginx's extra `/oauth2/`, `/login/oauth2/`, `/logout` locations are deleted. Management
port `9090` has no context path and is never routed by Traefik. Mobile uses one base URL `https://${TROVE_DOMAIN}` and
calls `/api/v1/…` with a bearer token; the four `EXPO_PUBLIC_API_BASE_URL` profile values collapse to dev/prod.

### 1.5 Auth model

**Tenancy (owner decision, 2026-09-03):** ONE SHARED HOUSEHOLD LEDGER per install. `households(id, name, created_at)`;
every user belongs to exactly one household (`users.household_id NOT NULL FK`, set at registration/invite-accept, never
changed by the user). Every ledger-owned table (`connected_account`, `transaction`, `category` user rows, `budget_period`,
`envelope`, `savings_goal`, `net_worth_snapshot`, `asset`, `liability`, `receipt`, `detected_subscription`,
`subscription_alert`, `tax_export_run`) additionally carries `household_id UUID NOT NULL REFERENCES households(id)`
alongside `user_id` (which becomes "created/owned by" for audit, not the scoping column); every repository query and the
`UserScopedRepository<T>` base (below) filters by `household_id`, not `user_id`. Per-user-only tables (auth, sessions,
`ai_settings`, `notification_preference`, `push_device`→dropped, `webhook_subscription`, `security_event`) stay scoped by
`user_id`. `household.role` per member is `MEMBER` or `ADMIN` (at least one ADMIN per household, enforced on
demote/remove); admin rights are household-scoped, never cross-household. `AccessControlIT` (§9 risk 2) is corrected to
assert isolation **between households** and full mutual visibility **within** one — see §3 V2/V3, §9.

- **Identity store**: `users` (+ `household_id`, `role`) + `local_credential` (argon2id, `m=65536 KiB (64 MiB), t=3, p=1,
  16-byte salt, 32-byte hash`, params stored in the PHC string so future re-tuning re-hashes on next login) +
  `totp_credential` (RFC 6238, secret AES-GCM-encrypted with `TROVE_ENCRYPTION_KEY`, 10 SHA-256-hashed single-use recovery
  codes) + `oidc_link` + `login_attempt` (NEW, gap P2-5) + `security_event` (NEW, gap P2-17, see §6).
- **Password policy**: 12–128 chars, no composition rules, checked against a bundled offline top-10k list (no HIBP
  network call by default); login is enumeration-safe — unknown email verifies against a fixed dummy Argon2 hash so
  timing and response body are identical to a wrong password.
- **Login lockout / brute force** (NEW, gap P2-5, extends G7/G8 — `RateLimitFilter.java:80-81` today takes
  `X-Forwarded-For` unconditionally and fails open on error; `RateLimitFilterIT`/security-review reads this as a
  standing gap on the public-internet decision): client IP for every limiter and for `security_event`/`audit_log` comes
  from `request.getRemoteAddr()` **after** a `RemoteIpValve`/`ForwardedHeaderFilter` configured with
  `internal-proxies`/`trusted-proxies` = the compose network only — raw `X-Forwarded-For` is never read by hand again.
  `login_attempt(email_hash, ip, success, at)`; 5 failed attempts / account / 15 min → soft lock with exponential
  backoff (recorded on `local_credential.locked_until`/`failed_attempts`); 20 failed attempts / IP / 10 min → 429.
  `/v1/auth/**` limiters are **fail-closed** (a limiter error rejects the request, not "failing open" as today). The
  response never distinguishes locked-out from wrong-password.
- **Tokens**: `JwtIssuerService` (HS256, 15-min access, 24-h refresh) remains the sole `Jwts.builder()` caller, but every
  issued token now carries a `typ` claim (`access`, `totp_challenge`) and an `iss`/`jti`; `JwtAuthenticationProvider`
  requires `typ=access` for every protected route, closing a type-confusion hole where the existing verifier accepts
  **any** HS256 token with a `sub` — including a 60-s TOTP challenge token — as a full access token
  (`JwtAuthenticationProvider.java:52-65`, `CookieJwtAuthFilter.java:54-61`; blocker, second factor is bypassable as
  specified otherwise). `JwtTypeConfusionIT`: a `totp_challenge` token on `GET /v1/auth/me` → 401. Refresh tokens move
  from Valkey to the `refresh_token` table (hash only, `household_id` NOT carried — refresh is per-user), rotated on
  every use, family-revoked on reuse of a **stale** token; a reuse of the immediately-preceding token within a 30-s
  grace window (two browser tabs, or web+one retry racing) returns the same successor instead of revoking the family —
  without this, ordinary concurrent tab usage force-logs-out the household (major; `axios` today coalesces refreshes
  only per-tab, mobile has no refresh flow to reuse). `RefreshTokenIT`: concurrent double refresh → both 2xx, same
  successor; reuse of an older token → family revoked.
- **Web**: `POST /v1/auth/login` with header `X-Trove-Auth-Mode: cookie` sets `__Host-trove_access` and
  `__Host-trove_refresh` (`HttpOnly; Secure; SameSite=Lax; Path=/`) via the existing `AuthCookies`; body carries no refresh
  token. CSRF for cookie-authenticated requests: `CookieCsrfTokenRepository.withHttpOnlyFalse()` with
  **`setCookiePath("/")`** (not the `/api` context-path default — the SPA is served at `/`, so a context-path-scoped
  cookie is invisible to axios and every cookie-mode mutation 403s as specified) and
  **`csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())`** (the Spring-Security-7 default `Xor...Handler` rejects
  the raw cookie value axios echoes). A `CsrfCookieFilter` runs after the JWT filter on every request and calls
  `csrfToken.getToken()` so the cookie is materialised for anonymous callers too — Spring 6/7's CSRF token is deferred
  and `login`/`register` are themselves CSRF-protected mutating POSTs, so without this filter the very first login 403s
  before a token cookie ever exists (blocker: §1.5/§5.1/§5.10 as originally drafted cannot log in). **Plus** the existing
  `CsrfRequiresHeaderFilter` (`X-Requested-With`), enforced only when the request authenticated via the cookie
  (`CookieJwtAuthFilter` sets a request attribute `trove.authSource=COOKIE|BEARER`) — as written today the filter
  rejects every mutating request unconditionally, which would 403 mobile's bearer-only `POST /v1/auth/login` at step 21
  (major). Bearer-authenticated requests skip both. `CsrfIT`: anonymous GET sets `XSRF-TOKEN` at `Path=/`; cookie POST
  with header → 200, without → 403; bearer POST without `X-XSRF-TOKEN` → 200.
- **Mobile**: same login endpoint without the header → `{accessToken, refreshToken, expiresIn, user:{id,email,displayName,
  totpEnabled}}` (the user object is included so mobile's session store, which requires `user.id`/`user.name` today, has
  something to persist without a second round trip) `POST /v1/auth/refresh` with JSON body rotates (single-flight —
  one in-flight refresh promise, `_retried` guard, refresh call itself excluded); tokens in `expo-secure-store`;
  `POST /v1/auth/logout` revokes server-side.
- **TOTP** (fleshed out per gap P2-6 — "optional TOTP" had no enrolment/step-up/recovery design): enrolment requires a
  fresh password re-auth and one valid code before `totp_credential.enabled=true`; the 10 recovery codes are shown once,
  SHA-256-hashed at rest, single-use. Step-up (password + current TOTP code) is required for: disabling TOTP, password
  change, adding a bank connection, `/v1/me/export`, `DELETE /v1/me`. If enabled, login returns `{challengeToken}` (a
  60-s JWT, `typ=totp_challenge`, `amr=pwd`) and the client calls `POST /v1/auth/login/totp {challengeToken, code}`
  (code or a recovery code); `JwtAuthenticationProvider` rejects a `totp_challenge` token on every other route (see
  Tokens above). Admin can clear a household member's TOTP (writes `security_event.TOTP_DISABLE`, actor ≠ subject);
  `trove admin disable-totp <email>` CLI escape hatch for the last admin locked out.
- **OIDC (optional)**: `trove.auth.oidc.enabled=true` + issuer-uri/client-id/secret registers one Spring OAuth2 client
  named `oidc`; `TroveOidcSuccessHandler` finds the user by `(provider, sub)` in `oidc_link`; when no link exists it
  auto-links to an existing local account **only if** the ID token's `email_verified` claim is `true` **and** the email
  matches — auto-linking on email alone (no `email_verified` gate) is an account-takeover: anyone who controls an IdP
  account with an unverified email matching a household member's logs in as that member with full ledger access
  (major; `TroveOidcSuccessHandler.java:83-88` reads only `getEmail()` today). When `email_verified` is false or absent,
  the handler redirects to a "link this provider" step requiring the local password instead of issuing cookies
  immediately; `email_at_link` is recorded on the `oidc_link` row. When no local account matches at all, registration
  policy applies (open → creates one in the caller's household via invite context; closed → 403). State/PKCE are kept in
  a **NEW** `finance.trove.auth.oidc.CookieAuthorizationRequestRepository` (AES-GCM with `TROVE_ENCRYPTION_KEY`, 5-min
  max-age, `__Host-trove_oidc`) so the app stays `STATELESS` — this class does not exist in Spring Security and must be
  written, not "kept" as originally listed (§2.1); the current success handler's `request.getSession(false)` usage is
  deleted with it. `AuthCookies` changes `SameSite=Strict` → `Lax` (REWRITE, not an addition — needed so the IdP
  redirect chain lands with cookies present) with an `AuthCookiesTest` assertion. `OidcLinkIT`: unverified email →
  no auto-link, "link account" response. Authentik is just one possible issuer.
- **First run / admin / invites** (gap P2-7): `FirstRunBootstrap` creates the first household + its first ADMIN user from
  `TROVE_ADMIN_EMAIL`/`TROVE_ADMIN_PASSWORD` when `users` is empty (also printed as a one-time setup URL for a headless
  first boot). Registration is **closed** by default (`trove.auth.registration-open=false`); a household admin invites
  members via single-use, 72-hour invite links (`POST /v1/admin/invites`, no SMTP required — copy/paste the URL) that
  pre-bind `household_id`; `PUT /v1/admin/settings {registrationOpen}` optionally opens self-serve signup into the
  admin's own household. `AccessDeniedException` on an admin route returns the JSON `ApiResponse` shape, not a bare 500
  (§5.3). This is the register/invite endpoint that unblocks the four dormant test surfaces (`tests/integration`,
  `tests/performance`, `security.yml` runtime assertions, `smoke-test.sh`).
- **Password reset without SMTP** (NEW, gap P2-8): admin-generated single-use reset link (`Settings → Household →
  Reset`, 1-hour TTL) plus `trove admin reset-password <email>` printing the link for headless use; self-service
  email reset is offered only when `SMTP_ENABLED=true`. A successful reset revokes every `refresh_token` for that user.

### 1.6 Config schema — every env var

`core/src/main/resources/application.yml` has **no default for any secret**; `docker-compose.yml` uses `${VAR:?message}`
for every row marked required; `scripts/bootstrap.sh` (reusing `upsert_env()` from `start-full-stack.sh:126-138`) generates
the four marked *gen* only while the value is still the placeholder, prints `TROVE_ADMIN_PASSWORD` once, and records
`.bootstrap-done` so a rerun never regenerates or reprints.

| Env var | Property | Required | Notes |
|---|---|---|---|
| `TROVE_DOMAIN` | `trove.domain` | yes | Cookie domain, CORS origin, Traefik `Host()` rule, ACME |
| `ACME_EMAIL` | — (Traefik) | yes | Let's Encrypt registration |
| `POSTGRES_PASSWORD` | `spring.datasource.password` | yes, *gen* | `openssl rand -base64 36` — closes the `start-full-stack.sh` gap |
| `POSTGRES_USER` / `POSTGRES_DB` | datasource | default `trove` / `trove` | not secrets |
| `TROVE_JWT_SECRET` | `trove.jwt.secret` | yes, *gen* | `JwtProperties.validate()` rejects `<32` chars **and** the literal `change-me-minimum-32-chars-in-production!!` |
| `TROVE_ENCRYPTION_KEY` | `trove.crypto.key` | yes, *gen* | 64 hex; bound via `@ConfigurationProperties`, not `System.getenv` |
| `TROVE_ADMIN_EMAIL` / `TROVE_ADMIN_PASSWORD` | `trove.bootstrap.*` | yes on first boot, *gen* (password) | consumed only when `users` is empty |
| `TROVE_DATA_DIR` | `trove.storage.root` | default `/data` | receipts/exports/uploads; created on boot (`StorageConfig`) |
| `TROVE_REGISTRATION_OPEN` | `trove.auth.registration-open` | default `false` | admin can flip at runtime |
| `TROVE_OIDC_ENABLED`, `TROVE_OIDC_ISSUER_URI`, `TROVE_OIDC_CLIENT_ID`, `TROVE_OIDC_CLIENT_SECRET`, `TROVE_OIDC_DISPLAY_NAME` | `trove.auth.oidc.*` | no | all-or-nothing validation at boot |
| `SMTP_ENABLED`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `NOTIFICATION_FROM_ADDRESS` | `trove.notifications.*`, `spring.mail.*` | no | off by default; self-service password-reset email is offered only when true (§1.5) |
| `TROVE_RATE_LIMIT_ENABLED` | `trove.rate-limit.enabled` | default `true` | actually read this time (gateway NEW-1) |
| `TROVE_EGRESS_ALLOW_PRIVATE` | `trove.egress.allow-private` | default `false` | renamed from `TROVE_AI_ALLOW_PRIVATE_ENDPOINTS` — governs **every** outbound call (AI endpoint, SimpleFIN, webhooks), not AI alone (gap P2-10); when false, `OutboundUrlPolicy` refuses a private/loopback/link-local/CGNAT/ULA target for any user; when true, an individual user may still opt a specific AI endpoint into a private target from Settings › AI (owner decision: AI endpoint is per-user; the household admin sets whether private LAN targets are allowed at all, each member opts their own endpoint in) |
| `TROVE_RECEIPT_QUOTA_MB` | `trove.receipts.quota-mb` | default `2048` | per-user receipt storage quota (gap P2-11), shown in Settings |
| `JAVA_OPTS` | — | default `-Xmx1536m -XX:+UseZGC` | compose `mem_limit: 2g` |
| `COMPOSE_PROFILES` | — | no | `ai`, `monitoring` |
| `TZ` | — | default `UTC` | container clock; **not** the user-facing timezone — every `@Scheduled` job still carries `zone="UTC"`, but per-user "today"/"this month" boundaries are computed from `users.timezone` (IANA, defaulted from the browser/device at setup), not the container clock (gap P2-15; see §3 V3, §6) |

Retired names (13 files, 50+ vars): every `*_POSTGRES_URL`, `DB_HOST/PORT/NAME/USER/PASS`, `VALKEY_URL`, `MINIO_*`,
`AUTHENTIK_*`, `LITELLM_*`, `OLLAMA_URL`, `PADDLEOCR_URL`, `TROVE_SERVICE_TOKEN`, `TROVE_INTERNAL_SERVICE_TOKEN`,
`TROVE_AI_SERVICE_TOKEN`, `AI_SERVICE_TOKEN`, `SERVICE_TOKEN`, `INGESTION_URL`, `*_URL` service hostnames, `SERVER_PORT`,
`TROVE_SECRET`, `FCM_*`, `APNS_*`, `GRAFANA_ADMIN_PASSWORD` (→ `GRAFANA_ADMIN_PASSWORD` only under `monitoring` profile),
and — per the owner's no-push decision — `EXPO_PUSH_ENABLED`/`trove.notifications.expo-push.enabled` (v1 ships in-app
notifications + client-local notifications only; no Expo relay, no `push_device` table; see §2.8, §3, §6, §9).

**Multipart / upload limits** (app config, not env-tunable; gap P2-11/P2-12): `spring.servlet.multipart.max-file-size` /
`max-request-size` = 20 MB globally (receipts); the CSV/OFX/QFX import endpoints (§4.4, §6) additionally enforce
`ImportLimits` (10 MB, 50,000 rows, 1,000 chars/field, 60 s parse budget, streaming, no zip wrappers) at the service
layer since Boot's global multipart cap alone would reject nothing about row count or parse time. Boot's default
multipart cap is 1 MB; the plan as originally drafted named no multipart config anywhere, so every receipt upload
would 413 out of the box.

---

## 2. Source migration table

Paths are relative to `<module>/src/main/java/` unless prefixed. Tests follow the rule in §2.13 unless listed.

### 2.1 `services/api-gateway` (`finance.trove.gateway.*`)

| Source | Action | Destination / reason |
|---|---|---|
| `TroveApiGatewayApplication` | DELETE | one `finance.trove.app.TroveApplication` |
| `routing/{HealthAggregator,ProxyController,ServiceProperties,ServiceRegistry}` | DELETE | no processes to proxy; B1–B4, D7(health), D9 vanish by construction |
| `auth/{AuthentikClient,AuthentikProperties,AuthentikUserInfo}` | DELETE | Authentik-specific id_token exchange; OIDC is generic via Spring |
| `auth/AuthController` | REWRITE | `finance.trove.auth.AuthController`: drop `/token`; add register/login/login-totp/password/totp; `/refresh`+`/logout` use Postgres store; mapping becomes `/v1/auth` (context-path supplies `/api`) |
| `auth/AuthService` | REWRITE | `finance.trove.auth.AuthService` — orchestrates `LocalAccountService` + `JwtIssuerService` + `RefreshTokenService` |
| `auth/{JwtIssuerService,JwtAuthenticationProvider,TroveRoles}` | REWRITE | `finance.trove.auth.jwt` — **not** a mechanical KEEP: `JwtIssuerService` adds a `typ` claim (`access`/`totp_challenge`) + `iss`/`jti` to every token; `JwtAuthenticationProvider` requires `typ=access`, closing the type-confusion hole where any HS256 token with a `sub` (including a 60-s TOTP challenge) is accepted as a full access token today (§1.5, blocker) |
| `auth/CookieJwtAuthFilter` | REWRITE | `finance.trove.auth.jwt` (no longer `@Component`; constructed in `SecurityConfig`); sets request attribute `trove.authSource=COOKIE\|BEARER` for `CsrfRequiresHeaderFilter` below |
| `auth/CsrfRequiresHeaderFilter` | REWRITE, not KEEP | `finance.trove.auth.jwt`; today it unconditionally rejects every mutating request without `X-Requested-With` — enforced only when `trove.authSource==COOKIE`, or mobile's bearer-only `POST /v1/auth/login` 403s at step 21 (§1.5, major) |
| `auth/UserClaims` | KEEP | `finance.trove.auth.jwt` — now built from `users` row, not Authentik userinfo |
| `auth/RefreshTokenStore` | REWRITE | `finance.trove.auth.session.RefreshTokenService` + `RefreshToken` entity/repository (Postgres, hashed, rotation, family revoke with a 30-s reuse-of-immediate-predecessor grace window, §1.5); Lettuce/`StringRedisTemplate` code deleted |
| `auth/MeController` | REWRITE | `finance.trove.auth.MeController` at `/v1/auth/me`; response camelCase `{userId,email,displayName,roles,totpEnabled,householdId}` |
| `auth/oidc/AuthCookies` | REWRITE, not KEEP | `finance.trove.auth.oidc` (used by local login too); `SameSite` **changes** `Strict`→`Lax` (needed for the IdP redirect to land with cookies present — this is a behaviour change, not an addition) |
| `auth/oidc/{TroveOidcSuccessHandler,TroveOidcFailureHandler}` | REWRITE | `finance.trove.auth.oidc`; `@ConditionalOnProperty(trove.auth.oidc.enabled)`; success handler auto-links via `oidc_link` only when the ID token's `email_verified=true` (§1.5, major) |
| NEW `auth/oidc/CookieAuthorizationRequestRepository` | NEW, not KEEP | `finance.trove.auth.oidc`; `HttpCookieOAuth2AuthorizationRequestRepository` named in the original draft is not a real Spring Security class — this must be written (AES-GCM state/PKCE cookie, 5-min max-age, §1.5) |
| `config/GatewaySecurityConfig` | REWRITE | `finance.trove.app.SecurityConfig` — becomes the one chain (§5.1); PKCE resolver bean kept, conditional |
| `config/CorsConfig` | KEEP | `finance.trove.app.WebConfig` (origins = `https://${trove.domain}` + dev `http://localhost:5173`) |
| `config/RestClientConfig` | REWRITE | `finance.trove.app.HttpClientConfig` — one `RestClientCustomizer` with timeouts (§5.5); authentik client deleted |
| `config/ValKeyConfig` | DELETE | no Valkey |
| `preferences/{UserPreferences,UserPreferencesRepository,UserPreferencesService,UserPreferencesController,UserPreferencesDto}` | REWRITE | `finance.trove.users.preferences`: fields become `region`, `locale`, `timezone`, `onboardingCompleted` (`currency`/`country` move onto `users` itself — they are identity-level, set once at registration and read by five other packages; keeping them only on `user_preferences` as originally drafted left those reads pointing at nonexistent columns, see §3 V3); `deployment`/`aiMode` deleted; row created at registration |
| `ratelimit/{RateLimitConfig,RateLimitConfigRepository,RateLimitService,RateLimitFilter}` | REWRITE | `finance.trove.app.ratelimit`: `LettuceBasedProxyManager` → `bucket4j` `LocalBucketBuilder` in a Caffeine map keyed `userId|ip`; read `trove.rate-limit.enabled`; `@Scheduled` reload now fires (one `@EnableScheduling`); **filter moves after `CookieJwtAuthFilter`** in the chain (§5.1) and the IP-keyed branch is path-matched against `rate_limit_config` too, not just a flat 20/20 anonymous bucket (§5.1, blocker); IP is read from `request.getRemoteAddr()` after a configured `RemoteIpValve`/trusted-proxies filter, never parsed by hand from `X-Forwarded-For` (§1.5 gap P2-5); `/v1/auth/**` limiter checks are fail-closed, not fail-open |
| `audit/{AuditLog,AuditLogPersistenceService,AuditLogRepository}` | KEEP | `finance.trove.app.audit`; `saveAsync` bound to the named `auditExecutor` (bounded), not `SimpleAsyncTaskExecutor`; add `AuditController` for `GET /v1/audit` (D7) |
| `audit/AuditLogFilter` | REWRITE, not KEEP | `finance.trove.app.audit`; drops `ContentCachingResponseWrapper` (reads `response.getStatus()` directly) — the wrapper buffers the whole response body and only flushes in its `finally`, which silently swallows every SSE frame from the coach endpoint (nothing reaches the client until the emitter completes) and fully buffers every receipt/PDF download in heap (§6 AI settings + coach, major) |
| NEW `finance.trove.app.audit.SecurityEvent{,Repository}` | NEW | append-only `security_event` log (gap P2-17): LOGIN_OK/LOGIN_FAIL/LOCKOUT/TOTP_ENROL/TOTP_DISABLE/PASSWORD_CHANGE/SESSION_REVOKE/BANK_CONNECTION_ADD/BANK_CONNECTION_REMOVE/EXPORT/ACCOUNT_DELETE/ADMIN_INVITE/EGRESS_SETTING_CHANGE; DB role is INSERT/SELECT only; `GET /v1/me/security-events` (self) and an admin view; audit rows store the **route template**, never the raw request URI (which today can carry an OIDC code or query string) — see §5.1 |
| `src/main/resources/db/migration/V1..V5` | REWRITE | folded into V11 (audit_log)/V12 (rate_limit_config)/V10 (user_preferences) of §3 |
| `src/main/resources/{application.yml,logback-spring.xml}` | REWRITE | merged into `core/src/main/resources/` |
| tests: `routing/*Test`, `ProxyControllerIT`, `AuthentikClientTest`, `AuthControllerTest`(token-exchange cases), `OidcLoginIT`(Authentik-specific stubs), `RefreshTokenStoreTest` | DELETE | targets deleted code |
| NEW `finance.trove.common.UserScopedRepository<T>` | NEW | gap P2-9; base repository interface exposing only `findByIdAndHouseholdId`/`findAllByHouseholdId`/`deleteByIdAndHouseholdId` — every ledger-owned repository extends it instead of `JpaRepository`; ArchUnit forbids `findById`/`deleteById`/`findAll` (no scope) on any repository whose entity carries `household_id` |
| tests: `security/AccessControlIT` | REWRITE | **un-@Disabled**; targets `POST /v1/auth/register` + `GET /v1/transactions`; asserts **household B** cannot read **household A**'s account/transaction/budget/receipt/subscription rows (404) — corrected from a per-user isolation assertion, which would contradict the shared-household-ledger decision (§1.5); a second parameterised case walks every by-id route in the OpenAPI spec |
| tests: `CookieAuthIT`, `CsrfIT`, `AuthControllerIT`, `RefreshTokenIT`, `RateLimit*`, `AuditLogIT`, `UserPreferences*IT`, `JwtAlgorithmTest`, `Cookie/Csrf/Jwt*Test`, `AuthCookiesTest`, `TroveOidc*HandlerTest`, `RequestIdMdcIntegrationTest` | KEEP | `core/src/test/java/finance/trove/auth/…`; Valkey Testcontainer removed |

### 2.2 `services/ingestion-service` (`finance.trove.ingestion.*`)

| Source | Action | Destination / reason |
|---|---|---|
| `TroveIngestionApplication`, `config/IngestionSecurityConfig`, `config/JacksonConfig` (empty) | DELETE | |
| `account/{ConnectedAccount,AccountRepository,AccountService,AccountType,SyncStatus}` | REWRITE | `finance.trove.accounts`: identity from principal, scoped by `household_id`; `AccountType.CREDIT_CARD` matches new CHECK; no `"CAD"` default — currency from `UserSettingsService`; `configEncrypted` becomes **nullable** (the entity's `@Column(nullable=false)` must change in the same commit or `ddl-auto: validate` throws `PropertyValueException` on boot for every manual/CSV/OFX account — none of those have a config blob to encrypt); `source_kind='MANUAL'` added (gap P2-13, no-credential manual account) |
| `account/AccountController` | REWRITE | `finance.trove.accounts.AccountController`: remove all `@RequestHeader("X-User-Id")`; scope by `household_id`; return `AccountResponse` DTO (never `configEncrypted`); add `GET/PUT /{id}`, `POST /{id}/sync`, `POST` with `sourceKind=MANUAL` (no exchange step) |
| `account/{ExchangeSetupTokenController,ExchangeSetupTokenRequest,ExchangeSetupTokenResponse,ExchangeSetupTokenService,SetupTokenExchanger,SetupTokenExchangeException}` | REWRITE | `finance.trove.ingestion.simplefin`; identity from principal; decoded URL passes `OutboundUrlPolicy` (https only, public IP only, DNS-pinned at connect, §6); **upserts** `connected_account` on `(household_id, source_kind, external_account_id)` instead of always inserting — the service today creates a fresh row for every bridge account on every exchange with no lookup by SimpleFIN's own account id, so a re-exchange (token rotation, retry, second device) duplicates the account and every transaction on it (§3 V16, blocker) |
| `account/HttpSetupTokenExchanger` | REWRITE | same package; uses the customized `RestClient.Builder` (timeouts) |
| `transaction/{Transaction,TransactionRepository,TransactionService,TransactionQuery}` | REWRITE | `finance.trove.transactions`: `sourceId`→`providerTransactionId`; `tagsJson` → `List<String> tags` (JSONB); sort allowlist (`date,amount` × `asc,desc`), default `limit=50`/max `500`, zero-based `page`; add `createManual` (gap P2-13: amount/date/merchant/category/account/notes, `manual=true`, `source_kind='MANUAL'` accounts only), `delete` (manual rows only — a synced row is deleted by disconnecting/re-syncing the account, never directly); add `transferGroupId` (gap P2-14) and a nightly pairing job matching opposite-sign, same-currency, ±3-day amounts across the household's own accounts; add `q` (pg_trgm trigram index on merchant/description/notes, gap P2-15) |
| `transaction/TransactionController` | REWRITE | identity from principal, scoped by `household_id`; `ApiResponse` envelope; `GET` gains `page` (0-based, default `limit=50` max `500`), `q`, date presets, amount range; add `GET /{id}`, `POST` (manual entry), `DELETE` (manual only); `PATCH {categoryId,…}` sets `transaction.category_id` directly **and** upserts `category_override` (marks it user-pinned so auto-categorisation never overwrites it again), then publishes `TransactionCategorisedEvent` — the original draft only mentioned the override table, which the budgets aggregate (D1) never reads, so a recategorised transaction's spend would never move envelopes (§6 D4, major) |
| `dashboard/DashboardController` | REWRITE | `finance.trove.transactions.dashboard`: identity from principal, household-scoped; per-currency subtotals, headline in `users.currency`; "today"/date-range boundaries computed from the caller's `users.timezone` via a `UserClock` bean, not the container's UTC clock (gap P2-15 — `DashboardController.java:95,100` is zone-less today) |
| `dedup/{DeduplicationService,DeduplicationResult}` | REWRITE | `finance.trove.ingestion.dedup`: exact match on `providerTransactionId` unchanged; the **fuzzy pass is corrected**, not carried over unchanged — as written today it (a) matches ANY pending row in the ±2 d/±$0.50 window regardless of the candidate's own provider id, so two distinct same-day, same-amount pending transactions collapse into one, and (b) requires `existing.pending=true` while CSV-sourced rows are inserted `pending=false` (posted), so a re-imported CSV file is never matched and every row duplicates. Fixed: fuzzy match only fires when `incoming.providerTransactionId==null`, and only against a candidate whose own `providerTransactionId` is not already present in the current sync batch; `CsvSource` derives a deterministic `providerTransactionId = sha256(accountId\|date\|amount\|normalisedDescription\|rowOrdinal)` so CSV re-import is exact-match, not fuzzy, matching gap P2-12's `import_batch` design (§6 M0/CSV import, blocker) |
| `sync/{SyncOrchestrator,SyncLog,SyncLogRepository}` | REWRITE | `finance.trove.ingestion`: `status` values become `RUNNING/SUCCESS/PARTIAL_FAILURE/FAILED` (matching §3 V18's CHECK — the code today writes `OK`/`ERROR`, which V15 as originally scoped did not rename); the method is **not** a single top-level `@Transactional` (a per-row failure would mark the whole transaction rollback-only, so the final `PARTIAL_FAILURE` log save throws `UnexpectedRollbackException` and no sync could ever complete PARTIAL_FAILURE) — fetch from the source outside any transaction, persist accepted rows in one short `@Transactional` batch method, write the `RUNNING`/final `sync_log` rows in their own short transactions, and start the sync by claiming the account with `sync_status` set from `PENDING/OK/ERROR` to `SYNCING` in a conditional `UPDATE … WHERE sync_status<>'SYNCING'` (409 if already syncing) so two overlapping sync triggers cannot both pass the in-memory check and collide on the unique index mid-loop (§6 M0, major); publishes `TransactionsIngestedEvent` from `afterCommit` of the batch |
| `sync/PluginRegistry` | KEEP (rename) | `finance.trove.ingestion.SourceRegistry` — injects `List<BankSource>` |
| `sync/SyncController` | REWRITE | folded into `AccountController.sync` (`POST /v1/accounts/{id}/sync`) |
| `encryption/FieldEncryptionService` | REWRITE | `finance.trove.common.crypto`; key from `trove.crypto.key` property, not `System.getenv` |
| NEW `ingestion/importbatch/{ImportBatch,ImportBatchRepository,ImportController}` | NEW | gap P2-12: `POST /v1/imports` (multipart csv/ofx/qfx) → preview (detected header, per-column guesses, date-format candidates, new/exact-dup/fuzzy-dup counts) → `POST /v1/imports/{id}/commit`; `import_batch` row + `transaction.import_batch_id`; `DELETE /v1/imports/{id}` undoes unedited rows; shared `ImportLimits` (§1.6) |
| `db/migration/V1..V4` | REWRITE | §3 V16–V18 |
| tests: `DeduplicationServiceTest`, `SyncOrchestratorTest`, `FieldEncryptionServiceTest`, `AccountServiceTest`, `ExchangeSetupTokenServiceTest`, `TransactionServiceTest`, `PluginRegistryTest` | KEEP | + new `DeduplicationServiceIT` (two distinct pending transactions same day/amount → both persist; two-sync-idempotent; re-exchange SimpleFIN twice → account count unchanged; upload the same OFX/CSV twice → transaction count unchanged) |
| tests: `*ControllerTest`, `SyncControllerWebMvcTest`, `TransactionControllerIT`, `ExchangeSetupTokenControllerIT` | REWRITE | remove `X-User-Id`; assert 404 when JWT household ≠ owning household |

### 2.3 `services/categorisation-service` (`finance.trove.categorisation.*`)

| Source | Action | Destination / reason |
|---|---|---|
| `TroveCategorisationApplication`, `config/CategorisationSecurityConfig` | DELETE | |
| `category/{Category,CategoryRepository}` | REWRITE, not KEEP | `finance.trove.categorisation`; adds `household_id UUID NULL FK` (NULL = system category) — `category` has no tenancy column and a globally-unique `name` today, so every household would see every other household's custom categories, collide on name, and be able to delete each other's rows (§3 V14, major); `CategoryRepository.findVisible(householdId)` = system OR owned |
| `category/CategoryController` | REWRITE | binds `CategoryRequest{name,parentId,icon}` (G3); add `PUT /{id}`; `getAllCategories` no longer swallows exceptions; GET/POST/PUT all serialise one `CategoryDto{id,name,parentId,icon,isSystem}` — GET as originally scoped still returned the raw entity (nested `parent`, field `system`) while only POST got a DTO, which does not match either client's `parentId`/`isSystem` shape (§4.5, minor); create/delete require household ownership (system rows are never deletable) |
| `categorise/{CategorisationService,CategorisationResult}` | REWRITE | split: read path stays `readOnly`; write-back moves to new `MerchantMappingWriter` bean (`REQUIRES_NEW`) — fixes D4 self-invocation; identity is a method parameter supplied only by callers holding the principal; the **caller** (`CategorisationListener`, §6 D4) owns the `REQUIRES_NEW` transaction that sets `transaction.category_id` — the read-only session must never be the one persisting that column, or the write is silently dropped exactly the way D4 originally failed (minor) |
| `categorise/MerchantCategoryMapRepository` | REWRITE | prefix query as `@Query("… WHERE :merchant LIKE CONCAT(m.merchant,'%') AND m.matchType='PREFIX' AND (m.userId IS NULL OR m.userId=:userId)")` |
| `categorise/{MerchantCategoryMap,CategoryOverride,CategoryOverrideRepository}` | REWRITE | add nullable `userId` (user rules — merchant rules stay per-user, not household-scoped, since a raw merchant string can be a counterparty's or employer's name that not every household member should shape categorisation from); `MerchantMappingWriter.persist(userId, …)` **always** sets `user_id` to the caller — the write-back in source has no user scope at all today, so an LLM-learned rule from one household's raw merchant strings would otherwise be visible/listable to every other user (§3 V15/§6 D4, major); `CategoryOverride.transactionId` becomes FK |
| `categorise/CategorisationController` | DELETE | `/v1/categorise*` replaced by in-process calls and `PATCH /v1/transactions/{id}`; user rules via `CategoryRuleController` (NEW, `/v1/categories/rules`, lists only `user_id = caller`) |
| `categorise/AiCategorisationService` | REWRITE | `finance.trove.categorisation.ai.AiCategorisationService` — calls `finance.trove.ai.AiGateway.complete()`; only invoked when the transaction owner's own `ai_settings.enabled` and rules missed (AI endpoint is per-user, §6) |
| `resources/community-merchant-registry.json` | REWRITE | converted to `INSERT`s in V15 with an explicit slug→`category.name` mapping (`food.coffee`→`Coffee Shops`, `health.pharmacy`→`Pharmacy`, `food.grocery`→`Groceries`, `shopping.online`→`Shopping`; `food.delivery`→`Restaurants`, since no delivery category is seeded) — `registry/merchant-db.json`'s `category_id` values are slugs that match none of the 51 `category.name` seeds as originally drafted, so the `INSERT … SELECT id FROM category WHERE name = <slug>` would insert NULL into a NOT NULL FK and halt Flyway on every boot (blocker); both source files repeat some aliases (`TIM HORTONS`), so each alias becomes one `EXACT` row inserted `ON CONFLICT DO NOTHING`; in-memory `HashMap` deleted |
| `db/migration/V1..V3` | REWRITE | §3 V14, V15, V19 |
| tests: `CategorisationServiceTest`(add real-DB `CategorisationWriteBackIT`), `CategoryControllerTest`(add id-in-body rejection), `AiCategorisationServiceTest`, entity tests | KEEP/REWRITE | `CategorisationControllerTest` DELETE; extend `AccessControlIT` to categories (household B cannot mutate household A's custom category) |

### 2.4 `services/tax-engine` (`finance.trove.tax.*`) — **DELETE entirely**

All of `calculation/*`, `rates/*` (+ three `tax-rates-*.yml`), `room/*`, `export/Sa100PdfExporter`, `profile/*`,
`slip/*`, `deduction/*`, `config/TaxSecurityConfig`, `TroveTaxEngineApplication`, migrations V1–V6, all tests, `Dockerfile`,
`README.md`, `.env.example`. Nothing is moved; `taxexport` (§2.9) is designed against `transaction`, not against these.

### 2.5 `services/budget-service` (`finance.trove.budget.*`)

| Source | Action | Destination / reason |
|---|---|---|
| `TroveBudgetApplication`, `config/BudgetSecurityConfig`, `config/SchedulingConfig` | DELETE | one `finance.trove.app.SchedulingConfig` |
| `budget/{BudgetPeriod,BudgetRepository,BudgetService,BudgetType,CreateBudgetRequest,UpdateBudgetRequest}` | REWRITE | `finance.trove.budgets.period`; remove 2 `"CAD"` sites; currency from `UserSettingsService`; add `delete(id)` |
| `budget/BudgetController` | REWRITE | `/v1/budgets`: add `DELETE /{id}`; `getCurrent` uses `orElseThrow(ResourceNotFoundException)` like the others |
| `envelope/{Envelope,EnvelopeRepository,EnvelopeService,EnvelopeHealth,EnvelopeHealthCalculator,EnvelopeWithHealth,Create/UpdateEnvelopeRequest}` | REWRITE | `finance.trove.budgets.envelope`; remove `"CAD"`; **NEW `EnvelopeSpendService.recalculate(householdId, categoryId, month)`** = `SUM(CASE type WHEN 'DEBIT' THEN amount WHEN 'REFUND' THEN -amount END) WHERE household_id AND category_id AND date in month AND currency = envelope.currency AND transfer_group_id IS NULL` written to `spent` (idempotent, D1 root fix; excludes transfer legs per gap P2-14, and nets REFUND since `transaction.type` widens to the full 7-value set in §3 V14 — the original single-value `type='DEBIT'` sum silently mis-totals a REFUND typed CREDIT and a same-account TRANSFER typed DEBIT) |
| `goal/*` | KEEP | `finance.trove.budgets.goal`; remove `"CAD"` |
| `rollover/{RolloverService,RolloverScheduler}` | KEEP | `finance.trove.budgets.rollover`; add `@SchedulerLock`, `zone="UTC"` |
| `networth/{Asset,Liability,NetWorthSnapshot,*Repository,*Request,NetWorthSeriesResponse}` | REWRITE | `finance.trove.networth`; `sumValueByUserId`/`sumBalanceByUserId` → `…GroupByCurrency` (F2); snapshot unique `(user_id, snapshot_date, currency)`; `account_id` FK |
| `networth/{NetWorthService,NetWorthSnapshotService,NetWorthController}` | REWRITE | per-currency series; add `GET /v1/networth/liabilities` (BUD-02) |
| `db/migration/V1..V6` | REWRITE | §3 V21–V26 |
| tests | KEEP | + `EnvelopeSpendServiceIT` (ingest 3 DEBITs → `spent` = sum; recategorise one → recomputed), `NetWorthMixedCurrencyIT` |

### 2.6 `services/receipt-processor` (`finance.trove.receipt.*`)

| Source | Action | Destination / reason |
|---|---|---|
| `TroveReceiptProcessorApplication`, `config/ReceiptSecurityConfig` | DELETE | |
| `config/{ReceiptAsyncConfig,ReceiptPipelineConfig}` | REWRITE | `finance.trove.receipts.ReceiptPipelineConfig` (executor named `receiptExecutor` kept, declared in `app.AsyncConfig`); `IngestionProperties` DELETE (in-process) |
| `storage/{MinioConfig,MinioProperties,MinioStorageService}` | DELETE | replaced by `finance.trove.common.storage.LocalDiskStorage` (NEW: `put(kind, userId, id, bytes) → path`, `get`, `delete`; root `trove.storage.root`; `mkdirs` on boot) |
| `storage/StorageException` | KEEP | `finance.trove.common.storage` |
| `receipt/{Receipt,LineItem,*Repository,ReceiptStatus,ReceiptResponse,ReceiptSummary,LineItemResponse,StatusResponse,ReviewRequest}` | REWRITE | `finance.trove.receipts.receipt`; `ReceiptStatus` drops **both** `QUEUED` and `OCR` (PaddleOCR is cut and there is no queue stage) — the enum today still contains both and `ReceiptPipelineOrchestrator.java:108` does `status = ReceiptStatus.valueOf(stage.stageName())`, so `VisionExtractionStage.stageName()` must literally be `"EXTRACTING"` (an existing enum value) or every AI-path receipt fails the CHECK on write (§3 V27, minor); `imageKey/thumbnailKey` → `receipt_image` rows; `thumbnailUrl` = `/api/v1/receipts/{id}/thumbnail`; `matchedTransactionId` FK; household-scoped |
| `receipt/{ReceiptController,ReceiptService}` | REWRITE | `download`/`thumbnail` stream bytes (no presigned URL) with `Content-Disposition: attachment; filename="<uuid>.<ext>"`, the stored MIME type, and `X-Content-Type-Options: nosniff` (gap P2-11 — an inline-served upload whose magic bytes claim one type and content is another is otherwise served straight from the app origin); add `GET /{id}/thumbnail`, `PATCH /{id}` (manual fields + `matchedTransactionId`) |
| `upload/{MultipartFileValidator,ReceiptUploadController,ReceiptUploadService,UploadResponse}` | REWRITE | `finance.trove.receipts.upload`; storage calls repointed; **re-encodes every raster upload to JPEG q85, ≤2000px long edge** before it ever touches disk — this strips EXIF/GPS by construction, rejects >40 MP source images before decode (an `ImageReader` header probe runs before the full `ImageIO.read`, not after, so a crafted huge-dimension file cannot OOM the single JVM), and enforces the per-user `TROVE_RECEIPT_QUOTA_MB` quota (gap P2-11 — none of EXIF-stripping, a dimension cap, or a quota exist in the source `ReceiptUploadService`, which stores `file.getBytes()` verbatim); HEIC is rejected server-side (client converts); when AI disabled, receipt goes `UPLOADED → COMPLETE(reviewRequired=true)` directly; upload response is `202 {id, status, createdAt}` (§4.10) |
| `pipeline/{PipelineStage,PipelineStageException,ReceiptPipelineOrchestrator,ReceiptProcessingContext}` | KEEP | `finance.trove.receipts.pipeline` |
| `pipeline/stages/OcrStage` | DELETE | PaddleOCR cut |
| `pipeline/stages/ExtractionStage` + `extraction/AiExtractor` | REWRITE | `VisionExtractionStage`: sends the image (base64 content-part) + `prompts.yml` `receipt.extract` to `AiGateway.completeWithImage()`; skipped when AI disabled |
| `pipeline/stages/ParsingStage` + `extraction/{HeuristicExtractor,HeuristicExtractionResult,LineItemCandidate}` | REWRITE | runs on the vision model's raw text when present; date format order from `users.country` (D2); currency = user currency unless an explicit ISO code appears (D3) |
| `pipeline/stages/MatchingStage` + `matching/{TransactionMatchingService,TransactionSummary,MatchingServiceException}` | REWRITE | in-process `TransactionQueryService.findCandidates(userId, amount±, date±)`; no HTTP, no `X-User-Id` |
| `pipeline/stages/TaxSplitStage` + `taxsplit/*` | REWRITE | runs only when `users.country == CA`; province from `user_preferences.region`; output stays on `receipt` only, never in the tax export |
| `pipeline/stages/ScoringStage` | REWRITE | weights renormalised when no match candidate exists (D1: unmatched receipts can reach the threshold) |
| `pipeline/stages/ValidationStage` | KEEP | storage call repointed |
| `resources/prompts.yml` | KEEP | `core/src/main/resources/prompts/receipt.yml` |
| `db/migration/V1..V3` | REWRITE | §3 V27–V29 |
| tests: `OcrStageTest`, `MinioStorageServiceTest`, `ReceiptPipelineIT`(PaddleOCR stubs) | DELETE/REWRITE | `ReceiptPipelineIT` rewritten with a WireMock OpenAI-compatible vision stub; all others KEEP with storage mock swapped |

### 2.7 `services/subscription-detector` (`finance.trove.subscription.*`)

| Source | Action | Destination / reason |
|---|---|---|
| `TroveSubscriptionDetectorApplication`, `config/{SubscriptionSecurityConfig,SchedulingConfig,RestClientConfig}` | DELETE | |
| `detection/{IngestionClient,TransactionDto}` | DELETE | replaced by `TransactionQueryService.debitsSince(householdId, from)` |
| `detection/{BillingCycle,ConfidenceCalculator,IntervalClassifier,MerchantNameNormalizer,SubscriptionDetectionService}` | REWRITE | `finance.trove.subscriptions.detection`; consumes `Transaction` projection; upsert via `ON CONFLICT (household_id, merchant_name)` (corrected from `user_id` — shared household ledger, §1.5); publishes `SubscriptionPriceChangedEvent` |
| `subscription/{DetectedSubscription,SubscriptionRepository,SubscriptionService,SubscriptionSignal,SubscriptionSummary,UpcomingCharge}` | REWRITE | `finance.trove.subscriptions`; household-scoped; both `"CAD"` sites → the creating user's currency; `transaction_ids[]` → `subscription_transaction` join |
| `subscription/SubscriptionController` | KEEP | + `POST /v1/subscriptions/scan` (manual trigger) |
| `alert/*` | KEEP | `finance.trove.subscriptions.alert` |
| `scheduler/SubscriptionScanScheduler` | REWRITE | enumerates households via `UserRepository.findAllActiveIds()` grouped by `household_id` (SD-1/D2); `zone="UTC"`, `@SchedulerLock("subscription-scan")` |
| `db/migration/V1..V2` | REWRITE | §3 V30–V32 |
| tests | KEEP/REWRITE | `SubscriptionServiceTest:125` asserts user currency, not `"CAD"`; new `SubscriptionScanFreshInstallIT` |

### 2.8 `services/notification-service` (`finance.trove.notification.*`)

| Source | Action | Destination / reason |
|---|---|---|
| `TroveNotificationApplication`, `config/{NotificationSecurityConfig,SchedulingConfig,ServiceTokenAuthFilter,HttpClientConfig}` | DELETE | no `/internal/**` surface; scheduling/HTTP client centralised |
| `config/NotificationProperties` | KEEP | `finance.trove.notifications` |
| `channel/{ApnsChannel,FcmChannel}` | DELETE | **owner decision (2026-09-03): no push notifications until a direct APNs/FCM path exists — no Expo relay.** The original draft replaced these with a NEW `ExpoPushChannel`; that is now cut too. v1 = in-app notification inbox (`inapp/*`, kept) + client-local notifications (mobile schedules a local notification from its own poll of `GET /v1/notifications/unread-count`, no server push at all) |
| `channel/EmailChannel` | REWRITE | returns `DeliveryResult{SENT|FAILED, error}` instead of swallowing (D3) |
| `send/{NotificationDispatcher,NotificationLog,NotificationLogRepository,SendNotificationRequest}` | REWRITE | `finance.trove.notifications.send.NotificationService` (in-process API: `notify(userId, NotificationType, payload)`), writes `in_app_notification` **always**, then channels per preference (`EMAIL`, `IN_APP` only); `status` = channel result |
| `send/NotificationSendController` | DELETE | `/internal/notifications/send` gone |
| `inapp/*` | KEEP | `finance.trove.notifications.inapp` |
| `preference/*` | REWRITE | drop `taxReminderEnabled`; **no** `pushTokens`/`PushDevice`/`/v1/notifications/devices` — cut with Expo push above |
| `webhook/{SafeWebhookUrl,SafeWebhookUrlValidator}` | REWRITE, not KEEP | promoted to `finance.trove.common.net.OutboundUrlPolicy`, one bean used by every outbound caller (SimpleFIN exchange+sync, webhooks, the per-user AI endpoint — owner decision (d), §6); the source validator only rejects loopback/link-local/site-local/any/multicast, missing IPv6 ULA `fc00::/7`, CGNAT `100.64.0.0/10`, `0.0.0.0/8` (major); it also resolves DNS once at validation time and lets the HTTP client resolve **again** independently at connect — a rebinding resolver answers public at check time and private at connect time (classic TOCTOU). Fixed: the policy returns the vetted `InetAddress` and `HttpClientConfig` builds every outbound `RestClient` with a custom `DnsResolver` that only returns addresses the policy already approved for that call, so check and connect share one resolution (pin-by-resolution); `https` required unless the caller's own private-endpoint exception applies (`TROVE_EGRESS_ALLOW_PRIVATE`, §1.6); an `EGRESS_SETTING_CHANGE`/per-call `security_event`/audit row records what left the box and to where (owner decision (d), gap P2-10) |
| `webhook/{WebhookSubscription,WebhookDeliveryLog,*Repository,WebhookController,WebhookSubscriptionResponse,WebhookRetryScheduler}` | KEEP | `finance.trove.notifications.webhook` |
| `webhook/WebhookDeliveryService` | REWRITE | `deliver()` uses the pinned resolver above, not a second independent DNS lookup, before each attempt (D-N2); is **not** one `@Transactional` spanning the outbound HTTP call (10 s connect/10 s read) — claim due rows in a short transaction (`SELECT … FOR UPDATE SKIP LOCKED`, `status=IN_FLIGHT`), call out with `webhookRestClientBuilder` outside any transaction, persist the outcome in a second short transaction, so a slow receiver cannot pin a DB connection for the life of the call or blow the retry scheduler's `lockAtMostFor` (minor); `scheduleDelivery` called from `WebhookEventListener` (NEW) on every domain event whose type is in `event_types` |
| `digest/WeeklyDigestScheduler` | REWRITE | in-process `TransactionQueryService`; currency from user; date boundaries from the recipient's `users.timezone` (gap P2-15); `@SchedulerLock`, `zone="UTC"` (the lock/cron trigger stays UTC-scheduled; the *content* of the digest — "this week" — is computed per recipient) |
| `resources/templates/email/tax-reminder.html` | DELETE | others KEEP under `core/src/main/resources/templates/email/` |
| `db/migration/V1..V5` | REWRITE | §3 V33–V36, V13 (shedlock); no `push_device` migration (cut) |
| tests: `ApnsChannel*Test`, `FcmChannelTest`, `ServiceTokenAuthFilterTest`, `NotificationSendControllerIT`, `WeeklyDigestSchedulerTest`(WireMock contract) | DELETE | `WeeklyDigestSchedulerIT` NEW against real rows; others KEEP; no `ExpoPushChannelTest` (feature cut) |

### 2.9 `services/report-service` (`finance.trove.report.*`) → `finance.trove.taxexport`

| Source | Action | Destination / reason |
|---|---|---|
| `TroveReportApplication`, `config/ReportSecurityConfig`, `storage/{MinioConfig,MinioStorageService}`, `generation/{ReportDataFetcher,TransactionDto,NarrativeSummaryService,ReportFilters}` | DELETE | HTTP fetch → in-process; narrative and MONTHLY/ANNUAL/CUSTOM types out of scope |
| `config/ReportAsyncConfig` | REWRITE | executor `exportExecutor` declared in `app.AsyncConfig` (keep AbortPolicy rationale) |
| `report/{Report,ReportRepository,ReportStatus,ReportFormat,CreateReportRequest,ReportWithUrl}` | REWRITE | `TaxExportRun{id,userId,taxYear,jurisdiction,format,status,filePath,fileSizeBytes,errorMessage,…}`; `ReportType` DELETE |
| `report/{ReportService,ReportController}` | REWRITE | `TaxExportService`, `TaxExportController` at `/v1/tax-export/runs`; page/size bounded (RS-9); download streams the file |
| `generation/{ReportGenerationService,ReportStatusUpdater}` | KEEP | `finance.trove.taxexport` (status transitions in `REQUIRES_NEW` are correct) |
| `generation/CsvReportBuilder` | REWRITE | `CsvExportBuilder`: columns `date, merchant, description, notes, amount, currency, category, taxLineCode, taxLineLabel, receiptId`; **no `gst_amount`, no computed figure**; currency from `tx.currency()`; notes separate from description (RS-6); one row per transaction plus a totals-by-line section that is a plain sum of the user's own amounts |
| `generation/PdfReportBuilder` | KEEP (rename) | `PdfExportBuilder`; same columns |
| NEW `TaxLineMapping`, `TaxLineMappingRepository`, `TaxLineMappingController` | NEW | jurisdiction-scoped `category_id → (line_code, line_label, deductible)` seeded for CA (T2125/T1 line groupings) and US (Schedule C / Schedule A groupings), user-overridable; `UNIQUE(jurisdiction, category_id, user_id)` is declared **`NULLS NOT DISTINCT`** (Postgres 16, already pinned by §2.13) — the plain-`UNIQUE` form treats every `user_id IS NULL` global row as distinct from every other, so the seed itself is not unique and cannot be targeted by `ON CONFLICT` (§3 V42, minor; same treatment applies to `receipt_image`'s `(receipt_id, kind, page_no)` and `merchant_category_map`'s `(merchant, match_type, user_id)`) |
| `db/migration/V1` | REWRITE | §3 V41 (+V42 mapping) |
| tests: `CsvReportBuilderTest`(delete the `10.00` GST assertion), `PdfReportBuilderTest`, `ReportGenerationServiceTest`, `ReportStatusUpdaterTest`, `ReportServiceTest`, `ReportControllerIT` | REWRITE | rename to `*Export*`; `ReportDataFetcherTest`, `NarrativeSummaryServiceTest` DELETE |

### 2.10 `services/model-manager` (`finance.trove.modelmanager.*`) → `finance.trove.ai`

| Source | Action | Destination / reason |
|---|---|---|
| `TroveModelManagerApplication`, `config/{SecurityConfig,SchedulingConfig}` | DELETE | M1 config never ported |
| `config/YamlPropertySourceFactory` | KEEP | `finance.trove.app` |
| `chat/{CoachConversation,CoachConversationRepository,CoachPromptsProperties,SuggestionService}` | KEEP | `finance.trove.ai.chat` |
| `chat/ChatService` | REWRITE | routes through `AiGateway`; no `ModelRoutingService`/alias |
| `chat/ChatController` | REWRITE | keeps `/v1/chat/*`; SSE emitted from `AiGateway.stream()`; no direct Ollama `RestClient`; `X-Tax-Jurisdiction` header dropped (from `users.country`); wire contract now pinned explicitly (§4.11) — the original draft named "SSE via POST" with no frame shape, and neither client's existing SSE reader matches what the source controller (or `AiGateway.stream()`) would emit (major) |
| `chat/CoachContextBuilder` | REWRITE | in-process: `DashboardService`, `BudgetService`, `NetWorthService`, `SubscriptionService` (D1); bounded `coachExecutor`, not `commonPool` |
| `chat/LiteLlmChatGateway` | DELETE | superseded by `OpenAiCompatibleClient` |
| `insight/{CoachInsight,CoachInsightRepository,InsightController,InsightGenerationService}` | KEEP | `finance.trove.ai.insight` |
| `insight/WeeklyInsightScheduler` | REWRITE | all active users; jurisdiction from `users.country` (D5/D6) |
| `model/{ModelController,ModelHealthScheduler,ModelRegistration,ModelRepository}`, `routing/ModelRoutingRegistry`, `embeddings/EmbeddingsController` | DELETE | replaced by `finance.trove.ai.settings.{AiSettings,AiSettingsRepository,AiSettingsService,AiSettingsController}` (NEW) with `POST /v1/ai/settings/test` health ping (`GET {baseUrl}/models`) |
| `audit/{AiAuditLog,AiAuditLogRepository}` | REWRITE | `finance.trove.ai.audit`; `id`/`createdAt` server-assigned (D4); written by `AiGateway` after every call |
| `audit/AuditController` | DELETE | `/internal/audit` + `/v1/audit`(AI) gone |
| `resources/coach-prompts.yml` | KEEP | `core/src/main/resources/prompts/coach.yml`; the duplicate `max-history-messages` key removed from `application.yml` |
| `db/migration/V1..V7` | REWRITE | §3 V37–V40, V13 |
| tests: `ModelControllerTest`, `ModelHealthSchedulerTest`, `ModelRoutingRegistryTest`, `EmbeddingsControllerTest`, `AuditControllerTest`, `LiteLlmChatGatewayTest`, `integration/ChatControllerIT`(H2 duplicate), `integration/ModelControllerMvcTest` | DELETE | |
| tests: `chat/ChatControllerIT`, `ChatControllerTest`, `ChatServiceTest`, `CoachContextBuilderTest`(WireMock → in-process mocks), `CoachConversation*`, `CoachPromptsPropertiesTest`, `SuggestionServiceTest`, `Insight*`, `WeeklyInsightSchedulerTest`(no `"CA"` assertion) | KEEP/REWRITE | |

### 2.11 `libs/*`

| Source | Action | Destination / reason |
|---|---|---|
| `libs/common` `dto/*`, `domain/*`, `observability/*`, `exception/*` | KEEP | `finance.trove.common.{dto,domain,observability,exception}`; `Jurisdiction` javadoc updated |
| `libs/common` `exception/GlobalExceptionHandler` | REWRITE | + `ResponseStatusException`, `AccessDeniedException`, `AuthenticationException`, `MaxUploadSizeExceededException`, `DataIntegrityViolationException`(409) |
| `libs/common` `security/{JwtProperties,TroveUserDetails}` | REWRITE/KEEP | `finance.trove.auth.jwt`; `validate()` blocklists the known default |
| `libs/common` `security/JwtAuthFilter` | DELETE | `CookieJwtAuthFilter` (bearer-first, cookie-fallback) is the one filter |
| `libs/common` `security/SecurityConfig` | DELETE | header/entry-point content merged into `app.SecurityConfig` (§5.1); `/test/**` permitAll gone (G9) |
| `libs/common` `config/JacksonConfig` | DELETE | SNAKE_CASE never ran anywhere; clients are camelCase |
| `libs/common` `config/ActuatorConfig` | DELETE | convention lives in `application.yml` |
| `libs/common` `plugin/{BankSourcePlugin,RawTransaction,TransactionType,BankSourceConnectionException}` | REWRITE | `finance.trove.ingestion.source.{BankSource,RawTransaction,TransactionType,BankSourceException}`; `RawTransaction.sourceId` → `providerTransactionId` (O7); `id` slot removed |
| `libs/common` `resources/logback-trove.xml` | KEEP | `core/src/main/resources/logback-spring.xml` (with `RedactingConverter`) |
| `libs/common` tests | KEEP | `TestApplication` DELETE; `SecurityHeadersTest`, `JwtAuthFilterTest` → retarget the new chain/filter |
| `libs/privacy-engine` `PrivacyTier`, `PrivacyViolationException(PrivacyTier,String)` | KEEP | `finance.trove.ai.privacy` |
| `libs/privacy-engine` everything else (`ModelType`, `PrivacyEnforced`, `PrivacyEnforcementAspect`, `PrivacyAutoConfiguration`, `PrivacyContext`, `PrivacyClassifier`, `RuleBasedPrivacyClassifier`, `PrivacyRules`, `privacy-rules.yml`, `META-INF/spring/*.imports`, all tests except `PrivacyTierTest`) | DELETE | dead aspect; drops AspectJ from the classpath |
| `libs/ai-gateway-client` `{AiRequest,AiResponse}` + prompt substitution from `AiGatewayClient` | KEEP | `finance.trove.ai.client` |
| `libs/ai-gateway-client` `LiteLlmRestClient` | REWRITE | becomes `OpenAiCompatibleClient`: `complete`, `stream` (SSE `data:` frames → `Flux`/`SseEmitter`), `completeWithImage` (content-parts), `listModels`; retry skeleton kept; timeouts from customizer |
| `libs/ai-gateway-client` `{ModelRoutingService,ModelAlias,AiGatewayAutoConfiguration,AiGatewayProperties,AiAuditLog(DTO),PrivacyViolationException(String),META-INF imports}` | DELETE | one endpoint, no routing; audit is a local write |
| `libs/ai-gateway-client` tests | REWRITE | `OpenAiCompatibleClientTest` (WireMock: chat, stream, vision); others DELETE |
| `libs/model-adapter-api` `{CompletionRequest,CompletionResponse,ModelInfo,ModelAdapterException}` | KEEP | `finance.trove.ai.client` |
| `libs/model-adapter-api` `{ModelAdapter,ModelCapability,EmbeddingRequest,EmbeddingResponse,OcrResult}` + test | DELETE | no adapter registry, no embeddings, no OCR wire type |

### 2.12 `plugins/*`

| Source | Action | Destination / reason |
|---|---|---|
| `plugins/bank-sources/simplefin` `{SimplefinClient,SimplefinNormalizer,SimplefinPlugin,SimplefinProperties,dto/*}` | REWRITE | `finance.trove.ingestion.simplefin`: M0 fix (`tx.id()` → `providerTransactionId`); timeouts wired; accessUrl checked by `OutboundUrlPolicy` with the pinned-DNS resolver (§2.8); send `start-date` to the bridge (high-watermark) |
| `plugins/bank-sources/simplefin` `SimplefinAutoConfiguration` + imports file | DELETE | classes become `@Component` |
| `plugins/bank-sources/ofx` `{OfxPlugin,OfxParser,OfxFormatDetector,OfxSgmlParser,OfxXmlParser,OfxNormalizer,dto/*}` | REWRITE | `finance.trove.ingestion.ofx`: M0 fix (`fitId` → `providerTransactionId`); parses `ACCTID` into `connected_account.external_account_id` so a repeat OFX/QFX import of the same file **reuses** the account instead of creating a second one with duplicate transactions (§3 V16, blocker — `OfxPlugin.java:113-114` today parses `accountId` and discards it); `DEFAULT_CURRENCY` removed — `CURDEF` parsed, else account currency; upload base from `trove.storage.root/uploads`; SGML honours header `CHARSET` |
| `plugins/bank-sources/ofx` `OfxAutoConfiguration` + imports | DELETE | |
| `plugins/bank-sources/{imap,bank-emails}` (all files, tests, `bank-email-templates.yml`) | DELETE | cut channels; note `ReDoSGuard` pattern reused in the CSV header sniffer |
| `plugins/models/{ollama-adapter,paddleocr-adapter}` (all) | DELETE | |
| `plugins/models/litellm-adapter` `dto/{ChatCompletionRequest,ChatCompletionResponse,ModelListResponse}` | KEEP (copy) | `finance.trove.ai.client.dto` — wire shapes for `OpenAiCompatibleClient`; rest of module DELETE |
| `plugins/bank-sources/README.md`, `plugins/models/README.md` | DELETE | |
| tests: simplefin (3), ofx (6) | KEEP | + normalizer tests assert `providerTransactionId` is per-transaction |

### 2.13 Tests — mechanical rule

Every `*Test.java`/`*IT.java` not listed above moves to `core/src/test/java/` under the new package of its subject.
The nine `support/BaseIT.java` + `support/JwtTestHelper.java` copies collapse to one `finance.trove.support.BaseIT`
(Testcontainers `postgres:16-alpine`, one Flyway history) and one `JwtTestHelper`. The nine `OpenApiTest` classes become
one `finance.trove.OpenApiTest` writing `openapi/trove-core.yaml` only under `-Dtrove.openapi.write=true` (CI never sets it;
a separate `generate-openapi` workflow step does). `docker-java.properties` (5 copies) → one.

---

## 3. Database plan — one schema, one Flyway history

> **Numbering re-based at landing time (2026-09-04, wave A).** The V-numbers in the table below from **V16 upward are the
> plan's ORIGINAL numbers and are no longer the real ones**. Flyway needs a dense, collision-free history in *landing* order,
> and the steps landed in a different order than the table assumed. Landed so far: V1–V9_2 (steps 1–3), **V11–V13 = step 4**
> (`audit_log`, `rate_limit_config`, `shedlock` — V10 `user_preferences` was never written: preferences live on `users`),
> **V14–V15 = step 5** (`category`, `merchant_category_map`), **V16–V17 = step 12** (`ai_settings`, `ai_audit_log` — the rows
> listed below as V37/V40; V38/V39 were not needed). **Next free number: V18 (step 6 starts there).** Every later step takes the
> next free numbers at landing time, keeps the table's *columns and constraints*, and records its real numbers in
> `06-PROGRESS.md`; do not read a number below as an assignment.


Location `core/src/main/resources/db/migration/`. No production data exists, so this is a clean rewrite. Global rules:
every `user_id` is `UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE` (nullable only where noted; on ledger-owned
tables it now means "created/owned by", not "scoped by" — see the tenancy note below); every ledger-owned table also
carries `household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE`; every money column is `NUMERIC(19,4)`;
every `currency` is `VARCHAR(3) NOT NULL` with **no default**; all timestamps `TIMESTAMPTZ`; index names are prefixed by
table to avoid the `idx_audit_log_*` collision; every `UNIQUE` constraint that can legitimately hold a NULL member
(a global seed row, an unset `page_no`) is declared `NULLS NOT DISTINCT` (Postgres 16, pinned in §2.13) so the seed/global
row is actually unique and `ON CONFLICT` can target it — the plain-`UNIQUE` default treats NULLs as distinct from each
other, so `receipt_image`, `merchant_category_map`, and `tax_line_mapping` below would otherwise silently admit
duplicate global/unset rows (§9 risk 6 extended). Source-file counts below are corrected from
`docs/recovery/inventory/database.md`, which undercounts both the Flyway file total (34 vs the actual 42 — 5 gateway + 6
budget + 3 categorisation + 4 ingestion + 7 model-manager + 5 notification + 3 receipt + 1 report + 2 subscription + 6
tax-engine) and the surviving `user_id`-bearing table count (21 vs its own §4 table's 23); this plan's own migration
count (below) is the authority, not that document.

**Tenancy (owner decision, §1.5): one shared household ledger per install.** `households(id)` is the scoping root; every
`user.household_id` is set once (registration/invite, first-run bootstrap creates the install's one household) and never
user-editable. Every repository over a `household_id`-bearing entity extends `UserScopedRepository<T>` (§2.1) exposing
only `*AndHouseholdId` finders — `findById`/`findAll`/`deleteById` on those entities is an ArchUnit violation (§9 risk 2).
Auth/session/AI-endpoint/notification-preference/webhook/audit tables stay `user_id`-scoped (below), since those are
per-member settings, not shared ledger data.

| # | File | Source | Changes vs source |
|---|---|---|---|
| V1 | `V1__extensions.sql` | any V1 | `CREATE EXTENSION IF NOT EXISTS pgcrypto`, `pg_trgm` (gap P2-15 search) once |
| V2 | `V2__households.sql` | NEW (owner decision — shared household ledger) | `households(id, name, created_at)` |
| V3 | `V3__users.sql` | NEW | `users(id, household_id UUID NOT NULL FK, role VARCHAR(10) NOT NULL DEFAULT 'ADMIN' CHECK IN ('MEMBER','ADMIN'), email UNIQUE (lower), display_name, currency VARCHAR(3) NOT NULL, country VARCHAR(2) NOT NULL CHECK IN ('CA','US'), timezone VARCHAR(64) NOT NULL DEFAULT 'UTC', is_active, created_at, updated_at)`; unique index `users_email_lower_idx`; **`currency`/`country` live here, not on `user_preferences`** — they are identity-level, set once at registration (§4.1 `{email,password,displayName,currency,country}`), and five other packages (`DashboardController`, `ParsingStage`, `TaxSplitStage`, `ChatController`, `WeeklyInsightScheduler`) read `users.currency`/`users.country` directly; leaving them only on `user_preferences` as originally drafted pointed those five reads at columns that would not exist (major); `role` is the per-household admin flag (invites, `/v1/admin/settings`, registration policy) — at least one `ADMIN` per household is enforced at the service layer on demote/remove |
| V4 | `V4__local_credential.sql` | NEW | `local_credential(user_id PK FK, password_hash TEXT, password_updated_at, failed_attempts INT DEFAULT 0, locked_until)` (gap P2-4/P2-5) |
| V5 | `V5__totp_credential.sql` | NEW | `totp_credential(user_id PK FK, secret_encrypted TEXT, enabled BOOL, recovery_codes_hashed TEXT[], confirmed_at, created_at)` |
| V6 | `V6__oidc_link.sql` | NEW | `oidc_link(id, user_id FK, provider VARCHAR(50), provider_sub VARCHAR(255), email_at_link, linked_at, UNIQUE(provider, provider_sub))` |
| V7 | `V7__refresh_token.sql` | NEW (replaces Valkey `RefreshTokenStore`) | `refresh_token(id, user_id FK, token_hash VARCHAR(128) UNIQUE, family_id UUID, issued_at, expires_at, revoked_at, replaced_by UUID, ip_address VARCHAR(45), user_agent VARCHAR(500))`; index `(user_id, expires_at)` |
| V8 | `V8__login_attempt.sql` | NEW (gap P2-5) | `login_attempt(id, email_hash CHAR(64), ip INET, success BOOL, at TIMESTAMPTZ)`; index `(email_hash, at)`, `(ip, at)` |
| V9 | `V9__security_event.sql` | NEW (gap P2-17) | `security_event(id, user_id UUID NULL FK SET NULL, actor_id UUID NULL FK SET NULL, type VARCHAR(40) NOT NULL CHECK IN ('LOGIN_OK','LOGIN_FAIL','LOCKOUT','TOTP_ENROL','TOTP_DISABLE','PASSWORD_CHANGE','SESSION_REVOKE','BANK_CONNECTION_ADD','BANK_CONNECTION_REMOVE','EXPORT','ACCOUNT_DELETE','ADMIN_INVITE','KEY_ROTATE','EGRESS_SETTING_CHANGE'), ip INET, ua_summary VARCHAR(200), metadata JSONB, created_at)`; app DB role is INSERT/SELECT only (no UPDATE/DELETE) |
| V10 | `V10__user_preferences.sql` | gateway V5 | `user_preferences(user_id PK FK, region VARCHAR(3), locale VARCHAR(10), timezone_source VARCHAR(10) CHECK IN ('AUTO','MANUAL'), onboarding_completed BOOL, created_at, updated_at)`; `currency`/`country` moved to V3 `users`; `deployment`/`ai_mode` dropped; redundant index dropped |
| V11 | `V11__audit_log.sql` | gateway V1+V3 | `user_id` nullable FK — **no** `ON DELETE SET NULL` cascade fired synchronously from the request thread: `AuditLogFilter.saveAsync` runs on `auditExecutor` and a hard FK would drop an audit row whenever the JWT's `sub` no longer resolves (deleted user, stale token) instead of recording it (minor); keep the column an **unconstrained, indexed** UUID and reconcile via a nightly integrity check, not a live FK; indexes `audit_log_user_id_idx`, `audit_log_created_at_idx`; `path` stores the **route template** (`/v1/accounts/{id}`), never the raw URI, so an OIDC code or query string in the original request is never persisted (gap P2-17) |
| V12 | `V12__rate_limit_config.sql` | gateway V2+V4 | final seed only: `/api/v1/auth/login` 10/20 per-IP, `/api/v1/auth/register` 5/10, `/api/v1/auth/**` 60/120, `/api/v1/chat/**` 60/120, `/api/v1/ai/**` 30/60, `/api/v1/receipts/upload` 30/60, `/api/v1/accounts/*/sync` 10/20, `/api/**` 600/1200 |
| V13 | `V13__shedlock.sql` | model-manager V7 = notification V5 | one copy |
| V14 | `V14__category.sql` | categorisation V1 | adds `household_id UUID NULL FK` (NULL = system category); replaces the global `name UNIQUE` with `UNIQUE (name) WHERE household_id IS NULL` **and** `UNIQUE (household_id, name)` — the source table has no tenancy column at all and a single global-unique name, so every household would see, collide on, and be able to delete every other household's custom categories under the shared-ledger model (§9 risk 2 extended, major); + 51 system seed rows (`household_id NULL`) |
| V15 | `V15__merchant_category_map.sql` | categorisation V2 | `+ user_id UUID NULL FK` (merchant rules stay **per-user**, not household — a raw merchant string can be a counterparty's/employer's name); `UNIQUE (merchant, match_type, user_id) NULLS NOT DISTINCT` (so the global seed and per-user LLM write-back are both actually unique and `ON CONFLICT`-able — the source table has no uniqueness beyond its PK, so every confident LLM answer for a repeat merchant would insert another row); seeded from `community-merchant-registry.json` (20 rows, `user_id NULL`) **and** `registry/merchant-db.json`'s CA entries via an explicit slug→`category.name` mapping stated here — `food.coffee`→Coffee Shops, `health.pharmacy`→Pharmacy, `food.grocery`→Groceries, `shopping.online`→Shopping, `food.delivery`→Restaurants (no delivery category exists; V14 does not add one) — the source registry's `category_id` values are slugs matching none of V14's 51 `category.name` seeds, so an unmapped `INSERT … SELECT id FROM category WHERE name = <slug>` yields NULL into a NOT NULL FK and halts Flyway on every boot (blocker); repeated aliases across the two source files (e.g. `TIM HORTONS`) are deduplicated to one row each, inserted `ON CONFLICT DO NOTHING` |
| V16 | `V16__connected_account.sql` | ingestion V1 | `account_type` CHECK adds `CREDIT_CARD`, removes `CREDIT`; `source_kind` CHECK IN ('SIMPLEFIN','OFX','CSV','MANUAL'); `config_encrypted` nullable (manual/CSV/OFX accounts have no config blob — the entity's `@Column(nullable=false)` changes with it, §2.2); `sync_status` CHECK adds `SYNCING`; adds `external_account_id VARCHAR(255) NULL` + `UNIQUE (household_id, source_kind, external_account_id) NULLS NOT DISTINCT` — without this, a SimpleFIN re-exchange (bridge token rotation, retry, second device) or a repeat OFX/CSV upload has nothing to look up and re-use, so it creates a second account and duplicates every transaction on it, past what the exact-match dedup key can catch (§6 M0, blocker); no currency default; `household_id` FK (this is the account-ownership scope now, not `user_id`) |
| V17 | `V17__transaction.sql` | ingestion V2 | `source_id` → `provider_transaction_id` (partial UNIQUE `(account_id, provider_transaction_id)` kept); `tags_json TEXT` → `tags JSONB`; `category_id` FK → `category` (`ON DELETE SET NULL`); `type` CHECK **widens** from `('DEBIT','CREDIT')` to all 7 values of `finance.trove.common.plugin.TransactionType` (`DEBIT,CREDIT,TRANSFER,FEE,INTEREST,REFUND,OTHER`) — the shared enum already carries all seven, and the new CSV column-mapping (§6) can legitimately produce any of them, so the narrower CHECK from source would reject valid imports (minor; D1's aggregate is written against the full set, §2.5); `manual BOOL DEFAULT false`; `transfer_group_id UUID NULL` + index (gap P2-14 — pairs opposite legs of an internal transfer so budgets/dashboard/export can exclude them); `import_batch_id UUID NULL FK` (gap P2-12); `receipt_id` added later by V27 (ALTER); `household_id` FK |
| V18 | `V18__sync_log.sql` | ingestion V3+V4 | `status VARCHAR(20)` CHECK IN ('RUNNING','SUCCESS','PARTIAL_FAILURE','FAILED') **and** `SyncOrchestrator`'s three `setStatus` call sites are renamed to match (§2.2) — source writes `'OK'`/`'ERROR'`, which this CHECK would reject on every sync the moment the orchestrator becomes transactional, rolling back every ingested row with it (major); `sync_log_user_id_idx` added |
| V19 | `V19__category_override.sql` | categorisation V3 | `transaction_id` FK → `transaction ON DELETE CASCADE` — this migration's FK requires `transaction` (V17) to already exist, so it and the rest of the `categorisation` package that reference it move to the same execution-order step as `transaction`, not an earlier parallel one (§7, major) |
| V20 | `V20__import_batch.sql` | NEW (gap P2-12) | `import_batch(id, household_id FK, account_id FK, source_kind CHECK IN ('CSV','OFX'), mapping_json JSONB, row_count INT, new_count INT, dup_count INT, status CHECK IN ('PREVIEW','COMMITTED','UNDONE'), created_at)` |
| V21 | `V21__budget_period.sql` | budget V1 | NUMERIC(19,4); no currency default; `household_id` FK |
| V22 | `V22__envelope.sql` | budget V2 | `category_id` FK → `category`; NUMERIC(19,4); no default; `household_id` FK |
| V23 | `V23__savings_goal.sql` | budget V3 | same treatment; `household_id` FK |
| V24 | `V24__net_worth_snapshot.sql` | budget V4 | UNIQUE `(household_id, snapshot_date, currency)`; generated column kept; `household_id` FK |
| V25 | `V25__asset.sql` | budget V5 | `account_id` FK → `connected_account ON DELETE SET NULL`; `household_id` FK |
| V26 | `V26__liability.sql` | budget V6 | same as asset; `household_id` FK |
| V27 | `V27__receipt.sql` | receipt V1+V3 | status CHECK without `QUEUED`/`OCR` (`VisionExtractionStage.stageName()` = `EXTRACTING`, an existing value, §2.6); `source` CHECK IN ('UPLOAD','SCAN'); `image_key`/`thumbnail_key` dropped; `matched_transaction_id` FK → `transaction ON DELETE SET NULL`; `extracted_currency` NOT NULL default removed (set by pipeline); `household_id` FK; plus `ALTER TABLE transaction ADD receipt_id UUID REFERENCES receipt(id) ON DELETE SET NULL` |
| V28 | `V28__receipt_image.sql` | NEW (owner-requested `receipt_images`) | `receipt_image(id, receipt_id FK CASCADE, kind CHECK IN ('ORIGINAL','THUMBNAIL','PAGE'), page_no INT NOT NULL DEFAULT 0, file_path TEXT, mime_type VARCHAR(100), size_bytes BIGINT, sha256 CHAR(64), created_at)`; `UNIQUE (receipt_id, kind, page_no)` (plain `page_no` default of `0`, not `NULL`, sidesteps the NULLS-distinct trap directly since every ORIGINAL/THUMBNAIL row now shares the same non-null value and is still caught by the constraint) |
| V29 | `V29__line_item.sql` | receipt V2 | NUMERIC(19,4) |
| V30 | `V30__detected_subscription.sql` | subscription V1 | `transaction_ids` column dropped; UNIQUE `(household_id, merchant_name)` (SD-5, corrected from per-user to per-household to match the shared ledger); `category_id` FK; no currency default; `household_id` FK |
| V31 | `V31__subscription_transaction.sql` | NEW | `subscription_transaction(subscription_id FK CASCADE, transaction_id FK CASCADE, PRIMARY KEY(subscription_id, transaction_id))` |
| V32 | `V32__subscription_alert.sql` | subscription V2 | `household_id` FK |
| V33 | `V33__notification_preference.sql` | notification V1 | `tax_reminder_enabled`, `apns_token`, `fcm_token` dropped; **no push-token column of any kind** (owner decision — no push until a direct APNs/FCM path exists, no Expo relay); redundant index dropped; stays `user_id`-scoped (per-member preference) |
| V34 | `V34__notification_log.sql` | notification V2 | `channel VARCHAR(12)` CHECK IN ('EMAIL','IN_APP') (no push channel); `type VARCHAR(40)` — **widened from source's `VARCHAR(20)`**, not left at 20: the CHECK list adds `RECEIPT_PROCESSED` and `SUBSCRIPTION_PRICE_CHANGE` (25 chars), which does not fit the source column width or the `NotificationLog` entity's `length=20`, so `CREATE TABLE` would succeed but every insert of that type would fail `value too long for character varying(20)` and silently drop every price-change notification (major); `type` CHECK also drops `TAX_REMINDER`; `user_id`-scoped |
| V35 | `V35__in_app_notification.sql` | notification V3 | user FK |
| V36 | `V36__webhook.sql` | notification V4 | `webhook_subscription` + `webhook_delivery_log`, user FK (developer feature, stays per-user, not household) |
| ~~V37~~ **landed as V16 (step 12)** | `V16__ai_settings.sql` | NEW | `ai_settings(user_id PK FK, enabled BOOL, endpoint_url TEXT, api_key_encrypted TEXT, chat_model VARCHAR(200), vision_model VARCHAR(200), allow_private_endpoint BOOL DEFAULT false, privacy_class CHECK IN ('LOCAL','CLOUD'), last_test_at, last_test_ok BOOL, created_at, updated_at)`; **stays per-user**, not household — owner decision: the AI endpoint (and its API key) is configured by each household member individually, not shared; `allow_private_endpoint` is this user's own opt-in to a LAN/private target, gated globally by `TROVE_EGRESS_ALLOW_PRIVATE` (§1.6, §6) |
| V38 | `V38__coach_conversation.sql` | model-manager V3+V4+V5 (final shape) | `session_id UUID NOT NULL` (bare correlation id — decision: chat sessions are not refresh tokens, no FK); user FK; only the two V5 indexes |
| V39 | `V39__coach_insight.sql` | model-manager V6 | user FK |
| ~~V40~~ **landed as V17 (step 12)** | `V17__ai_audit_log.sql` | model-manager V2 | `user_id` nullable FK (`SET NULL`); indexes renamed `ai_audit_log_*`; `endpoint_host VARCHAR(255)` replaces `provider`/`alias` |
| V41 | `V41__tax_export_run.sql` | report V1 | `tax_export_run(id, user_id FK, tax_year INT, jurisdiction VARCHAR(2), format CHECK IN ('CSV','PDF'), status CHECK IN ('PENDING','GENERATING','COMPLETE','FAILED'), file_path TEXT, file_size_bytes BIGINT, error_message TEXT, created_at, updated_at)`; `report_type`, `requested_filters`, `period_*` dropped; stays per-user (a tax filing is individual even inside a shared household ledger) |
| V42 | `V42__tax_line_mapping.sql` | NEW | `tax_line_mapping(id, jurisdiction VARCHAR(2), category_id FK, user_id UUID NULL FK, line_code VARCHAR(20), line_label VARCHAR(200), deductible BOOL, UNIQUE(jurisdiction, category_id, user_id) NULLS NOT DISTINCT)`; seeded CA + US rows for the 51 system categories (label-only groupings, no rates) |

Dropped tables (never recreated): `tax_profile`, `tax_deduction`, `tax_slip`, `rrsp_tfsa_fhsa_room`, `tax_calculation_cache`,
`model_registration`, `report` (superseded by `tax_export_run`), gateway `user_preferences` shape, `push_device` (never
built — owner decision, no push relay in v1).
FKs that become enforceable: `transaction.category_id`, `envelope.category_id`, `detected_subscription.category_id`,
`category_override.transaction_id`, `asset.account_id`, `liability.account_id`, `receipt.matched_transaction_id`,
`transaction.receipt_id`, `subscription_transaction.*`, `merchant_category_map.user_id`, `tax_line_mapping.category_id`,
and every `user_id` column, enumerated (31): `local_credential`, `totp_credential`, `oidc_link`, `refresh_token`,
`user_preferences`, `audit_log` (unconstrained, see V11), `merchant_category_map`, `transaction` (owner), `sync_log`,
`category_override`, `receipt` (owner), `detected_subscription` (owner), `subscription_alert` (owner),
`notification_preference`, `notification_log`, `in_app_notification`, `webhook_subscription`, `ai_settings`,
`coach_conversation`, `coach_insight`, `ai_audit_log`, `tax_export_run`, `tax_line_mapping`, plus `connected_account`,
`budget_period`, `envelope`, `savings_goal`, `net_worth_snapshot`, `asset`, `liability` where it now denotes
creator/owner rather than scope (`household_id` scopes those seven) — `SchemaDefaultsIT` asserts via
`information_schema` that every column literally named `user_id` or `household_id` carries an FK to `users`/`households`
respectively. **Migration count: 42.**

---

## 4. Route map (`/api` context path + `/v1/…`)

Identity is `JWT` (principal) for every non-public row; `none` = permitAll. Consumers: W = web, M = mobile, B = both,
S = server/ops only. Status CHANGED lists what changed; every client call that must move is listed in §4.13.

### 4.1 Auth — `finance.trove.auth.AuthController`, `MeController`

Envelope rule for this whole table, stated explicitly because the original draft left it ambiguous while every other
route family uses `ApiResponse` (§5.3/§5.4, major): **every `/v1/auth/*` and `/v1/admin/invites*` body is the same
`ApiResponse` envelope as everywhere else** — there is no bare/legacy shape carved out for auth.

| Method | Path | Source | Identity | Consumers | Status |
|---|---|---|---|---|---|
| POST | `/v1/auth/register` | NEW | none, requires a valid invite token unless `registrationOpen` (policy-gated, §1.5) | B | NEW — `{email,password,displayName,currency,country,timezone,inviteToken?}` → 201 + tokens/cookies + `user:{id,email,displayName,householdId,totpEnabled}` |
| POST | `/v1/auth/login` | NEW | none | B | NEW — `{email,password}`; `X-Trove-Auth-Mode: cookie` selects cookie mode; bearer-mode body is `{accessToken,refreshToken,expiresIn,user:{id,email,displayName,totpEnabled}}` (§1.5 — the user object closes the gap where mobile's session store needs `user.id`/`user.name` and previously had no source for it); may return `{challengeToken}` (`typ=totp_challenge`, §1.5) instead when TOTP is enabled |
| POST | `/v1/auth/login/totp` | NEW | none (`typ=totp_challenge` JWT only — a full access token is rejected here, §1.5) | B | NEW — `{challengeToken, code}` (TOTP code or a recovery code) |
| POST | `/v1/auth/refresh` | gateway `AuthController#refreshToken` | none (refresh token) | B | CHANGED — Postgres store, rotation with a 30-s reuse-grace window (§1.5), camelCase body |
| POST | `/v1/auth/logout` | gateway `AuthController#logout` | none | B | CHANGED — revokes family; no `X-Authentik-End-Session` |
| GET | `/v1/auth/me` | gateway `MeController#me` | JWT (`typ=access` only) | B | CHANGED — camelCase `{userId,email,displayName,householdId,role,totpEnabled}` (was `user_id`; `displayName` is new — Settings/mobile session both need it and neither the original body nor the source `MeResponse` carried it, minor) |
| GET | `/v1/auth/providers` | NEW | none | B | NEW — `{local:true, oidc:{enabled,displayName}, registrationOpen}`; Login page gates the OIDC button and the closed-registration message on this, not a hardcoded link (minor — the source Login page links `/v1/auth/start` unconditionally, which 404s once OIDC is optional) |
| GET | `/v1/auth/start` | gateway `AuthController#startLogin` | none | W | CHANGED — 302 to `/api/oauth2/authorization/oidc`; 404 when OIDC disabled |
| POST | `/v1/auth/password` | NEW | JWT, step-up (current password) | B | NEW — change password (re-auth, gap P2-6); revokes all other sessions |
| POST | `/v1/auth/password/reset-request` (admin-only) · `/v1/auth/password/reset` | NEW | ADMIN / none (single-use token) | B | NEW — gap P2-8: admin generates a 1-h link; self-service only when `SMTP_ENABLED` |
| POST | `/v1/auth/totp/setup` · POST `/v1/auth/totp/confirm` · DELETE `/v1/auth/totp` (step-up) | NEW | JWT | B | NEW — gap P2-6 |
| POST | `/v1/auth/token` | gateway | — | — | DROP (Authentik id_token exchange) |
| GET | `/oauth2/authorization/oidc`, `/login/oauth2/code/oidc` | Spring Security | none | W | CHANGED — registration id `authentik` → `oidc`, conditional |
| GET/POST | `/v1/admin/invites` · DELETE `/v1/admin/invites/{id}` | NEW | ADMIN (household-scoped) | W | NEW — gap P2-7: single-use 72-h invite links, no SMTP required |

### 4.2 Users / preferences / admin — `finance.trove.users`
| Method | Path | Source | Identity | Consumers | Status |
|---|---|---|---|---|---|
| GET | `/v1/me/preferences` | gateway `UserPreferencesController#get` | JWT | B | CHANGED — `{region,locale,timezone,onboardingCompleted}`; `currency`/`country` move to `/v1/auth/me` and `PUT /v1/me` (they live on `users`, §3 V3, not `user_preferences`) |
| PUT | `/v1/me/preferences` | gateway `UserPreferencesController#put` | JWT | B | CHANGED — same fields, partial patch |
| PUT | `/v1/me` | NEW | JWT | B | NEW — `{displayName,timezone}` (identity fields `currency`/`country` are set once at registration and not user-editable afterward — every historical transaction/budget/export assumes a stable reporting currency) |
| GET | `/v1/me/export` (POST triggers, GET lists) · `DELETE /v1/me` | NEW | JWT, step-up | B | NEW — gap P2-16: full data export (zip) and in-app account deletion (7-day soft-delete grace); required for Apple 5.1.1(v)/Play account-deletion policy since sideload-only distribution (owner decision b) does not exempt the app from it if it is ever later listed |
| GET | `/v1/me/security-events` | NEW | JWT | B | NEW — gap P2-17, self-service "recent activity" |
| GET | `/v1/admin/users` · DELETE `/v1/admin/users/{id}` | NEW | ADMIN (household-scoped; refuses deleting the last ADMIN) | W | NEW |
| GET/PUT | `/v1/admin/settings` | NEW | ADMIN | W | NEW — `{registrationOpen}` |
| GET | `/v1/audit` | gateway `AuditLogRepository` (unwired) | ADMIN | W | NEW — D7 fixed; route template only, never raw URI (gap P2-17) |
| GET | `/v1/health` | NEW | none | B | NEW — `{status,version}` (D7) |
| GET | `/v1/status` | NEW | none | B | NEW — gap P1-1, unauthenticated detail-free `{status:UP\|DEGRADED,version}` for the `/welcome` degraded state |

### 4.3 Accounts — `finance.trove.accounts.AccountController`
| Method | Path | Source | Identity | Consumers | Status |
|---|---|---|---|---|---|
| GET | `/v1/accounts` | ingestion `AccountController#getAccounts` | JWT (household) | B | CHANGED — no `X-User-Id`; DTO without `configEncrypted`; `ApiResponse` envelope — **both** web's `listAccounts` and `exchangeSetupToken` calls move to the shared `unwrap()` helper in the same commit as this change (§4.13; the original draft enveloped this row but separately listed `exchange-setup-token` as client-unchanged, which is a direct contradiction — `accounts.ts` reads `res.data` bare today and Onboarding reads `mutation.data.accountIds.length`, both of which throw once the envelope lands, major) |
| POST | `/v1/accounts` | ingestion `#createAccount` | JWT | B | CHANGED — `{sourceKind, displayName, institution, accountType, currency}`; `sourceKind='MANUAL'` skips the exchange step entirely (gap P2-13) |
| GET | `/v1/accounts/{id}` · PUT `/v1/accounts/{id}` | NEW | JWT (household) | B | NEW |
| DELETE | `/v1/accounts/{id}` | ingestion `#deleteAccount` | JWT (household) | B | CHANGED — identity; 404 not 403 |
| POST | `/v1/accounts/{id}/sync` | ingestion `SyncController#triggerSync` (`/v1/sync/{accountId}`) | JWT (household) | B | CHANGED — path moved; SSRF-guarded; 409 if already `SYNCING` (§6 M0) |
| GET | `/v1/accounts/{id}/sync-logs` | NEW | JWT (household) | B | NEW |
| POST | `/v1/accounts/exchange-setup-token` | ingestion `ExchangeSetupTokenController` | JWT | B | CHANGED — identity; response now enveloped (see the `GET /v1/accounts` row above — both change together) |

### 4.4 Transactions & dashboard — `finance.trove.transactions`
| Method | Path | Source | Identity | Consumers | Status |
|---|---|---|---|---|---|
| GET | `/v1/transactions` | ingestion `TransactionController#getTransactions` | JWT (household) | B | CHANGED — identity; `page` (0-based, default `limit=50`, max `500` — stated explicitly here because neither bound was named in the original draft and the web daily-trend hook sends no `limit` at all, so an unstated default would silently truncate its chart, major), `sort` allowlist (`date,amount` × `asc,desc`), `from,to,type,accountId,categoryId,q` (pg_trgm, gap P2-15); `ApiResponse` + `PaginationMeta` |
| GET | `/v1/transactions/{id}` | NEW | JWT (household) | M | NEW |
| POST | `/v1/transactions` | NEW | JWT (household) | B | NEW — manual entry (gap P2-13): `{amount,date,merchant,categoryId,accountId,notes}`, `accountId` must be `source_kind='MANUAL'` |
| PATCH | `/v1/transactions/{id}` | ingestion `#patchTransaction` | JWT (household) | B | CHANGED — identity; `{categoryId,merchantName,notes,tags,transferGroupId}`; `categoryId` writes `transaction.category_id` directly and upserts `category_override` (§6 D4); mobile PUT→PATCH |
| DELETE | `/v1/transactions/{id}` | NEW | JWT (household) | B | NEW — manual rows only |
| GET | `/v1/dashboard/today` | ingestion `DashboardController#getToday` | JWT (household) | B | CHANGED — identity; boundaries from the caller's `users.timezone` via `UserClock` (gap P2-15); `{currency, spentToday, …, byCurrency:{…}}` |
| POST | `/v1/imports` (multipart csv/ofx/qfx) → `POST /v1/imports/{id}/commit` · GET `/v1/imports/{id}/preview` · DELETE `/v1/imports/{id}` | NEW (`ImportController`) | JWT (household) | B | NEW — gap P2-12: preview (detected header, per-column guesses, date-format candidates, new/exact-dup/fuzzy-dup counts) → commit; `DELETE` undoes unedited rows of a batch |

### 4.5 Categories — `finance.trove.categorisation`
| Method | Path | Source | Identity | Consumers | Status |
|---|---|---|---|---|---|
| GET | `/v1/categories` | categorisation `CategoryController#getAllCategories` | JWT (household) | B | CHANGED — envelope; errors propagate; serialises `CategoryDto{id,name,parentId,icon,isSystem}` — GET returned the raw JPA entity (nested `parent`, field `system`) as originally drafted, matching neither client's `parentId`/`isSystem` shape (§2.3, minor) |
| POST | `/v1/categories` | `#createCategory` | JWT (household) | B | CHANGED — `CategoryDto` request (G3) |
| PUT | `/v1/categories/{id}` | NEW | JWT (household) | B | NEW — `CategoryDto` |
| DELETE | `/v1/categories/{id}` | `#deleteCategory` | JWT (household) | B | KEEP — system categories still refuse; ownership check now by `household_id` |
| GET/POST | `/v1/categories/rules` · DELETE `/v1/categories/rules/{id}` | NEW (`CategoryRuleController`) | JWT | W | NEW — user merchant rules |
| POST | `/v1/categorise`, `/v1/categorise/batch` · PUT `/v1/categorise/override` | categorisation `CategorisationController` | — | — | DROP (in-process; override = `PATCH /v1/transactions/{id}`) |

### 4.6 Budgets & goals — `finance.trove.budgets`
| Method | Path | Source | Identity | Consumers | Status |
|---|---|---|---|---|---|
| GET/POST | `/v1/budgets` | budget `BudgetController#list/#create` | JWT | B | KEEP |
| GET | `/v1/budgets/current` | `#getCurrent` | JWT | W | CHANGED — 404 via `ResourceNotFoundException` |
| GET/PUT | `/v1/budgets/{id}` | `#get/#update` | JWT | B | KEEP |
| DELETE | `/v1/budgets/{id}` | NEW | JWT | M | NEW |
| GET/POST | `/v1/budgets/{id}/envelopes` | `#listEnvelopes/#createEnvelope` | JWT | B | KEEP (`spent` is now real) |
| GET/PUT/DELETE | `/v1/budgets/{id}/envelopes/{envelopeId}` | `#getEnvelope/#updateEnvelope/#deleteEnvelope` | JWT | B | KEEP |
| GET/POST | `/v1/goals` · PUT/DELETE `/v1/goals/{id}` | budget `GoalController` | JWT | B | KEEP |

### 4.7 Net worth — `finance.trove.networth.NetWorthController`
| Method | Path | Source | Identity | Consumers | Status |
|---|---|---|---|---|---|
| GET | `/v1/networth/series` | `#series` | JWT | B | CHANGED — `series[]` per currency |
| POST | `/v1/networth/snapshot` | `#snapshot` | JWT | B | KEEP |
| GET/POST | `/v1/networth/assets` · PUT/DELETE `/v1/networth/assets/{id}` | `#listAssets/#addAsset/#updateAsset/#deleteAsset` | JWT | B | KEEP |
| GET | `/v1/networth/liabilities` | NEW (BUD-02) | JWT | B | NEW |
| POST | `/v1/networth/liabilities` · PUT/DELETE `/v1/networth/liabilities/{id}` | `#addLiability/#updateLiability/#deleteLiability` | JWT | B | KEEP |

### 4.8 Subscriptions — `finance.trove.subscriptions.SubscriptionController`
| Method | Path | Source | Identity | Consumers | Status |
|---|---|---|---|---|---|
| GET | `/v1/subscriptions` · GET/DELETE `/v1/subscriptions/{id}` · GET `/v1/subscriptions/summary` · GET `/v1/subscriptions/alerts` · PATCH `/v1/subscriptions/alerts/{id}/read` | `#list/#get/#cancel/#summary/#alerts/#markRead` | JWT | B | KEEP (`summary.currency` = user currency) |
| POST | `/v1/subscriptions/scan` | NEW | JWT | B | NEW — manual trigger (D2) |

### 4.9 Notifications — `finance.trove.notifications`
| Method | Path | Source | Identity | Consumers | Status |
|---|---|---|---|---|---|
| GET `/v1/notifications` · GET `/v1/notifications/unread-count` · PATCH `/v1/notifications/{id}/read` · POST `/v1/notifications/read-all` · DELETE `/v1/notifications/{id}` | `InAppNotificationController` | JWT | B | KEEP |
| GET/PUT | `/v1/notifications/preferences` | `PreferenceController` | JWT | B | CHANGED — `taxReminderEnabled` removed |
| ~~POST `/v1/notifications/devices` · DELETE `/v1/notifications/devices/{token}`~~ | — | — | — | **DROP from the plan** — owner decision: no push notifications until a direct APNs/FCM path exists, no Expo relay; mobile polls `GET /v1/notifications/unread-count` and raises a client-local notification instead |
| GET/POST | `/v1/webhooks` · DELETE `/v1/webhooks/{id}` | `WebhookController` | JWT | W | KEEP |
| POST | `/internal/notifications/send` | `NotificationSendController` | — | — | DROP |

### 4.10 Receipts — `finance.trove.receipts`
| Method | Path | Source | Identity | Consumers | Status |
|---|---|---|---|---|---|
| POST | `/v1/receipts/upload` | `ReceiptUploadController#upload` | JWT (household) | B | CHANGED — local disk, re-encode/EXIF-strip/quota (gap P2-11); `X-Trove-Province` header removed (from preferences); response is **202 `{id,status,createdAt}`** — this shape is stated explicitly because it was unspecified as originally drafted and neither client's expectation matches it (web wants `{jobId,status}`, mobile wants a full `Receipt`, minor) |
| GET | `/v1/receipts` · GET `/v1/receipts/{id}` · GET `/v1/receipts/{id}/status` · DELETE `/v1/receipts/{id}` · PATCH `/v1/receipts/{id}/review` | `ReceiptController` | JWT (household) | B | KEEP (`get` returns `thumbnailUrl` path) |
| GET | `/v1/receipts/{id}/download` | `ReceiptController#download` | JWT (household) | B | CHANGED — streams bytes, no presigned URL; `Content-Disposition: attachment`, nosniff (gap P2-11) |
| GET | `/v1/receipts/{id}/thumbnail` | NEW | JWT (household) | B | NEW — bearer token required (mobile `<Image>` needs `headers:{Authorization}`, or the client fetches the bytes and renders a local blob URI — stated here because the original draft left this unresolved, minor) |
| PATCH | `/v1/receipts/{id}` | NEW | JWT (household) | B | NEW — manual fields + `matchedTransactionId` (AI-off path) |

### 4.11 AI — `finance.trove.ai` (per-user endpoint, owner decision)
| Method | Path | Source | Identity | Consumers | Status |
|---|---|---|---|---|---|
| GET/PUT | `/v1/ai/settings` · POST `/v1/ai/settings/test` | NEW (`AiSettingsController`) | JWT | B | NEW — API key write-only; `PUT` includes `allowPrivateEndpoint` (this user's own opt-in, gated by `TROVE_EGRESS_ALLOW_PRIVATE`, §1.6/§6) |
| POST | `/v1/chat/completions?stream=true` | model-manager `ChatController#complete` | JWT | B | CHANGED — auth works (M1); wire contract pinned (originally just "SSE via POST" with no frame shape, which matches neither client's SSE reader, major): request `{messages:[{role,content}]}`, response header `X-Session-Id` always set (request may supply it to continue a session), non-streaming or non-2xx responses are the normal JSON `ApiResponse` (check `response.ok` before treating the body as a stream), streaming frames are OpenAI-style `data: {"choices":[{"delta":{"content":…}}]}`, terminated by `data: [DONE]`, with an in-band `data: {"error":ErrorDetail}` frame on failure; 409 `AI_NOT_CONFIGURED` when the caller's own `ai_settings.enabled=false` |
| GET | `/v1/chat/suggestions` · GET/DELETE `/v1/chat/history` | `ChatController` | JWT | B | KEEP |
| GET | `/v1/insights` | `InsightController#list` | JWT | B | KEEP |
| `/v1/models*` (5), `/v1/embeddings`, `/v1/audit`(AI), `/internal/audit` | model-manager | — | — | DROP |

### 4.12 Tax export — `finance.trove.taxexport`
| Method | Path | Source | Identity | Consumers | Status |
|---|---|---|---|---|---|
| GET | `/v1/tax-export/lines?jurisdiction=` · PUT `/v1/tax-export/lines/{categoryId}` | NEW | JWT | W | NEW |
| POST/GET | `/v1/tax-export/runs` · GET/DELETE `/v1/tax-export/runs/{id}` · GET `/v1/tax-export/runs/{id}/download` | report `ReportController` (rewritten) | JWT | B | NEW/CHANGED — `{taxYear, format}` |
| `/v1/reports*` (4), `/v1/tax/**` (3) | report-service, tax-engine | — | — | DROP |

**Route count:** approximately 115 exposed `/api/v1` routes (auth+invites 15, users/admin/export 11, accounts 8,
transactions 10, categories 7, budgets 15, net worth 10, subscriptions 7, notifications 11 — one fewer than the original
count, push-device routes dropped, §4.9 — receipts 9, AI 7, tax export 7 — counting each METHOD separately), plus the
2 Spring OAuth2 endpoints; old routes dropped: `/v1/auth/token`, 3× `/v1/categorise*`, `/internal/notifications/send`,
5× `/v1/models*`, `/v1/embeddings`, `/v1/audit`(AI), `/internal/audit`, 4× `/v1/reports*`, 3× `/v1/tax/**` — this is an
architect-level estimate for scale, not a number CI enforces; `openapi/trove-core.yaml` (generated, §2.13) is the source
of truth.

### 4.13 Client contract changes (every current client call is accounted for)

| Client call today | Resolution |
|---|---|
| web `GET /api/v1/auth/me` (`user_id`) | field → `userId`; `authStore.ts:50` mapping updated; add `displayName` |
| web `POST /api/v1/auth/logout`, `/v1/auth/refresh` | unchanged path; wire logout to Settings/nav; add cross-tab refresh lock (`navigator.locks`/BroadcastChannel) so two tabs racing a 401 do not both rotate and trip family-revoke (§1.5, major) |
| web `/v1/dashboard/today`, `/v1/transactions`, `/v1/categories`, **`/v1/accounts`, `POST /v1/accounts/exchange-setup-token`** (bare bodies) | now `ApiResponse` envelope — `dashboard.ts`, `transactions.ts`, `categories.ts`, **`accounts.ts`** use `unwrap()` (the original draft enveloped `GET /v1/accounts` in §4.3 while separately calling `exchange-setup-token` "unchanged" in this row — a direct contradiction that would leave `accounts.ts`/`accounts.test.ts`/`Onboarding` reading a field that no longer exists; §4.3, major) |
| web amounts everywhere (`Transaction.amount`, `Envelope.allocated/spent`, `BudgetPeriod.totalIncome`, `DashboardResponse.*`, `DetectedSubscription.normalizedAmount`) | server serialises every `BigDecimal` as a **decimal string** (`"12.34"`), not a JSON number and not cents — the original draft's `Money`-serializer note covered only the `Money` wrapper type, but none of these fields use `Money`; today's mix of raw-decimal-number DTOs, web's cents-based `MoneyDisplay`, and mobile's `*Cents` integer types are three incompatible representations (§5.4, major) — delete every `*Cents` type and `MoneyDisplay(amount: string, currency)` via `Intl.NumberFormat` on both clients |
| web `/v1/budgets/current`, `POST /v1/budgets`, `POST /v1/budgets/{id}/envelopes`, `GET/PUT /v1/me/preferences` | unchanged (preferences fields change — `currency`/`country` move out to `/v1/auth/me`/`PUT /v1/me`, §4.2) |
| web `error.field_errors` | → `error.fieldErrors` (camelCase, matching everything else; the gateway is the one place SNAKE_CASE actually runs today, §5.3/§5.4, minor) |
| web `/api/accounts`, `/api/budgets`, `/api/transactions`, `/api/receipts*`, `/api/subscriptions`, `/api/networth/history`, `/api/tax/*`, `/api/coach/*` (Generation B) | hooks deleted; pages rewritten on Generation A against §4.3–4.12 |
| web `GET /api/coach/stream?q=` and `useCoachSession` | replaced by `POST /v1/chat/completions?stream=true` with `fetch` + `ReadableStream` SSE parser on the pinned frame contract (§4.11) |
| mobile `POST /api/auth/login`, `GET /api/auth/me` | → `/api/v1/auth/login` (bearer mode; body now includes `user`, §4.1), `/api/v1/auth/me`; add single-flight refresh (mobile has no refresh flow at all today — a naive add-refresh without serialising concurrent 401s would trip family-revoke on two parallel requests, §1.5, major), logout, TOTP step |
| mobile 12 prefix-only calls | add `/api/v1`; `PUT /transactions/{id}` → `PATCH` |
| mobile `GET /api/transactions/{id}`, `DELETE /api/budgets/{id}` | now real: `/v1/transactions/{id}`, `DELETE /v1/budgets/{id}` |
| mobile `POST /api/notifications/device` | **DROP, not rewired** — owner decision: no push relay in v1 (§4.9); delete `registerPushToken.ts`, the Expo push permission prompt, and the device-registration call site entirely |
| mobile `/api/net-worth/history` | → `/v1/networth/series` |
| mobile `Budget{name,category,limitCents,spentCents,period}`, `Subscription{name,amountCents,billingCycle:'monthly'|'annual',…}`, `Transaction{merchant,amountCents,category,tags[]}`, `Receipt` types | `src/api/types.ts` is **rewritten wholesale** against the real server DTOs (`BudgetPeriod`+`envelopes[]`, `DetectedSubscription` with `WEEKLY/BIWEEKLY/MONTHLY/…` cycles, decimal-string amounts not `*Cents`, `ReceiptResponse`/`ReceiptStatus`) — the mobile types as they exist today model a different, flatter shape than any KEPT server DTO, so "KEEP" for the Budgets/Subscriptions endpoints in §4.6/§4.8 is not true of the mobile client without this rewrite (§7 step 21, major); Budgets/Subscriptions/Transactions/Receipts screens are rewritten with it |
| mobile `/api/model-routing` (GET/PUT), `/api/tax/year` | deleted with the screens; AI settings → `/v1/ai/settings` |
| mobile setup screen (CAD/USD/GBP/EUR, email/OFX channel picker) | CAD/USD only; email channel picker removed (cut per owner decisions); `setup.tsx` now `POST`s `{currency,country,timezone}` to `/v1/auth/register` — as originally drafted onboarding wrote only to `SecureStore` and never reached the server, so a mobile-onboarded user had no server-side currency at all (§7 step 21, major) |
| mobile `GET /api/coach/stream?message=` (`react-native-event-source`, waits for a named `'done'` event) | `fetch`-based POST SSE reader on the pinned frame contract (`data: [DONE]`, not a named event, §4.11); **drop** the `crypto.randomUUID`/`react-native-get-random-values` polyfill — its only call site generates a client-side session id, and the server now generates and echoes `X-Session-Id` (§4.11), so nothing on the client needs to mint a UUID any more (minor) |

---

## 5. Bean / config dedup

1. **One `SecurityConfig`** (`finance.trove.app.SecurityConfig`, from `GatewaySecurityConfig` + `libs/common`'s headers):
   filters constructed here (none are `@Component`, so Boot cannot double-register them — the `FilterRegistrationBean`
   trick is unnecessary and the 7-service double-registration bug cannot recur). **Corrected filter order** (the original
   draft put `RateLimitFilter` before `CookieJwtAuthFilter`, which means the limiter never sees a principal and every
   authenticated request falls into the flat anonymous IP bucket — §9 risk 20's "11th login → 429" test cannot pass
   against a bucket sized for that traffic, and per-user `rate_limit_config` rows are unreachable; blocker):
   `RequestIdMdcFilter` → `CookieJwtAuthFilter` → `RateLimitFilter` (now IP-**and**-path-matched for anonymous callers,
   not a flat 20/20 bucket, so the V12 `/v1/auth/login` 10/20-per-IP seed is actually reachable) → `CsrfRequiresHeaderFilter`
   (enforced only when `trove.authSource==COOKIE`, §2.1) → `CsrfCookieFilter` (materialises the CSRF cookie for anonymous
   callers too) → Spring `CsrfFilter` with `CookieCsrfTokenRepository.withHttpOnlyFalse().setCookiePath("/")` +
   `SpaCsrfTokenRequestHandler` (ignored for bearer requests via `RequestMatcher`; §1.5, blocker) → `AuditLogFilter`
   (REWRITE, no `ContentCachingResponseWrapper`, §2.1). `permitAll`: `/v1/auth/{register,login,login/totp,refresh,logout,
   providers,start,password/reset}`, `/v1/health`, `/v1/status`, `/oauth2/**`, `/login/oauth2/**` (OIDC only), `/error`.
   `hasRole('ADMIN')`: `/v1/admin/**`, `/v1/audit`. Everything else authenticated. `authenticationEntryPoint` **and**
   `accessDeniedHandler` both write `ApiResponse.error(ErrorDetail)` via the shared `ObjectMapper` — this closes the gap
   where an admin-route 403 today falls through to a bare 500 (gap P2-7). Client IP for the limiter, audit, and every
   `security_event` row comes from `request.getRemoteAddr()` **after** a Tomcat `RemoteIpValve`/`ForwardedHeaderFilter`
   configured with `internal-proxies`/`trusted-proxies` = the compose network only — raw `X-Forwarded-For` is never
   parsed by hand (§1.5 gap P2-5; the source `RateLimitFilter`/`AuditLogFilter` both take the first `X-Forwarded-For`
   value unconditionally today, which is spoofable by any client that appends its own hop, minor). Headers: HSTS
   1y+subdomains, **`Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
   img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'`** (the
   original draft's `default-src 'none'` decorates only the JSON API — nginx/Traefik in front of the SPA's `index.html`
   emit no CSP at all as drafted, so the public-internet finance UI would ship with no script-src/connect-src
   restriction, the one header that most limits XSS blast radius; major — add the same header to the Traefik
   `security-headers` middleware/nginx `add_header` for `/`, not just the app), `Permissions-Policy`, nosniff, frame DENY,
   referrer strict-origin-when-cross-origin — kept at app level even though Traefik repeats them.
   `SessionCreationPolicy.STATELESS` always (OIDC state in a cookie). `@EnableMethodSecurity` on.
2. **One JWT filter**: `CookieJwtAuthFilter` (bearer wins, cookie fallback; sets `trove.authSource`); `libs/common.JwtAuthFilter`
   deleted; `/internal/**` bypass deleted. `JwtProperties.validate()` rejects short secrets and the shipped default literal.
   `JwtAuthenticationProvider` requires `typ=access` (§1.5).
3. **One `GlobalExceptionHandler`** with the 7 existing handlers + `ResponseStatusException` (its own status),
   `AccessDeniedException` (403), `AuthenticationException` (401), `MaxUploadSizeExceededException` (413),
   `DataIntegrityViolationException` (409), `OptimisticLockException` (409). Tested with `@SpringBootTest`, not a slice.
4. **One Jackson strategy**: Jackson 3 defaults — **camelCase**, `FAIL_ON_UNKNOWN_PROPERTIES=false`, `WRITE_DATES_AS_TIMESTAMPS=false`,
   every `BigDecimal` on the wire (not only the `Money` wrapper — `Transaction.amount`, `Envelope.allocated/spent`,
   `BudgetPeriod.totalIncome`, `DashboardResponse.*`, `DetectedSubscription.normalizedAmount` are raw `BigDecimal` fields
   with no `Money` wrapper today, so a serializer scoped to `Money` alone leaves them as JSON numbers) via a **global**
   `BigDecimal` `ToStringSerializer` — plain decimal string (`"12.34"`), never a float, on every DTO; both clients drop
   their cents-based/`*Cents` representations for it (§4.13, major). Both clients are verified camelCase; the wire-format
   changes are the auth DTOs (`id_token/access_token/refresh_token/token_type/user_id` → camelCase) and `error.field_errors`
   → `error.fieldErrors` (the gateway is the one place SNAKE_CASE Jackson actually runs today via its own
   `@ComponentScan`, minor) — §4.13. `JacksonConfig` (SNAKE_CASE) deleted; the explicit `@JsonProperty("extracted_merchant")`
   overrides in receipt DTOs are removed so receipts become camelCase like everything else (web/mobile receipt types
   updated in the same step).
5. **One `RestClient` customizer** (`HttpClientConfig`): `RestClientCustomizer` → `HttpComponentsClientHttpRequestFactory`,
   connect 5 s, response 30 s, no redirects, `User-Agent: trove-core/<version>`, **a custom `DnsResolver` that only
   returns addresses `OutboundUrlPolicy` already approved for that specific call** (pin-by-resolution — closes the
   DNS-rebind TOCTOU where a check-time lookup and connect-time lookup can answer differently, §2.8, major); named
   builders: `aiRestClientBuilder` (response 120 s, streaming reads unbounded but connect 5 s), `webhookRestClientBuilder`
   (10 s/10 s), `simplefinRestClientBuilder` (5 s/30 s). ArchUnit forbids `RestClient.create(` / `RestClient.builder()`
   outside `HttpClientConfig`; a second ArchUnit rule restricts `aiRestClientBuilder` to `finance.trove.ai.client` (§9
   risk 10).
6. **Scheduling**: one `@EnableScheduling @EnableSchedulerLock(defaultLockAtMostFor="PT10M")` in `app.SchedulingConfig`
   with one `JdbcTemplateLockProvider` (V13 `shedlock`). Every `@Scheduled` carries `@SchedulerLock(name=…)` and
   `zone="UTC"` for the **trigger** (the cron fires on the container clock; the *content* each job computes — "today",
   "this week" — reads per-recipient `users.timezone` via `UserClock`, gap P2-15): `rate-limit-reload` (5 min),
   `envelope-rollover` (monthly), `networth-daily-snapshot` (23:30 UTC pass, per-user boundary inside), `subscription-scan`
   (02:00), `weekly-digest` (Mon 08:00 UTC pass, per-user boundary inside), `weekly-insight` (Mon 08:30), `webhook-retry`
   (30 s), `receipt-orphan-sweep` (hourly), `refresh-token-purge` (daily), `login-attempt-purge` (daily, gap P2-5),
   `invite-expiry-sweep` (daily, gap P2-7), `export-link-expiry-sweep` (daily, gap P2-16), `account-deletion-purge`
   (daily — finalises the 7-day soft-delete grace, gap P2-16). Lock names are distinct.
7. **Async**: one `@EnableAsync` in `app.AsyncConfig` with named bounded executors `receiptExecutor` (3/6/100,
   **`AbortPolicy`**, not `CallerRunsPolicy` — the source `ReceiptAsyncConfig` uses `CallerRunsPolicy`, which once the
   queue fills runs the entire pipeline, including a 120-s vision call, on the HTTP request thread itself, turning the
   upload endpoint into a thread-exhaustion vector for any authenticated user; a `TaskRejectedException` maps to 503 +
   `Retry-After` and the receipt stays `UPLOADED` for the hourly orphan sweep to re-enqueue, minor), `exportExecutor`
   (2/4/50, `AbortPolicy`), `auditExecutor` (1/2/1000, DiscardOldest), `coachExecutor` (4/8/50); `AsyncUncaughtExceptionHandler`
   from `ReportAsyncConfig` kept. No unqualified `@Async`.
8. **Rate limiting**: `bucket4j-core` local buckets in a Caffeine cache (`expireAfterAccess 10m`), key = `userId` when
   authenticated (now reachable — the filter runs after `CookieJwtAuthFilter`, item 1) else client IP, path-matched
   against `rate_limit_config` (V12 seeds) for both keys — not just a flat anonymous bucket, since the seeded per-route
   limits on `/v1/auth/login`/`/v1/auth/register` are meaningless otherwise (item 1, blocker); IP from
   `request.getRemoteAddr()` post-`RemoteIpValve` (item 1); reload every 5 min (now actually scheduled), 429 via
   `RateLimitException`; **`/v1/auth/**` checks are fail-closed** — a limiter error rejects the request rather than
   logging "failing open" as the source does (gap P2-5). Traefik `ratelimit` middleware (average 100/s, burst 200 per
   IP; auth router 5/s) is the outer layer; the app layer is the one that distinguishes users.
9. **Micrometer**: actuator on 9090 (`health,info,prometheus,metrics`), `health` groups `readiness` (db, flyway,
   `diskSpace(${TROVE_DATA_DIR}/receipts, 1GB)`, gap P1-1) / `liveness` (ping). Three real meters added so the Grafana
   JSON stops lying: `trove.receipt.stage` (Timer, tag `stage`), `trove.ai.invocation` (Timer, tags `kind`, `success`),
   `trove.notification.sent` (Counter, tags `channel`, `status`).
10. **CSRF**: see §1.5 and item 1 — cookie path `/`, `SpaCsrfTokenRequestHandler`, a `CsrfCookieFilter` that materialises
    the token for anonymous callers. Web axios config: `xsrfCookieName: 'XSRF-TOKEN', xsrfHeaderName: 'X-XSRF-TOKEN'`.
11. **CORS**: allowed origin `https://${trove.domain}` (+ `http://localhost:5173` under `dev` profile), credentials true.
12. **Storage**: `StorageConfig` creates `${trove.storage.root}/{receipts,exports,uploads}` on boot (the `MinioInitializer`
    idempotent pattern, on a filesystem), fails fast if not writable. Receipt/export/import multipart limits per §1.6.
13. **Graceful shutdown** (gap P1-2): `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=20s`;
    every `@Scheduled` batch job checks interruption between rows; compose `stop_grace_period: 30s` on `trove-core`,
    `60s` on `postgres`, `depends_on` orders the app to stop first.
14. **Deleted duplicates**: 10 `*SecurityConfig` shims, 4 `SchedulingConfig`, 2 `MinioStorageService`, 2 `MinioConfig`,
    2 `RestClientConfig`, 9 `BaseIT`, 9 `JwtTestHelper`, 9 `OpenApiTest`, 10 `application.yml`, 10 `logback-spring.xml`,
    10 application classes, 2 `PrivacyViolationException`, 2 `AiAuditLog`, 2 `TransactionDto`, 2 `ChatControllerIT`.

---

## 6. Feature wiring (the seams)

Events live in `finance.trove.common.events` as records published with `ApplicationEventPublisher` and consumed with
`@TransactionalEventListener(phase = AFTER_COMMIT)`; listeners that write open their own `REQUIRES_NEW` transaction.

**M0 — dedup key.** `RawTransaction(providerTransactionId, accountId, amount, currency, description, merchantName,
date, postedAt, type, pending, metadata)`; `SimplefinNormalizer` puts `tx.id()`, `OfxNormalizer` puts `fitId`, `CsvSource`
derives `sha256(accountId|date|amount|normalisedDescription|rowOrdinal)` (not `null` — see the fuzzy-pass fix below).
`DeduplicationService.deduplicate(accountId, incoming)` exact-matches `findByAccountIdAndProviderTransactionId`, then a
**corrected** fuzzy pass (±2 d, ±0.50, candidate must be `pending=true` **and** its own `providerTransactionId` must not
already appear in the current sync batch) — the source fuzzy pass matches any pending row in the window regardless of
the candidate's own id (two distinct same-day, same-amount pending transactions collapse into one) and requires
`pending=true` on a candidate while CSV rows are inserted `pending=false`, so a re-imported CSV file matched nothing and
duplicated every row; giving CSV a deterministic hash id moves it onto the exact-match path entirely and it no longer
needs the fuzzy pass at all (§2.2, blocker). Two overlapping account syncs cannot race each other: `SyncOrchestrator.sync`
first claims the account with `UPDATE connected_account SET sync_status='SYNCING' WHERE id=? AND sync_status<>'SYNCING'`
(0 rows updated → 409), fetches from the source **outside** any transaction, then persists accepted rows in one short
`@Transactional` batch (not the whole method — a per-row failure marking the transaction rollback-only would make the
final `PARTIAL_FAILURE` log save throw `UnexpectedRollbackException`, so no sync could ever record a partial failure);
the `RUNNING`/final `sync_log` rows are written in their own short transactions; after commit of the batch, publishes
`TransactionsIngestedEvent(userId, accountId, newIds, updatedIds)` from `afterCommit`. SimpleFIN exchange and OFX/CSV
import both upsert `connected_account` on `(household_id, source_kind, external_account_id)` (§3 V16) instead of always
inserting, so a repeat exchange/upload reuses the account rather than duplicating it and every transaction on it.
Test: sync a stub source twice with 3 rows → 3 rows, then 3 rows; exchange SimpleFIN twice / upload the same OFX/CSV
twice → account count unchanged; two same-day same-amount pending transactions → both persist.

**D4 — categorisation write-back.** `TransactionsIngestedEvent` → `CategorisationListener` (`@Transactional(REQUIRES_NEW)`
— the listener itself, not the read-only `CategorisationService.categorise`, owns this transaction, so the write below
is never silently dropped by a read-only session, §2.3) → for each new id `CategorisationService.categorise(userId, tx)`:
transaction-level override → user rule → global EXACT → PREFIX (fixed query) → LLM (only if the transaction owner's own
`ai_settings.enabled`) → `Uncategorised`; the listener sets `transaction.category_id` (this is the column that today
nothing writes) and, on a confident LLM answer, calls `MerchantMappingWriter.persist(userId, …)` (separate bean,
`REQUIRES_NEW`, always scoped to the calling user — §2.3), then publishes `TransactionCategorisedEvent(userId, txId,
oldCategoryId, newCategoryId, month, currency)`. `PATCH /v1/transactions/{id} {categoryId}` sets `transaction.category_id`
**directly** (not only `category_override` — D1's aggregate reads `transaction.category_id`, so a PATCH that touched
only the override table would never move envelope spend, §2.2, major) and upserts `category_override` (marks the
transaction user-pinned so auto-categorisation never overwrites it again on re-sync), then publishes the same event.

**D1 — budgets spent.** `TransactionCategorisedEvent` → `EnvelopeSpendListener` → `EnvelopeSpendService.recalculate(
householdId, categoryId, month)` for old and new category: `spent = SUM(CASE type WHEN 'DEBIT' THEN amount WHEN 'REFUND'
THEN -amount END) FROM transaction WHERE household_id AND category_id AND date IN month AND currency = envelope.currency
AND transfer_group_id IS NULL` (aggregate rewrite, never `+=`, so recategorisation and re-sync cannot double count;
excludes transfer legs per gap P2-14 and nets a REFUND typed CREDIT, since `transaction.type` now spans the full
7-value set, §3 V17). `RolloverService` unchanged: `unspent = allocated + rollover − spent` is now correct. When
`spent/allocated ≥ notification_preference.budget_alert_threshold` the service publishes `BudgetThresholdCrossedEvent`.

**D2 — subscription scan.** `SubscriptionScanScheduler.scanAll()` iterates `UserRepository.findAllActiveIds()` grouped by
household; `SubscriptionDetectionService.detectForHousehold(householdId)` reads `TransactionQueryService.debitsSince(
householdId, now−13 months)` and upserts with `INSERT … ON CONFLICT (household_id, merchant_name) DO UPDATE`. `POST
/v1/subscriptions/scan` calls the same method synchronously for the caller's household. Price change →
`SubscriptionPriceChangedEvent` → alert row + notification.

**D3 — notification delivery (no push, owner decision).** `NotificationService.notify(userId, type, model)` (in-process,
replaces `/internal/notifications/send`): (1) always inserts `in_app_notification`; (2) loads preferences; (3)
`EmailChannel.send` returns `DeliveryResult`; (4) one `notification_log` row per channel (`EMAIL`, `IN_APP` only — no
push channel) with the real status/error. Mobile has no server-pushed channel to poll a device token for; it instead
polls `GET /v1/notifications/unread-count` on foreground/background-fetch and raises a client-local notification from
the result — this is a deliberate v1 limitation (owner decision), not a gap to fill later in this plan. Producers:
`BudgetThresholdCrossedEvent`, `SyncCompletedEvent` (from `SyncOrchestrator`, success or failure), `SubscriptionPriceChangedEvent`,
`ReceiptProcessedEvent`, weekly digest. `WebhookEventListener` subscribes to all five, filters by
`webhook_subscription.event_types`, and calls `WebhookDeliveryService.scheduleDelivery`; the retry scheduler **claims**
due rows in a short transaction (`SELECT … FOR UPDATE SKIP LOCKED`, `status=IN_FLIGHT`), then `deliver()` calls out on
`webhookRestClientBuilder` **outside any transaction** using the pinned-DNS resolver from `OutboundUrlPolicy.check(
targetUrl)` (same resolution the check used, not a second independent lookup — closes the DNS-rebind TOCTOU, §2.8,
major), and persists the outcome in a second short transaction — the source `deliver()` wraps the whole HTTP call in
one `@Transactional`, pinning a DB connection for up to 20 s per delivery and risking the retry job's `lockAtMostFor`
on a batch of slow receivers (minor).

**Net worth.** `AssetRepository.sumValueByHouseholdIdGroupByCurrency` / `LiabilityRepository.sumBalanceByHouseholdIdGroupByCurrency`;
`NetWorthSnapshotService.takeSnapshot(householdId)` writes one snapshot per currency present; `GET /v1/networth/series`
returns `{primaryCurrency, series:[{currency, points:[…]}]}`. Asset/Liability `currency` defaults to the creating user's
preference on create.

**Tax export.** `POST /v1/tax-export/runs {taxYear, format}` → row `PENDING` (scoped to the requesting user, not the
household — a filing is individual) → `TaxExportGenerationService.generateAsync` (`exportExecutor`): loads
`TransactionQueryService.forYear(householdId, taxYear)` filtered to transactions the requesting user is willing to
export (v1: all household transactions, since the ledger is shared) joined to `category` and `tax_line_mapping` for
`users.country` (user overrides win over global rows), builds CSV (`CsvExportBuilder`) or PDF (`PdfExportBuilder`),
writes to `${root}/exports/{userId}/{runId}.{csv|pdf}` via `LocalDiskStorage`, marks `COMPLETE`. Columns carry the
user's own amounts and labels only — no rate, no computed tax, no GST split; receipt ids are listed so the user can
produce evidence. CSV cells are formula-injection-escaped (a leading `= + - @ \t \r` gets a `'` prefix, gap P2-16) since
the same builder is reused by `POST /v1/me/export`'s full data export (§7 step 18).

**CSV/OFX/QFX import.** `POST /v1/imports` (multipart) → `LocalDiskStorage.put(UPLOADS)` → format-sniffed to
`OfxSource.parse(path)` (parses `ACCTID` into `external_account_id`) or `CsvSource.parse(path, mapping)` → creates or
**reuses** a `connected_account` by `(household_id, source_kind, external_account_id)` (OFX) or the caller-selected
target account (CSV) → `import_batch` row records the mapping/counts → returns a preview (`GET /v1/imports/{id}/preview`:
detected columns + suggested mapping — header table from `plugins.md` §8.2, regexes run under a `ReDoSGuard`-style
timeout — plus new/exact-dup/fuzzy-dup counts) → `POST /v1/imports/{id}/commit` runs `SyncOrchestrator.syncFromSource`
tagging every inserted row with `transaction.import_batch_id`; `DELETE /v1/imports/{id}` removes the batch's unedited
rows (undo) and marks it `UNDONE`. `ImportLimits` (§1.6) bound size/rows/time. Currency: row `currency` column if
mapped, else the account's, never a literal.

**SimpleFIN (optional).** Onboarding step is skippable. `ExchangeSetupTokenService.exchange` decodes the token, runs
`OutboundUrlPolicy.check` (https, public IP after resolution, DNS-pinned at connect via the shared `DnsResolver`, no
redirects), POSTs with the customized builder (5 s/30 s), encrypts the access URL with `FieldEncryptionService`,
**upserts** (not always-creates, §3 V16, M0 above) accounts keyed on the bridge's own account id with the bridge's
currency (fallback household member's currency), then syncs each. `SimplefinClient` sends `start-date =
last_synced_at − 7d`.

**Receipts.** `ReceiptUploadService.upload` validates (`MultipartFileValidator`), **re-encodes every raster upload to
JPEG q85 ≤2000px** (strips EXIF/GPS by construction, rejects >40MP before full decode, enforces the caller's
`TROVE_RECEIPT_QUOTA_MB`, gap P2-11), stores original + thumbnail as two `receipt_image` rows, persists `Receipt(UPLOADED)`,
then: if the uploader's own `ai_settings.enabled && vision_model != null` → `processAsync` on `receiptExecutor`
(`ValidationStage → VisionExtractionStage → ParsingStage → MatchingStage → TaxSplitStage(CA only) → ScoringStage`); else
status `COMPLETE`, `reviewRequired=true`, and the user fills fields via `PATCH /v1/receipts/{id}` and links a transaction
(`matchedTransactionId` → also sets `transaction.receipt_id`). `MatchingStage` calls `TransactionQueryService.findCandidates(
householdId, amount, date)` in-process. `ReceiptProcessedEvent` → notification.

**AI settings + coach (per-user endpoint, owner decision).** `AiSettingsService.save` validates the URL with the shared
`OutboundUrlPolicy` — **https required unless the caller has set their own `allow_private_endpoint=true` AND**
`TROVE_EGRESS_ALLOW_PRIVATE` is enabled household-wide (§1.6; the source-inherited "ADMIN or a single global flag" rule
is replaced because the endpoint is per-user, not instance-wide — every member configures and pays for their own AI
endpoint, so the private-LAN exception is each member's own opt-in, gated by the admin only at the "is this allowed on
this install at all" level); encrypts the key, pings `GET /models`, writes an `EGRESS_SETTING_CHANGE` `security_event`.
`AiGateway` (`finance.trove.ai.client`) is the single egress: `complete`, `stream`, `completeWithImage`; every call
resolves through the same pinned `DnsResolver` the validation used (no independent re-resolution at connect time, DNS-
rebind TOCTOU closed, §2.8); refuses when the caller's own AI is disabled (`AiNotConfiguredException` → 409); classifies
the outbound payload with `PrivacyTier` (HIGH payloads refused against `privacy_class=CLOUD`); writes `ai_audit_log`
after every call (this is the per-user egress log the owner decision asks for); records `trove.ai.invocation`.
`ChatController.complete(stream=true)` writes SSE frames matching the pinned wire contract (§4.11: `X-Session-Id`
response header, OpenAI-delta `data:` frames, `data: [DONE]`, in-band error frame) — **not** buffered by `AuditLogFilter`
(§2.1/§5 item 1 fix: the filter no longer wraps the response in `ContentCachingResponseWrapper`, which would otherwise
hold every frame in heap until the emitter completes and the client would see nothing stream at all). `CoachContextBuilder`
gathers dashboard/budget/net-worth/subscriptions via in-process services on `coachExecutor` with a 5 s deadline.
`WeeklyInsightScheduler` iterates all users with their own AI enabled, on `UserClock` boundaries.

**Local accounts + households + onboarding flags.** `POST /v1/auth/register` → `LocalAccountService.register` (argon2id,
enumeration-safe, creates or joins a `households` row via invite token, `users{household_id,currency,country,timezone}`,
`local_credential`, `user_preferences{onboardingCompleted=false}`, `notification_preference`) → `AuthService.issue`.
Web `AuthGuard` reads `onboardingCompleted`; onboarding sets it true at the end of step 1 (currency + country +
timezone), every later step (SimpleFIN / import / first budget) is skippable. `FirstRunBootstrap` seeds the install's
first household and its first ADMIN. Registration is closed by default; an ADMIN issues invite links (72 h, single-use)
that pre-bind the new member to the admin's household (gap P2-7). Password reset without SMTP: an admin-generated
single-use link, `trove admin reset-password <email>` for headless use (gap P2-8). Data export & deletion (gap P2-16):
`POST /v1/me/export` (step-up) builds the same zip described under Tax export above plus every other JSON export;
`DELETE /v1/me` (step-up) cascades via FK, deletes the user's receipt files and sessions, writes an `ACCOUNT_DELETE`
`security_event`, and enters a 7-day soft-delete grace an admin can restore from; the service refuses to delete the
household's last ADMIN.

---

## 7. Execution order

Every step is a compilable commit on `rebuild` (`./mvnw clean verify -pl core` green, app boots against Testcontainers).
LOC: moved / deleted / written (approximate, main + test). Renumbered against §3's corrected V-list; a step that maps
an entity onto a table must not run before the step that creates that table (§9 risk 24 extended — this is what moved
`CategoryOverride` out of step 5 below).

| # | Step | Mode | Depends on | LOC m/d/w |
|---|---|---|---|---|
| 1 | **Skeleton**: `core/pom.xml`, `TroveApplication`, `application.yml` (context-path `/api`, no secret defaults, Flyway policy `baseline-on-migrate=false`/`out-of-order=false` prod / `true` dev-profile-only/`clean-disabled=true`, gap P1-3), minimal `SecurityConfig` (permit `/v1/health`), `HealthController` with `management.endpoint.health.probes.enabled=true` (readiness = db+flyway+`diskSpace(${TROVE_DATA_DIR}/receipts, 1GB)`; liveness = ping; unauthenticated `GET /v1/status`, gap P1-1), `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=20s` (gap P1-2), Flyway V1–V2 (`extensions`, `households`), `BaseIT` + `JwtTestHelper`, `core/Dockerfile` (HEALTHCHECK → `/actuator/health/readiness`, `start_period 240s`), `.env.example`, root `pom.xml` adds `<module>core</module>` | SERIAL | — | 0 / 0 / 750 |
| 2 | **Common foundation**: move `libs/common` dto/exception/domain/observability, `FieldEncryptionService`, `Money`; add `GlobalExceptionHandler` handlers, Jackson config (decimal-string `Money`, camelCase, §5.4), `HttpClientConfig` customizer with a per-call pinned-DNS `DnsResolver`, `OutboundUrlPolicy` (from `SafeWebhookUrlValidator`, extended CIDR blocklist + pin-by-resolution, gap P2-10), `LocalDiskStorage`, `common.events` records, `UserClock` (per-user timezone boundaries, gap P2-15), `UserScopedRepository<T>` base, ArchUnit rules (incl. no `findById`/`findAll` on household-scoped repositories) | SERIAL | 1 | 1,700 / 400 / 900 |
| 3 | **Auth, users, households**: V3–V9 (`users` incl. `household_id`/`currency`/`country`/`timezone`, `local_credential`, `totp_credential`, `oidc_link`, `refresh_token`, `login_attempt`, `security_event`); move gateway auth (`typ`-claim JWT, cookie CSRF path/`SpaCsrfTokenRequestHandler` fix, refresh rotation with reuse-grace-window); `LocalAccountService` (argon2id, enumeration-safe login, gap P2-4), `TotpService` (enrolment/step-up/recovery, gap P2-6), lockout via `login_attempt` (gap P2-5), `AuthController`, `MeController`, `users`/`preferences`, `FirstRunBootstrap` (first household + admin, gap P2-7), closed registration + admin invite links (gap P2-7), password reset without SMTP (gap P2-8), optional OIDC (`email_verified` gate, `CookieAuthorizationRequestRepository` NEW); `AccessControlIT` un-disabled (household isolation) | SERIAL | 2 | 2,400 / 700 / 2,600 |
| 4 | **Security chain, rate limit, audit, scheduling**: final `SecurityConfig` (order: `RequestIdMdcFilter → CookieJwtAuthFilter → RateLimitFilter → CsrfRequiresHeaderFilter → CsrfFilter+CsrfCookieFilter → AuditLogFilter`, §5.1), V10–V13 (`user_preferences`, `audit_log`, `rate_limit_config`, `shedlock`), `RateLimit*` on bucket4j-local (path-matched IP buckets, fail-closed on `/auth/**`, `RemoteIpValve`/trusted-proxies), `AuditLogFilter` REWRITE (no `ContentCachingResponseWrapper`) + `SecurityEvent` writer + `GET /v1/audit` + `GET /v1/me/security-events`, `SchedulingConfig`, `AsyncConfig`, CORS, admin settings/invites endpoints, CSP header (§5.1) | SERIAL | 3 | 1,100 / 400 / 700 |
| 5 | **Categories**: V14–V15 (+seeds with the slug→name mapping), `category`/`merchant_category_map` household/user scoping, `categorisation` package with D4/G3/prefix fixes, `MerchantMappingWriter` (per-user, `REQUIRES_NEW`), rules controller — **not** `CategoryOverride`/`CategoryOverrideRepository` (V19 FKs to `transaction`, which does not exist until step 6; moved there) | PARALLEL-SAFE | 2 | 900 / 300 / 500 |
| 6 | **Accounts, transactions, ingestion core, manual entry, transfers**: V16–V20 (`connected_account` w/ `external_account_id`+`SYNCING`, `transaction` w/ widened `type`+`transfer_group_id`+`import_batch_id`, `sync_log` status fix, `category_override` (moved from step 5), `import_batch`); SPI rename `providerTransactionId`; dedup fuzzy-pass fix; `SyncOrchestrator` transactional redesign + `SYNCING` claim guard; manual transaction CRUD + `source_kind='MANUAL'` account (gap P2-13); transfer pairing job (gap P2-14); controllers on household principal; dashboard per-currency via `UserClock`; `TransactionQueryService`; `q` search on `pg_trgm` (gap P2-15) | SERIAL | 3, 5 | 3,100 / 500 / 1,200 |
| 7 | **Sources + CSV/OFX/QFX import pipeline**: simplefin (M0 upsert-by-`external_account_id`, `OutboundUrlPolicy` pinned resolver, timeouts), ofx (M0 `ACCTID`-reuse, currency, charset), csv (NEW, deterministic `providerTransactionId = sha256(...)`), `ImportController` (preview/commit/undo, `import_batch`, gap P2-12), shared `ImportLimits` | PARALLEL-SAFE | 6 | 2,300 / 300 / 1,000 |
| 8 | **Budgets**: V21–V23, `budgets` package, `EnvelopeSpendService` (household-scoped, widened `type` formula, excludes `transfer_group_id`), rollover lock, `DELETE /v1/budgets/{id}` | PARALLEL-SAFE | 6 | 2,000 / 200 / 400 |
| 9 | **Net worth**: V24–V26, per-currency aggregation, liabilities list, household-scoped | PARALLEL-SAFE | 6 | 1,300 / 100 / 300 |
| 10 | **Subscriptions**: V30–V32, in-process fetch, all-households scan, manual trigger, join table | PARALLEL-SAFE | 6 | 1,700 / 300 / 350 |
| 11 | **Notifications (no push)**: V33–V36, `NotificationService` with `DeliveryResult`, channels `EMAIL`+`IN_APP` only (owner decision — no Expo relay), webhook re-validation on the pinned resolver + split-transaction `deliver()`, digest in-process on `UserClock` | PARALLEL-SAFE | 6 | 2,000 / 900 / 650 |
| 12 | **AI core (per-user endpoint)**: V37, V40, `OpenAiCompatibleClient` (chat/stream/vision/models), `AiGateway`, `AiSettings*` (per-user `endpoint_url`/`api_key`/`allow_private_endpoint`), `OutboundUrlPolicy` shared with SimpleFIN/webhooks (https-required unless private-endpoint exception, gap P2-10), privacy tier, audit write, `EGRESS_SETTING_CHANGE` security event | PARALLEL-SAFE | 3 | 900 / 1,300 / 1,500 |
| 13 | **Coach + insights**: V38–V39, chat/insight packages, `CoachContextBuilder` in-process, SSE controller on the pinned wire contract (§4.11: `?stream=true`, `X-Session-Id`, OpenAI-delta frames, `[DONE]`, in-band error frame) | SERIAL | 4, 8, 9, 10, 12 | 2,400 / 900 / 550 |
| 14 | **Receipts**: V27–V29, receipts package on `LocalDiskStorage`, upload hardening (re-encode/strip-EXIF/dimension-cap/quota, gap P2-11), `VisionExtractionStage` (status enum fix), in-process matching, scoring fix, CA-gated tax split, `PATCH /{id}`, thumbnail with `Content-Disposition`/nosniff | SERIAL | 6, 12 | 4,000 / 1,000 / 1,000 |
| 15 | **Tax export**: V41–V42, `taxexport` package from report-service, CA/US line mapping seeds (`NULLS NOT DISTINCT`), CSV/PDF builders, controller | PARALLEL-SAFE | 5, 6 | 1,400 / 900 / 900 |
| 16 | **Seam wiring**: `CategorisationListener` (owns the `REQUIRES_NEW` write of `transaction.category_id`), `EnvelopeSpendListener`, `WebhookEventListener`, notification producers, `SyncCompletedEvent`, `ReceiptProcessedEvent`; seam ITs (§9) | SERIAL | 5–15 | 0 / 0 / 650 |
| 17 | **AI categorisation fallback**: `AiCategorisationService` via `AiGateway`, gated on the transaction owner's own `ai_settings.enabled` | PARALLEL-SAFE | 5, 12 | 250 / 100 / 100 |
| 18 | **Data export & account deletion** (gap P2-16): `POST /v1/me/export` (step-up, async zip: transactions/accounts/budgets/envelopes/assets/liabilities/categories/rules JSON + receipts manifest; CSV formula-injection escaping with a `=HYPERLINK(...)`-merchant test), `DELETE /v1/me` (step-up, FK-cascades, 7-day soft-delete + admin restore, refuses the last household ADMIN), admin `DELETE /v1/users/{id}`; the tax-time CSV export (§6) becomes a filtered view of the same builder | PARALLEL-SAFE | 6, 8, 15 | 900 / 100 / 700 |
| 19 | **Infra**: `docker-compose.yml` (traefik, socket-proxy, web, core, postgres, ollama/monitoring profiles, `stop_grace_period`), Traefik static+dynamic (ACME, security headers **incl. CSP** on `/` and `/api`, ratelimit, `forwardedHeaders.trustedIPs` unset so it overwrites XFF), `scripts/{bootstrap,backup,restore,update}.sh`, log rotation, `nginx.conf` target (delete the redundant `/api/` proxy location — Traefik owns `/api`), mobile release signing config for **sideload-only distribution** (owner decision — signed APK per release + TestFlight/AltStore, no store submission: no store listing metadata, no Play/App Store CI lanes) | PARALLEL-SAFE | 1 | 500 / 0 / 750 |
| 20 | **Web client**: auth rewrite (register/login/TOTP/logout, CSRF header + cookie-path fix, invite-accept flow), decimal-string `MoneyDisplay` (`Intl.NumberFormat`, §5.4), per-user timezone/locale formatting (no bare `toLocaleString`/`'$'` literal, lint rule), onboarding (currency/country/timezone step, skips), pages Transactions (search/filters)/Accounts (incl. manual)/Budgets/NetWorth/Subscriptions/Receipts/Settings (Data export & delete)/TaxExport/Coach on Generation A, delete fixtures + Generation B + old components + `TestHarness`, 404 route | PARALLEL-SAFE (after contract fixed) | 16 | 0 / 3,000 / 4,900 |
| 21 | **Mobile client**: base URL, auth (single-flight refresh rotation, logout, TOTP, login body carries `user`), `/api/v1` paths, PATCH, Accounts screen (incl. manual), rewritten `types.ts` + Budgets/Subscriptions/Transactions/Receipts screens against the real server DTOs (not the mock flat shapes), `net-worth`→`networth/series`, coach fetch-SSE on the pinned contract (no `[DONE]`-less named-event wait; drop the UUID polyfill, §4.13), receipts single QueryClient, settings selectors + theme + Data export & delete, delete Tax tab/parser/native stubs/push-token registration entirely (owner decision — no push), CAD/USD-only onboarding, icons/PrivacyInfo, `tsconfig types`, sideload build profile (EAS local/`expo-updates` off, no store credentials) | PARALLEL-SAFE | 16 | 0 / 1,100 / 3,300 |
| 22 | **CI**: `ci.yml` (backend clean verify + archunit, web, mobile, secret-scan, e2e Playwright against built images with CSV import + export/delete flow), `release.yml` single core image + signed sideload APK artifact, delete `web-ci`/`mobile-ci` duplication and any store-submission job, `generate-openapi` step | PARALLEL-SAFE | 19, 20, 21 | 350 / 900 / 450 |
| 23 | **Load/security verification**: Gatling retargeted (`register` real), k-user load for `-Xmx`/`mem_limit`, Let's Encrypt staging run, restore drill, CSP/security-header e2e assertion, rate-limit/CSRF/JWT-type-confusion/OIDC-email-verified ITs run against the built image | SERIAL | 19, 22 | 200 / 300 / 300 |
| 24 | **Deletion**: remove old modules, plugins, libs, infra families, docs (§8); reactor = `core` only | SERIAL | 16–23 | 0 / ~48,000 / 0 |
| 25 | **Docs**: README (what exists), CONTRIBUTING (single module), `openapi/trove-core.yaml`, ADR-0020 "monolith collapse" superseding ADR-0001, `.tours` regenerated | SERIAL | 24 | 0 / 0 / 800 |

Parallel groups: {5, 12, 19} after step 2–3; {7, 8, 9, 10, 11, 15, 17} after step 6; {18} after 6, 8, 15; {20, 21} after
16; 22 after 19–21. Migration numbering note (§9 risk 24 extended): V-numbers above follow merge order within each
serial/parallel group as drafted, but a developer's persistent local Postgres that happens to apply a later-numbered
parallel-step migration before an earlier one still in review will fail Flyway's `out-of-order=false` check on next
boot — set `spring.flyway.out-of-order=true` under the `dev` profile only (never prod) and document `docker compose
down -v` as the standing local reset.

---

## 8. Deletion list (executed in step 24; ≈ 48,000 LOC)

| Path | LOC | Why |
|---|---|---|
| `services/api-gateway/` (entire module incl. tests, Dockerfile, `.env.example`, README) | 7,100 | moved to `core` (auth, prefs, ratelimit, audit) or deleted (routing, Authentik) |
| `services/ingestion-service/`, `services/categorisation-service/`, `services/budget-service/`, `services/subscription-detector/`, `services/notification-service/`, `services/receipt-processor/`, `services/report-service/`, `services/model-manager/` | 30,000 | moved to `core` packages per §2 |
| `services/tax-engine/` | 3,900 | cut |
| `libs/common/`, `libs/privacy-engine/`, `libs/ai-gateway-client/`, `libs/model-adapter-api/` | 4,500 | moved/deleted per §2.11 |
| `plugins/bank-sources/{simplefin,ofx}/` | 2,000 | moved to `core` |
| `plugins/bank-sources/{imap,bank-emails}/`, `plugins/models/{ollama-adapter,litellm-adapter,paddleocr-adapter}/`, `plugins/**/README.md` | 4,700 | cut |
| `docker/` (`docker-compose.yml`, `docker-compose.release.yml`, `docker-compose.integration.yml`, `postgres-init.sql`, `litellm_config.yaml`, `prometheus/`, `grafana/` → `infra/monitoring/` after trimming) | 1,600 | replaced by root `docker-compose.yml` |
| `infra/docker-compose.yml`, `infra/docker-compose.infra.yml`, `infra/full-stack/` (compose, traefik mkcert config, postgres-init, `.env.example`), `infra/.env.example` | 1,100 | replaced; socket-proxy block and `dynamic.yml` middleware copied out first |
| `scripts/start-full-stack.sh`, `start-full-stack.ps1`, `_run-full-stack-auto.ps1`, `bootstrap-user.sh/.ps1`, `smoke-test.sh`, `smoke-validate.sh/.ps1`, `test-bootstrap-oauth2.sh`, `_creds_stub/` | 3,000 | Authentik/mkcert/11-DB specific; `upsert_env()` reused in `bootstrap.sh` |
| `tests/integration/` (6 `@Disabled` ITs, GBP fixtures), `tests/performance/` (retargeted copy lives in `core/src/test/gatling/`) | 1,500 | replaced by Playwright e2e + `core` seam ITs |
| `openapi/*.yaml` (10 files) | 5,500 | replaced by `openapi/trove-core.yaml` |
| `registry/models-registry.json`; `registry/merchant-db.json` after its CA rows are folded into V15 | 160 | orphaned data |
| `config/litellm.yaml.template` | 50 | no LiteLLM |
| `.tours/*.tour.json` (6) | 440 | describe 10-service topology |
| `docs/ARCHITECTURE.md`, `docs/services/`, `docs/ONBOARDING.md`, `docs/postman/`, `docs/performance/`, `docs/adr/ADR-0001…` (marked superseded, not deleted), `docs-site/self-hosting/{minio-setup,authentik-setup,kubernetes}.md`, `docs-site/api/*` per-service pages | 14,000 | describe a system that never existed |
| `.github/workflows/{web-ci,mobile-ci,security(owasp-runtime-assertions job)}.yml` pieces, `ci.yml` 10-service matrix | 900 | replaced in step 22 |
| `services/*/.env.example` (10), root `.env.example` (52 vars) | 300 | one schema |
| `apps/web/tests/` (98 orphaned specs), `apps/web/e2e/auth-flow.spec.ts`, `src/hooks/{useBudgets,useTransactions,useSubscriptions,useNetWorth,useModelRouting,useCoachSession,useTaxYear,useAccounts,useReceipts}.ts` + tests, `src/test/handlers/{accounts,budgets,transactions,receipts,subscriptions,networth,tax,modelRouting,coach}.ts`, `src/pages/{TaxCA,TaxUS,TestHarness}/`, `src/components/{QlBar,QlBtn,QlDot,QlEyebrow,QlPageHeader,QlPill,QlRadioCard,QlSidebar,QlSpark,QlToggle}/`, `scripts/generate-api-clients.js` | 3,000 | second API/component generation, fixtures, cut features |
| `apps/mobile/app/(tabs)/tax.tsx` + test, `src/hooks/{useTaxYear,useModelRouting}.ts`, `src/notifications/bankNotificationParser.ts` + test, `android/app/src/main/java/com/trove/*.kt`, `ios/TroveNSE/*.swift`, hand-written `AndroidManifest.xml`, `scripts/generate-api-clients.js`, app-group entitlement + `RECEIVE_BOOT_COMPLETED`/`VIBRATE`, `src/notifications/registerPushToken.ts` + Expo push permission prompt (owner decision — no push relay), `react-native-get-random-values` dependency (§4.13 — session ids are server-generated now), `react-native-event-source` dependency (replaced by the fetch-based SSE reader, §4.11), any Play/App Store submission CI lane or store-listing metadata (owner decision — sideload-only distribution) | 950 | cut features |
| `pom.xml` `<modules>` entries for everything except `core` | — | |

---

## 9. Risks and verification

| # | Risk | Retired by |
|---|---|---|
| 1 | A package silently opts out of the security chain (M1 pattern) | ArchUnit: no class outside `finance.trove.app` annotated `@EnableWebSecurity`/declares `SecurityFilterChain`; `SecurityChainIT` hits every `@RequestMapping` discovered via `RequestMappingHandlerMapping` unauthenticated and asserts 401 unless on the permit list |
| 2 | Client-supplied identity sneaks back in (G1 / body-userId variant); household isolation not mechanised (gap P2-9) | ArchUnit: no `@RequestHeader("X-User-Id")`/`X-Trove-User-Id`; no request DTO field named `userId`; no `findById`/`findAll`/`deleteById` call on a `household_id`-bearing repository (only `UserScopedRepository` finders); `AccessControlIT` — corrected to household scope, §1.5/§2.1 — asserts **household B** cannot read/mutate **household A**'s account, transaction, budget, receipt, subscription (404), plus one parameterised case per by-id route in the generated OpenAPI spec |
| 3 | M0 recurs in a new source (CSV) | `RawTransaction` has no `id` slot; `CsvSource` derives a deterministic `providerTransactionId` so CSV lands on the exact-match path, not the fuzzy one (§6 M0); `DeduplicationServiceIT` runs each source twice on one account and asserts row count is stable, plus a case with two distinct pending same-day/same-amount transactions asserting both persist (the source fuzzy pass would collapse them) |
| 4 | `Envelope.spent` drifts (double count on recategorise/re-sync) | `EnvelopeSpendServiceIT`: ingest, recategorise, re-sync same file → `spent` equals the SQL sum each time; `RolloverServiceIT` asserts carry-forward = allocated − spent |
| 5 | Currency literals return | ArchUnit: string literal `"CAD"`/`"USD"` allowed only in `CurrencyCode` and tests; `MixedCurrencyIT` (user USD with a CAD account) asserts dashboard, net worth, digest, export and subscriptions summary label amounts by row currency; DB has no `DEFAULT 'CAD'` (`SchemaDefaultsIT` queries `information_schema.columns`) |
| 6 | Money rounding across `NUMERIC(19,4)`/`(12,2)` | `SchemaDefaultsIT` asserts every `numeric` money column is `(19,4)`; `Money` scale test |
| 7 | Jackson casing breaks a client | `OpenApiTest` snapshot compared against `apps/web/src/api/types.ts`/`apps/mobile/src/api/types.ts` via a generated-client type-check in CI (`openapi-typescript` → `tsc --noEmit`) — the generator finally has a real input |
| 8 | Double filter registration reappears | ArchUnit: no `OncePerRequestFilter` subclass is `@Component`; `FilterOrderIT` asserts each filter runs once per request (MDC counter) |
| 9 | Outbound HTTP without timeout / to private hosts, incl. DNS-rebind TOCTOU and the per-user AI endpoint (gap P2-10) | ArchUnit: `RestClient.builder()`/`create()` only in `HttpClientConfig`; `OutboundUrlPolicyTest` asserts the **pinned resolver** is used at connect (a rebinding stub resolver answering public-then-private cannot slip through, not just "resolves DNS again"), and the blocklist covers loopback, RFC1918, link-local, `169.254.169.254`, **`fc00::/7`, `100.64.0.0/10`, `0.0.0.0/8`, `::ffff:0:0/96`** for SimpleFIN, webhooks, and every user's AI endpoint |
| 10 | AI bypass (a second client to the model endpoint) | ArchUnit: only `finance.trove.ai.client` may reference `aiRestClientBuilder`/`/chat/completions`; `AiGatewayIT` with WireMock asserts audit row per call, refusal when disabled, HIGH-tier refusal against CLOUD |
| 11 | Streaming coach works only in unit tests, or is silently buffered by `AuditLogFilter` | `ChatStreamIT`: WireMock streams 5 SSE chunks → `SseEmitter` client receives 5 `data:` frames + `[DONE]` **before** the request completes (asserts frames arrive incrementally, not all at once at the end — this is what catches the `ContentCachingResponseWrapper` regression, §2.1/§5 item 1); Playwright coach spec against the built image |
| 12 | Vision OCR is unproven | `ReceiptPipelineIT` with a WireMock vision stub returning the documented JSON; manual acceptance run against Ollama `qwen2.5-vl` recorded in the acceptance notes (private project notes); AI-off path covered by `ReceiptManualFlowIT` |
| 13 | Notification status lies (D3) | `NotificationServiceIT`: SMTP stub down → `notification_log.status='FAILED'` with error; in-app row always present (no push channel to test — owner decision) |
| 14 | Subscription scan never runs for a fresh install | `SubscriptionScanFreshInstallIT`: new user + 3 monthly debits → scheduler produces a row; manual trigger returns it |
| 15 | Tax export leaks a computed number | `CsvExportBuilderTest` asserts header set exactly and no column derived from a rate; `TaxExportIT` downloads CSV and PDF and greps for `gst`/`tax_owing`/`rate` (must be absent) |
| 16 | Secrets default to known values | `JwtPropertiesTest` rejects the literal; compose uses `${VAR:?}` (`compose config` in CI fails without `.env`); `bootstrap.sh` test asserts four generated values differ from placeholders and `POSTGRES_PASSWORD` is not `trove` |
| 17 | ACME/Traefik never exercised | Step 23 runs the compose against a real DNS name with Let's Encrypt staging; `traefik.yml` `providers.docker.network` matches the compose project network (checked by `compose config` assertion) |
| 18 | `-Xmx`/`mem_limit` guessed | Gatling `AllSloSimulation` (retargeted, register real) at 50 users/10 min on a 2 GB container; heap/GC recorded from `/actuator/prometheus`; values in compose only after that run |
| 19 | Backups untested | `restore.sh` drill in CI nightly: backup → restore into scratch Postgres → row counts + Playwright smoke |
| 20 | Rate limiting "added" but ineffective — anonymous-only bucket, filter ordering (gap P2-5) | `RateLimitFilterIT` covers **both** keys: `/v1/auth/login` unauthenticated (11th request in a minute from one IP → 429, seeded 10/20) and an authenticated route keyed by `userId` (proves the filter runs after `CookieJwtAuthFilter`, §5 item 1); Traefik middleware asserted by `curl` loop in the e2e job |
| 21 | Scheduler overlap / wrong timezone; per-user date boundaries wrong (gap P2-15) | `SchedulerLockTest`: every `@Scheduled` method has `@SchedulerLock` and `zone="UTC"` (reflection); `UserClockIT`: a user in `America/Vancouver` and one in `America/Toronto` get different "today" boundaries from the same UTC instant |
| 22 | Web/mobile diverge from the route map | Playwright e2e (`smoke`, rewritten `onboarding-to-today` with CSV import, `transactions`, `budgets`, `receipts-manual`, `tax-export`, `data-export`, `account-deletion`) against built images in CI; a signed sideload build (no store submission, owner decision) smoke-installed on a device/emulator per release |
| 23 | `openapi/trove-core.yaml` drifts | written only by the `generate-openapi` CI step, diff must be empty on PRs |
| 24 | Parallel steps collide on `SecurityConfig`/`application.yml`, or map an entity onto a table a later step creates | steps 5–15 may only add `permitAll`-free controllers and their own `application-<domain>.yml` fragments merged by `spring.config.import`; only steps 3–4 edit `SecurityConfig`; §7's dependency column is the authority on migration/entity ordering (this is what moved `CategoryOverride` out of step 5) |
| 25 | CI unavailable during the rebuild hides all of the above | until restored every gate runs locally via `act` (`.actrc`) and results are pasted into the PR |
| 26 | Credential-stuffing / brute force against the public login (gap P2-5) | `LoginLockoutIT`: 5 wrong passwords on one account → soft lock, correct password still rejected until `locked_until`; 20 failures from one IP across accounts → 429; forged `X-Forwarded-For` from a non-proxy source is keyed on the socket address, not the header |
| 27 | OIDC auto-link as account takeover (gap, §1.5) | `OidcLinkIT`: an ID token with `email_verified=false` matching a local email → no auto-link, "link account" response, not issued cookies |
| 28 | TOTP challenge token accepted as a full access token (§1.5) | `JwtTypeConfusionIT`: a `typ=totp_challenge` token on any route other than `/v1/auth/login/totp` → 401 |
| 29 | Household ledger retrofitted after the fact is expensive (07-READINESS-GAPS.md warning) | schema carries `household_id` from V2 onward, not bolted on later; `AccessControlIT`/§9 risk 2 is the standing regression gate |
| 30 | Public SPA ships with no CSP | e2e asserts `Content-Security-Policy` header present with `script-src 'self'` on both `/` and `/api/v1/health` responses (§5 item 1) |
| 31 | Data export / account deletion missing (gap P2-16; also an app-store precondition if ever listed later despite sideload-only v1) | `DataExportIT`: export zip contains every declared file, a `=HYPERLINK(...)`-named merchant round-trips escaped in the CSV; `AccountDeletionIT`: soft-delete grace, admin restore, last-ADMIN refusal |

### Open questions (defaults chosen so work can start)
1. Receipt GST/PST/HST split (`taxsplit`) — kept, CA-only, receipt-scoped, never exported. Still open; confirm or delete (−600 LOC).
2. ~~`ai_settings` per user vs. instance-wide~~ — **settled by owner decision**: per-user, with the private-range/DNS-pinning rule in §1.6/§6.
3. ~~Household model~~ — **settled by owner decision**: one shared household ledger per install, `household_id` scoping throughout (§1.5/§3); registration closed by default, admin issues invites.
4. ~~Push~~ — **settled by owner decision**: no push in v1 (no Expo relay, no APNs/FCM) — in-app inbox + client-local notifications only (§2.8/§4.9/§6 D3).
5. Transaction split/manual entry: manual entry is in (gap P2-13); split is not. Still open; confirm split stays out of v1.
6. ~~Mobile distribution~~ — **settled by owner decision**: v1.0 is sideload-only (signed APK per release + TestFlight/AltStore), no Play/App Store submission (§7 step 19/21/22).

---

## 10. Review log

Three adversarial reviews (DATABASE, CLIENT CONTRACT, SECURITY AND WIRING) produced 51 findings against the prior draft.
Every finding was checked against the cited source file(s) before being actioned — none were taken on the reviewer's
word alone. All 51 were **CONFIRMED** against source and **ACCEPTED**; none were rejected. Three findings whose fix
would otherwise have patched a feature that the owner's four post-draft decisions remove outright (Expo push, the
household-model open question) are marked **ACCEPTED — superseded**: the underlying defect the reviewer found is real,
but the correction applied here is "the feature is cut" rather than "the feature is fixed," because the owner decision
supersedes the fix the reviewer proposed. Numbering below follows each review's own finding order.

### DATABASE (16 findings, all ACCEPTED)

1. **V12 seed slug mismatch (blocker).** `registry/merchant-db.json`'s `category_id` slugs (`food.coffee`, …) match no
   seeded `category.name`; an unmapped seed halts Flyway on every boot. ACCEPTED — §3 V15 now states the explicit
   slug→name mapping, dedupes repeated aliases, inserts `ON CONFLICT DO NOTHING` against a `NULLS NOT DISTINCT` unique
   index.
2. **No account-reuse key for SimpleFIN/OFX (blocker).** Every exchange/re-upload creates a new `connected_account` and
   duplicates every transaction on it. ACCEPTED — §3 V16 adds `external_account_id` + unique `(household_id,
   source_kind, external_account_id)`; §2.2/§2.12/§6 M0 require upsert-by-key.
3. **Fuzzy dedup collapses distinct transactions and misses CSV re-imports (blocker).** ACCEPTED — §2.2/§6 M0: fuzzy
   pass now requires no batch-internal id collision; CSV gets a deterministic `providerTransactionId` hash and moves
   onto the exact-match path entirely.
4. **`sync_log` status values (`OK`/`ERROR`) don't match the widened CHECK (major).** ACCEPTED — §3 V18 states the
   CHECK **and** the three `setStatus` call-site renames together.
5. **`notification_log.type` CHECK adds a 25-char value into a 20-char column (major).** ACCEPTED — §3 V34 widens to
   `VARCHAR(40)` and states the entity `length` change.
6. **PATCH only wrote `category_override`, never `transaction.category_id`, so budgets never see a recategorisation
   (major).** ACCEPTED — §6 D4 and §2.2's PATCH row now state both writes explicitly.
7. **`category` has no tenancy column and a global-unique name (major).** ACCEPTED — §3 V14 adds `household_id` +
   split unique constraints; §2.3 rewrites `CategoryRepository`/`CategoryController` around it.
8. **LLM merchant write-back has no user scope and no uniqueness (major).** ACCEPTED — §2.3/§3 V15: writer always sets
   the caller's `user_id`; `UNIQUE(merchant, match_type, user_id) NULLS NOT DISTINCT`.
9. **Five reads target `users.currency`/`users.country`, which V2 as drafted never created (minor).** ACCEPTED —
   moved `currency`/`country`/`timezone` onto `users` (§3 V3); every downstream reference left pointing at the right
   table.
10. **"24 user_id columns" undercounts and isn't enumerated (minor).** ACCEPTED — §3 closing paragraph enumerates all
    31 by name and states the `SchemaDefaultsIT` assertion; migration count corrected and stated as authoritative over
    the stale inventory doc.
11. **`receipt_image`/`tax_line_mapping` unique constraints don't catch NULLs as duplicates (minor).** ACCEPTED — §3
    V28 (fixed `page_no` default instead of NULL) and V42 (`NULLS NOT DISTINCT`); same treatment noted for V15.
12. **`ddl-auto: validate` will fail boot on `configEncrypted`/`ReceiptStatus` mismatches the plan never named
    (minor).** ACCEPTED — §2.2 connected_account row and §2.6 receipt row state both changes explicitly.
13. **`transaction.type` CHECK is narrower than the shared enum; D1's sum ignores REFUND/TRANSFER (minor).** ACCEPTED
    — §3 V17 widens the CHECK; §2.5's `EnvelopeSpendService` formula nets REFUND and excludes `transfer_group_id`.
14. **Two overlapping syncs race the unique index; a single top-level `@Transactional` can't record PARTIAL_FAILURE
    (minor).** ACCEPTED — §2.2/§6 M0: `SYNCING` claim guard + non-monolithic transaction shape.
15. **A hard FK on `audit_log.user_id` would drop audit rows on an async thread whenever `sub` doesn't resolve
    (minor).** ACCEPTED — §3 V11 keeps the column unconstrained and indexed instead.
16. **`docs/recovery/inventory/database.md` undercounts migrations (34 vs 42) and surviving tables (21 vs its own 23)
    (minor).** ACCEPTED — §3's intro states the corrected counts and defers to this plan's own table as authoritative.

### CLIENT CONTRACT (13 findings, all ACCEPTED)

1. **Cookie-mode CSRF as originally specified 403s on login itself (blocker).** ACCEPTED — §1.5/§5 item 1/10:
   `setCookiePath("/")`, `SpaCsrfTokenRequestHandler`, a `CsrfCookieFilter` that materialises the token for anonymous
   callers.
2. **Only the `Money` wrapper gets a string serializer; every raw-`BigDecimal` DTO field is untouched, and both
   clients use incompatible cents/decimal representations (major).** ACCEPTED — §5 item 4: one global `BigDecimal`
   `ToStringSerializer`; §4.13 deletes every client `*Cents` type.
3. **`accounts.ts`/`exchange-setup-token` envelope status contradicts itself between §4.3 and §4.13 (major).**
   ACCEPTED — §4.3/§4.13 now state both endpoints change together.
4. **`/v1/auth/*` envelope/field set left unstated; mobile session needs a `user` object the login body never
   carried (major).** ACCEPTED — §4.1 states the envelope rule and the `user` object on login; `displayName` added to
   `/v1/auth/me`.
5. **Coach SSE wire contract unpinned; neither client's SSE reader matches any plausible server shape (major).**
   ACCEPTED — §4.11/§6 states the exact frame contract; §4.13 drops the now-unneeded UUID polyfill.
6. **Mobile's flat mock DTOs don't match the KEPT server DTOs for budgets/subscriptions/transactions/receipts
   (major).** ACCEPTED — §4.13/§7 step 21: `types.ts` rewritten wholesale, screens rewritten with it.
7. **`GET /v1/transactions` had no stated default/max page size or page-index base (major).** ACCEPTED — §4.4/§2.2:
   0-based page, default 50, max 500, stated allowlist.
8. **Mobile onboarding never sent currency/country to the server (major).** ACCEPTED — §4.13/§7 step 21/§6: `setup.tsx`
   now `POST`s to `/v1/auth/register`; CAD/USD-only, other channel options removed.
9. **Server sends `field_errors`, both clients type `error` as a bare string (minor).** ACCEPTED — §5 item 4/§4.13:
   camelCase `fieldErrors`, typed `ErrorDetail` on both clients.
10. **Category GET still serialises the raw entity (`parent`, `system`), matching neither client (minor).** ACCEPTED
    — §4.5/§2.3: one `CategoryDto` for GET/POST/PUT.
11. **Receipt upload response shape unspecified; matches neither client's expectation (minor).** ACCEPTED — §4.10:
    `202 {id,status,createdAt}` stated explicitly.
12. **Expo push device-registration body/path-encoding unspecified (minor).** ACCEPTED — **superseded**: the route is
    removed entirely (§4.9/§2.8) per owner decision (c), so the encoding question no longer arises.
13. **Refresh rotation + family-revoke will spuriously log out concurrent tabs/requests; mobile had no refresh
    concurrency handling to begin with (minor).** ACCEPTED — §1.5/§4.13: 30-s reuse-grace window server-side,
    cross-tab lock on web, single-flight on mobile.

### SECURITY AND WIRING (22 findings, all ACCEPTED)

1. **Any HS256 token with a `sub` — including a 60-s TOTP challenge — is accepted as a full access token; TOTP is
   bypassable by construction (blocker).** ACCEPTED — §1.5/§2.1: `typ` claim required, `JwtTypeConfusionIT` (§9
   risk 28).
2. **Rate limiter runs before the JWT filter, so authenticated traffic falls into a flat 20/20 anonymous IP bucket
   and per-route seeds are unreachable (blocker).** ACCEPTED — §5 item 1/8/§2.1: filter reordered, IP buckets
   path-matched (§9 risk 20).
3. **Cookie CSRF as drafted has no path/handler/materialisation story and 403s the first login (major).** ACCEPTED —
   same fix as Client-Contract finding 1 (§1.5/§5 item 1/10) — one root cause, reported by two reviewers.
4. **`CsrfRequiresHeaderFilter` "KEEP" would 403 every bearer-mode mobile mutation unconditionally (major).**
   ACCEPTED — §2.1: REWRITE, enforced only in cookie mode via `trove.authSource`.
5. **`AuditLogFilter`'s `ContentCachingResponseWrapper` silently buffers every SSE frame and fully buffers every
   download in heap (major).** ACCEPTED — §2.1/§5 item 1/§6/§9 risk 11: wrapper dropped, `response.getStatus()` read
   directly.
6. **`OutboundUrlPolicy` re-resolves independently at connect time (DNS-rebind TOCTOU) and misses several private
   ranges (major).** ACCEPTED — §2.8/§5 item 5/§6/§9 risk 9: pin-by-resolution `DnsResolver`, extended CIDR blocklist.
7. **Strict rotate-and-revoke-on-reuse will log out concurrent tabs; mobile has no refresh concurrency handling
   (major).** ACCEPTED — same fix as Client-Contract finding 13 (§1.5).
8. **A single top-level `@Transactional` sync can never record PARTIAL_FAILURE and holds a DB connection across the
   bank HTTP call (major).** ACCEPTED — same fix as Database finding 14 (§2.2/§6 M0).
9. **OIDC auto-links to a local account by email with no `email_verified` gate — account takeover (major).**
   ACCEPTED — §1.5/§2.1/§9 risk 27: `email_verified=true` required to auto-link.
10. **§2.2 has step 6 (transactions) call `EnvelopeSpendService`, which is only created in step 8 — cannot compile
    (major).** ACCEPTED — §6 D4/§2.2: PATCH now writes the column directly and publishes an event; the budgets
    listener consumes it in step 16, removing the forward dependency.
11. **`CategoryOverride` FKs to `transaction`, which doesn't exist until step 6, but was scheduled in the earlier
    parallel step 5 (major).** ACCEPTED — §7: `CategoryOverride`/`CategoryOverrideRepository` moved into step 6; §3
    V19 states the ordering constraint.
12. **`ExpoPushChannel` contradicts the owner's own recorded decision against a push relay (major).** ACCEPTED —
    **superseded**: owner decision (c) removes push entirely; §2.8/§4.9/§6 D3 throughout.
13. **Strict per-user isolation throughout §1–§9 contradicts the owner's shared-household-ledger decision (major).**
    ACCEPTED — **superseded/actioned**: owner decision (a) is now the tenancy model; `household_id` scoping added
    throughout §1.5/§3/§6/§9.
14. **The public SPA ships with no CSP at all — neither the app-level header nor Traefik/nginx emit one for `/`
    (major).** ACCEPTED — §5 item 1/§7 step 19: CSP added to both the app and the Traefik/nginx layer.
15. **`X-Forwarded-For` parsed by hand and unconditionally trusted; a redundant nginx `/api/` proxy duplicates
    Traefik's routing (minor).** ACCEPTED — §5 item 1 (`RemoteIpValve`/trusted-proxies), §7 step 19 (nginx `/api/`
    location deleted).
16. **`HttpCookieOAuth2AuthorizationRequestRepository` is not a real Spring Security class; `AuthCookies`'
    `SameSite` change is a behaviour change, not an addition (minor).** ACCEPTED — §2.1/§1.5: marked NEW and REWRITE
    respectively.
17. **No multipart size config anywhere (413 on every upload); no `Content-Disposition`/nosniff on
    downloads/thumbnails (minor).** ACCEPTED — §1.6 (multipart limits stated), §4.10/§2.6 (headers stated).
18. **No scheme rule for the per-user AI endpoint lets a non-admin send a key/context in cleartext (minor).**
    ACCEPTED — §6 AI settings paragraph: https required unless the caller's own private-endpoint exception applies.
19. **Ambiguous which bean owns the transaction that writes `transaction.category_id` (minor).** ACCEPTED — §2.3/§6
    D4: the `REQUIRES_NEW` listener owns it, not the read-only `categorise` method.
20. **Migration numbers assigned by domain, not merge order, breaks a persistent local Postgres under
    `out-of-order=false` (minor).** ACCEPTED — §7 closing note: `out-of-order=true` under the `dev` profile only.
21. **`receiptExecutor`'s `CallerRunsPolicy` runs the whole pipeline (incl. a 120-s vision call) on the request
    thread once the queue fills (minor).** ACCEPTED — §5 item 7: `AbortPolicy` + 503/Retry-After + orphan-sweep
    re-enqueue.
22. **`WebhookDeliveryService.deliver()` wraps a 20-s outbound call in one transaction, risking the retry job's lock
    window (minor).** ACCEPTED — §2.8/§6 D3: claim/call/persist split into three phases, only two of them
    transactional.
