# Trove — Production-Readiness Gaps

> **Historical record, written 2026-09-03 (verified at `9ad1b5f` plus P0), with dated notes to 2026-09-27.** Many gaps were closed by later rebuild steps and the list was not rolled forward; only some entries carry a Superseded note. Current state: `docs-site/guide/architecture.md`.

What a genuinely production-ready self-hosted finance product needs that `01-FINDINGS.md`
through `06-PROGRESS.md` do **not** already cover. Merged from four reviewer lenses
(self-hoster journey, public-internet security, day-2 operations, Expo/store shipping), then
every gap was re-verified against the repo at `9ad1b5f` + P0 edits. Where a reviewer's evidence
did not hold up it is corrected inline. Items the plan already covers are omitted unless the
reviewer added something concrete, in which case the entry says *extends audit item X*.

**Summary.** 63 gaps: **34 must**, **24 should**, **5 nice**. The pattern is consistent with the
audit's diagnosis: the decisions in `06-PROGRESS.md` (built-in accounts, public exposure, local
disk, both clients) each imply a set of features that the plan names in a phrase but that have
**no design and no code** — there is no `users` table, no password hashing, no TOTP, no
lockout, no ACME, no CSP, no backup, no migrate-before-serve, no CSV importer, no transfer or
split model, no way to point the mobile app at a user's own server, and no way to delete an
account (which alone blocks both app stores). Most gaps are S/M effort; the risk is that they are
each "obvious" and therefore unscheduled. Eight items genuinely need the owner and are listed at
the end with a recommended default each.

Severity: **must** = a self-hoster on the public internet cannot safely or successfully run the
product without it; **should** = a real user hits it within the first month; **nice** = polish.
Phases follow `06-PROGRESS.md`. Effort S ≤ 2 days, M ≤ 1 week, L > 1 week.

---

## MUST

### P1 — Monolith collapse

**1. Readiness/liveness probes that reflect DB, Flyway, and the receipts disk** *(extends D7)*
- Why: `--wait`, `trove status`, the maintenance page and any uptime check all key off one
  endpoint; today it cannot distinguish "still migrating" from "DB down", and its disk check
  watches the JVM cwd at a 10 MB threshold, not the receipts volume.
- Now: no `probes`, `diskspace`, `readiness`, `liveness` keys in any
  `services/*/src/main/resources/application*.yml` (grep → 0); no `HealthIndicator` in
  `services/` or `libs/` (grep → 0); compose healthchecks hit `/actuator/health` with
  `start_period: 60s, retries: 5` (`infra/full-stack/docker-compose.yml:135,172,199,255`);
  `services/api-gateway/Dockerfile:19` HEALTHCHECK has no start period; `/api/v1/health` is in
  OpenAPI with no controller (D7).
- Proposal: `management.endpoint.health.probes.enabled=true`; readiness = db + flyway +
  diskSpace(path=`${TROVE_DATA_DIR}/receipts`, threshold 1 GB) + a `BackupFreshness` indicator
  (WARN, not DOWN); liveness = ping. Compose/Dockerfile healthcheck →
  `/actuator/health/readiness` with `start_period: 240s, interval: 15s, start_interval: 5s` so a
  slow ARM box is not restart-looped and a fast box is not penalised. Unauthenticated,
  detail-free `GET /api/v1/status` → `{status: UP|DEGRADED, version}` for the `/welcome`
  degraded state the design review asked for. Web depends on core `service_healthy`.
- Phase P1 · Effort S · **must**

**2. Graceful shutdown and `stop_grace_period`**
- Why: `trove update`, `down`, and host reboots SIGTERM the containers; an in-flight sync or
  receipt write is cut mid-transaction and Postgres is SIGKILLed after Compose's 10 s default,
  then replays WAL on an RPi for minutes ("Trove won't start").
- Now: absent — no `server.shutdown`/`graceful`/`timeout-per-shutdown-phase` in any
  `application*.yml` (grep → 0); no `stop_grace_period` in `docker/` or `infra/` (grep → 0).
  Positive: the compose entrypoint does `exec java …` so PID 1 receives SIGTERM.
- Proposal: `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=20s`,
  scheduled jobs check interruption between batches; compose `stop_grace_period: 30s` on
  trove-core and `60s` on postgres; `depends_on` so the app stops before the DB.
- Phase P1 · Effort S · **must**

**3. Flyway operating policy: forward-only, no baseline-on-migrate, clean disabled**
- Why: Flyway Community has no undo; a half-failed migration leaves a crash-looping container.
  P1's "single Flyway history" has no stated policy, and the ten current histories disagree.
- Now: `baseline-on-migrate: true` in tax-engine, subscription-detector, report-service
  (`application.yml:16,16,18`) — silently skips V1 on a non-empty DB; `false` in budget and
  receipt; unset elsewhere; only api-gateway sets `validate-on-migrate`/`out-of-order`
  (`application.yml:23-24`); `clean-disabled` nowhere. All 10 use `ddl-auto: validate`.
- Proposal: in trove-core `baseline-on-migrate=false`, `out-of-order=false`,
  `validate-on-migrate=true`, `clean-disabled=true`; CONTRIBUTING rule: migrations are
  append-only after a tag, destructive drops happen one release after the code stops reading
  the column (expand/contract). Pair with gap 27 (migrate-before-serve).
- Phase P1 · Effort S · **must**

### P2 — Wire the features

**4. Local-account credential design: argon2id, password policy, enumeration-safe login**
- Why: the decision drops Authentik for built-in accounts on the public internet; the login form
  becomes the primary attack surface and nothing in the repo says how credentials are stored or
  verified.
- Now: absent — `grep -rIl 'argon2|bcrypt|PasswordEncoder|scrypt|pbkdf2'` outside `docs/` → 0;
  no `users`/`credentials` table in any `services/*/src/main/resources/db/migration`
  (api-gateway has only audit_log, rate_limit_config, user_preferences); no `/register` or
  `/login` password route in `services/*/src/main` or `openapi/`. `database.md §9` sketches
  `users`/`credentials` columns only.
- Proposal: Spring Security `Argon2PasswordEncoder` (m=64 MiB, t=3, p=1, 16-byte salt, 32-byte
  hash; params stored in the PHC string, re-hash on login when they change). Min 12 / max 128
  chars, no composition rules, offline top-10k list (no HIBP call by default). Verify against a
  dummy hash when the user does not exist; identical 401 body and timing.
- Phase P2 · Effort M · **must**

**5. Login lockout, per-(IP,account) throttle, fail-closed auth limiter, trusted-proxy IP**
*(extends G7, G8)*
- Why: credential stuffing is the most likely real attack on a public login page. The existing
  limiter is per-IP for anonymous only, fails open, and trusts the first `X-Forwarded-For`
  value — so every per-IP control (limit, lockout, audit IP) is spoofable.
- Now: `services/api-gateway/.../ratelimit/RateLimitFilter.java:80-81` takes `xff.split(",")[0]`
  unconditionally; `:86` and `:103` log "failing open" on ValKey errors; no
  `lockout|locked_until|failed_attempts` in `services/` or `libs/` (grep → 0);
  `application.yml:3 forward-headers-strategy: framework` with no `remoteip`/`trusted-proxies`
  (grep → 0); Traefik has no `forwardedHeaders.trustedIPs`; `tests/security/a07-brute-force.sh`
  is only `bash -n`-checked in CI (`security.yml:177-183`).
- Proposal: `login_attempt` table (email_hash, ip, success, at). 5 failures/account/15 min → soft
  lock with exponential growth; 20/IP/10 min → 429; enforce in the service layer, in-memory
  Bucket4j in the single JVM, **fail closed on `/auth/*`**. Response never reveals lock vs. bad
  password. Tomcat `RemoteIpValve` with `internal-proxies` = compose CIDR and `trusted-proxies` =
  Traefik only; read `getRemoteAddr()` after the valve, never parse XFF by hand; test that a
  forged XFF from a non-proxy source is keyed on the socket address.
- Phase P2 · Effort M · **must**

**6. TOTP enrolment, verify-before-enable, recovery codes, step-up, admin unlock**
- Why: "optional TOTP" is decided; the planned table stores hashed recovery codes but no
  enrolment UX, step-up rules, or lockout recovery exists on either client. A lost phone locks
  the user out permanently.
- Now: absent — `grep -rIl 'totp|two.?factor|recovery.?code'` over `services/`, `libs/`,
  `apps/web/src`, `apps/mobile/{src,app}`, `openapi/` → 0; `apps/web/src/pages/Settings/index.tsx`
  is a theme toggle only; `mobile.md:172` confirms no TOTP UI.
- Proposal: RFC 6238 (SHA-1, 6 digits, 30 s, ±1 step, last-used-counter replay guard); secret
  encrypted with the field-encryption service; 10 single-use SHA-256-hashed recovery codes shown
  once. Enrol requires a fresh password and one valid code before `enabled=true`. Step-up
  (password + TOTP) for: disabling TOTP, password change, adding/viewing a bank connection,
  export, account deletion. Login: password → 5-min pending-2FA token → TOTP or recovery code,
  on web and mobile. Admin can clear a member's TOTP (logged); `trove user disable-totp` for the
  last admin.
- Phase P2 · Effort M · **must**

**7. First-admin bootstrap, roles, closed registration, invite links** *(extends 04 scope #1)*
- Why: with Authentik gone there is no admin source and no way to create the first login; an
  open `/register` on the internet lets strangers create accounts on a household's finance
  server. Every install dead-ends before minute five.
- Now: `services/api-gateway/.../auth/TroveRoles.java:16-27` maps Authentik group
  `trove-admins` → `ROLE_ADMIN`; only one admin route (`GatewaySecurityConfig.java:165`
  `/api/v1/audit`); web login is a single link to `/api/v1/auth/start`
  (`apps/web/src/pages/Login/index.tsx:29`); `scripts/bootstrap-user.sh:18-19` creates an
  Authentik user with defaults `test@yopmail.com`/`admin`; no `invit|household` hits outside
  tax-engine filing-status code; no registration setting anywhere.
- Proposal: `users.role ENUM('USER','ADMIN')`. On boot with zero users trove-core prints a
  one-time setup URL (32-byte token) and web serves `/setup` (email, password, CAD/USD,
  timezone); `trove admin create --email` for headless boxes; `/setup` → 404 once a user exists.
  Registration closed by default; admins issue single-use invite links (72 h, copy-paste, no SMTP
  needed). JSON `accessDeniedHandler` (today 403 → 500). Admin ≠ tenant bypass: admins cannot
  read other users' financial data.
- Phase P2 · Effort M · **must**

**8. Password reset without a mail server**
- Why: most self-hosters have no SMTP; a forgotten password with no reset path means a database
  edit or a reinstall.
- Now: absent — `grep -rIli 'password.?reset|reset-password|forgot'` over `services/`, `apps/`
  (excluding node_modules/dist) → 0; `infra/full-stack/.env.example:58 SMTP_HOST=` blank.
- Proposal: admin-generated single-use reset link (Settings → Users → Reset, 1 h TTL) plus
  `trove user reset-password <email>` printing the link; self-service email reset shown only
  when SMTP is configured. Reset revokes all sessions for that user.
- Phase P2 · Effort S · **must**

**9. Mechanised tenancy: user-scoped repositories, RLS, parameterised cross-tenant test**
*(extends G1)*
- Why: G1 documents one IDOR; the structural cause — any repository call can omit `user_id` —
  survives the collapse into trove-core unless the rule is enforced by tooling. The only IDOR
  test is `@Disabled`.
- Now: `findById(` in `services/*/src/main/**/*Service.java` → 16 hits vs 5 `…AndUserId(`
  hits; no `ROW LEVEL SECURITY|@Filter|@TenantId|CREATE POLICY` anywhere (grep → 0);
  `services/api-gateway/src/test/.../security/AccessControlIT.java:37` `@Disabled` pending a
  register endpoint.
- Proposal: every user-owned entity has `user_id NOT NULL REFERENCES users(id)`; a
  `UserScopedRepository<T>` base exposing only `*AndUserId` methods; ArchUnit test forbidding
  `findById/deleteById/findAll` on user-owned aggregates; Postgres RLS with `SET LOCAL
  app.current_user` from a transaction interceptor as defence in depth; one parameterised IT
  that, for every by-id route in the OpenAPI spec, creates two users and asserts the cross-tenant
  call returns 404.
- Phase P2 · Effort M · **must**

**10. One outbound-URL policy for the AI endpoint, SimpleFIN, and webhooks** *(extends G2)*
- Why: the decision makes the AI base URL user-supplied, which turns a config knob into a
  persistent SSRF primitive unless one validator with DNS pinning governs all outbound HTTP; a
  blanket private-range block is wrong because LAN Ollama is the primary AI story.
- Now: `SafeWebhookUrlValidator` exists only in
  `services/notification-service/.../webhook/`; SimpleFIN check is
  `decodedUrl.startsWith("http")` (`ExchangeSetupTokenService.java:84`); AI endpoint is env-only
  `OLLAMA_URL` (`services/model-manager/src/main/resources/application.yml:25`) with no
  validation; no `allowlist|egress` setting anywhere. (03 §3 already plans promoting the
  validator and adding ULA/CGNAT; the AI-endpoint scope and DNS pinning are new.)
- Proposal: one `OutboundUrlPolicy` bean on every HTTP client: https required; http only for
  hosts/CIDRs in admin setting `TROVE_EGRESS_ALLOW_PRIVATE`; loopback, link-local,
  169.254.169.254, fc00::/7, 100.64/10 denied unless listed; resolve once and pin the IP
  (custom DnsResolver); no auto-redirects; 10 s connect / 30 s read; 5 MB response cap (SimpleFIN
  JSON too). Settings › AI shows the configured host and a plain statement of what is sent
  there; AI features off until configured.
- Phase P2 · Effort M · **must**

**11. Receipt upload hardening: strip EXIF/GPS, cap dimensions, per-user quota, client-side
resize**
- Why: receipt photos carry GPS and device metadata; a crafted PNG with huge dimensions can OOM
  the single JVM; unlimited uploads fill the local-disk volume that replaces MinIO.
- Now: `services/receipt-processor/.../upload/ReceiptUploadService.java:59` stores
  `file.getBytes()` verbatim, `:104` `ImageIO.read` on the full array with no header pre-check;
  `MultipartFileValidator.java:30,36` = 20 MB cap + JPEG/PNG/HEIC/PDF magic bytes (good); no
  `quota`, no `exif|metadata-extractor` anywhere in `services/` (grep → 0); mobile
  `src/hooks/useReceiptCamera.ts:12-23` posts the raw URI at quality 0.8 with no `exif:false`
  and `expo-image-manipulator` is not in `apps/mobile/package.json`.
- Proposal: re-encode every raster upload to JPEG q85 ≤ 2000 px long edge and store only that
  (strips EXIF by construction); read dimensions with `ImageReader` first, reject > 40 MP; drop
  HEIC server-side (convert on device); PDF ≤ 10 MB/≤ 5 pages rendered to JPEG; per-user quota
  (`TROVE_RECEIPT_QUOTA_MB`, default 2048) shown in Settings; serve with
  `Content-Disposition: attachment`, nosniff; files 0600. Mobile: `exif:false`,
  `expo-image-manipulator` resize, client size cap, `onUploadProgress`.
- Phase P2 · Effort M · **must**

**12. CSV/OFX import pipeline: column mapping, preview, batches, undo, parser limits**
*(extends 04 scope #3)*
- Why: CSV is the only bank path that works for every CA/US bank with no third-party account;
  the plan lists "CSV/OFX" but no CSV code exists, every bank's CSV differs, and without a batch
  id an overlapping re-import cannot be undone or deduplicated.
- Now: `grep -rIli csv services/ingestion-service plugins/bank-sources` → 0 (the only CSV code
  is report output); `plugins/bank-sources/ofx/.../OfxPlugin.java:41` reads from a sandbox
  directory `/var/trove/uploads`, no upload endpoint; `OfxSgmlParser.java:31` caps at 10 000
  txns but the plugin has no byte cap; `V2__transaction.sql` has no batch/import column and
  dedups on `(account_id, source_id)` (`:23`); `sync_log` stores only counts; web has no file
  input outside Receipts.
- Proposal: `POST /v1/imports` (multipart csv/ofx/qfx) → preview: detected header, per-column
  guesses (date, description, amount or debit/credit pair, balance), date-format candidates with
  sample rows, sign convention, target account, counts of new/exact-dup/fuzzy-dup; user adjusts,
  `POST /v1/imports/{id}/commit`; mapping persisted per account. `import_batch` table and
  `transaction.import_batch_id`; CSV `source_id = sha256(account|date|amount|normalised
  description|ordinal)`; `DELETE /v1/imports/{id}` removes unedited rows and records the undo.
  Shared `ImportLimits`: 10 MB, 50 000 rows, 1 000 chars/field, 60 s budget, streaming parse,
  no zip wrappers.
- Phase P2 · Effort M · **must**

**13. Manual transactions (create/edit/delete) and a credential-less manual account**
*(extends 04 scope #5)*
- Why: mobile onboarding offers "Manual entry" but the backend only patches tags/notes; cash and
  cheques cannot be recorded at all.
- Now: `services/ingestion-service/.../transaction/TransactionController.java` exposes only
  `@GetMapping` (`:70`) and `@PatchMapping("/{id}")` (`:115`, tagsJson/notes only per
  `ingestion-service.md:76`); `apps/web/src/api/transactions.ts` exports only
  `listTransactions`; `V1__connected_account.sql:4,9` require `source_plugin` and
  `config_encrypted NOT NULL`, so a manual account cannot be inserted.
- Proposal: `POST/PUT/DELETE /v1/transactions` (amount, date, merchant, category, account,
  notes); `config_encrypted` nullable, `source_plugin='manual'`; quick-add sheet on mobile,
  modal on web; `user_edited=true` so sync and import-undo never overwrite.
- Phase P2 · Effort S · **must**

**14. Transfers between own accounts**
- Why: paying a credit card from chequing produces a DEBIT and a CREDIT; today both count as
  spending and income, inflating every budget, dashboard and net-worth-change figure.
- Now: absent — no transfer linkage column in `V2__transaction.sql`; no
  `transfer_group|isTransfer|TRANSFER` in ingestion or budget main code (grep → 0); the category
  seed has Transfers/E-Transfer rows (`categorisation V1__category.sql`) but nothing excludes
  them from aggregates.
- Proposal: `transaction.transfer_group_id`; pairing job after each sync/import matching opposite
  amounts across the user's accounts within ±3 days, same currency; "Mark as transfer / Unlink";
  every spend/income aggregate (dashboard, envelopes, reports, tax export) excludes transfer
  legs; one row in the list.
- Phase P2 · Effort M · **must**

**15. Per-user timezone and locale; all date logic per user; clock-skew tolerance; server-side
search** *(extends 03 "de-hardcode currency" and DoD `MoneyDisplay` rule)*
- Why: every "today/this month" boundary is computed in the container's UTC clock, so a
  Vancouver user's evening purchases land on tomorrow and the month rolls over at 4 pm on the
  31st. Canadians write 2026-09-03; Americans write 9/3/2026. Search is client-side over mock
  data. An RPi with no RTC boots in 1970 until NTP catches up and JWT/TOTP fail with "session
  expired".
- Now: zone-less `LocalDate.now()` in `DashboardController.java:95,100`,
  `BudgetService.java:141`, `NetWorthService.java:51-53`, `NetWorthSnapshotService.java:33`,
  `RolloverScheduler.java:36-37`, `SyncOrchestrator.java:114,118`, `CoachContextBuilder.java:96`;
  `V5__user_preferences.sql` has no timezone/locale; `database.md §9` adds only currency; no
  `TZ` in any compose/.env; no `clockSkew|allowedClockSkew` in `libs/common` or api-gateway
  (grep → 0). Web `MoneyDisplay.tsx:12` hardcodes `$` and ignores `currency`; `en-CA` literal in
  `NetWorth/index.tsx:25`, `TaxCA/index.tsx:64`; `Transactions/index.tsx:18,113` filters a
  `MOCK` array. Mobile `MoneyDisplay.tsx:29` `Intl.NumberFormat('en-CA')`;
  `net-worth.tsx:35,132,153` and `tax.tsx:139,184` use `$${x.toLocaleString()}`;
  `setup.tsx:22,38-41` still offers GBP/EUR; no `expo-localization`.
- Proposal: `users.timezone` (IANA, defaulted from the browser at setup) and `users.locale`
  (en-CA/en-US from jurisdiction, overridable); a `UserClock` bean for today()/monthStart();
  schedulers run hourly and process users whose local boundary just passed; containers stay
  UTC; JWT parser `clockSkewSeconds=60`; TOTP ±1 window; `trove doctor` warns on > 60 s drift.
  One shared `formatMoney(minor, currency, locale)` / `formatDate` on Intl used by web and
  mobile, lint rule banning `'$'` literals and bare `toLocaleString(`; remove GBP/EUR from mobile
  setup. `GET /v1/transactions` gains `q` (pg_trgm on merchant/description/notes), date presets,
  amount range, category/account filters, paginated.
- Phase P2 · Effort M · **must**

**16. Full data export and in-app account deletion**
- Why: self-hosters choose this product to own their data and there is no way to get all of it
  out; a household member has no way to leave; **Apple 5.1.1(v) and Play's account-deletion
  policy reject any app with sign-up but no in-app deletion**. The tax-time export covers only
  categorised transactions.
- Now: absent — only bank-connection deletion exists
  (`services/ingestion-service/.../AccountController.java:119`); no `/export` beyond report/tax
  outputs, no `DELETE /users|/me`, no `gdpr|erase` in `services/` or `openapi/`; mobile
  `app/(tabs)/settings.tsx:35` offers only Sign Out; report CSV uses commons-csv
  (`report-service/pom.xml:208`) with no formula escaping (`grep formula|HYPERLINK` → 0).
- Proposal: `POST /v1/me/export` (step-up auth) builds a zip asynchronously — transactions.csv
  (+splits, transfers), accounts/budgets/envelopes/assets/liabilities/categories/rules as JSON,
  receipts with a manifest and schema version — single 24 h download link, 1/day; CSV cells
  starting with `= + - @ \t \r` prefixed with `'` and a unit test with a merchant named
  `=HYPERLINK(...)`; the tax-time CSV becomes a filtered view of the same pipeline.
  `DELETE /v1/me` (step-up) cascades via FKs from the new `users` table, deletes receipt files
  and sessions, writes an `ACCOUNT_DELETE` event, 7-day soft-delete grace with admin restore;
  refuse deleting the last ADMIN; admin `DELETE /v1/users/{id}`. Settings › Data on web and
  mobile ("Export everything", "Delete account", "Sign out everywhere"); `trove export --user`.
- Phase P2 · Effort M · **must**

**17. Security-event audit trail; audit stores route templates, not raw URIs**
*(extends M11 log redaction, 03 §1 "production log redaction")*
- Why: after a breach or a household dispute the owner must answer "who logged in, from where,
  who added this bank connection, who exported"; method/path/status rows cannot. The access
  audit also persists raw request URIs (OIDC codes, query strings) and production logs are
  unredacted.
- Now: `V1__create_audit_log.sql` columns are method, path, status, duration_ms, ip_address,
  user_agent, request_id; `AuditLogFilter.java:76` records `getRequestURI()` and `:58` mints its
  own `UUID.randomUUID()` (see gap 36); no `SecurityEvent|security_event|login_attempt` in
  `services/` or `libs/` (grep → 0); `libs/common/src/main/resources/logback-trove.xml:6` applies
  `%redact` only under `springProfile local,test`; `tests/security/a09-pii-in-logs.sh` is not
  executed in CI.
- Proposal: append-only `security_event` (user_id, actor_id, type enum: LOGIN_OK, LOGIN_FAIL,
  LOCKOUT, TOTP_ENROL/DISABLE, PASSWORD_CHANGE, SESSION_REVOKE, BANK_CONNECTION_ADD/REMOVE,
  EXPORT, ACCOUNT_DELETE, ADMIN_INVITE, KEY_ROTATE, EGRESS_SETTING_CHANGE; ip, ua_summary,
  metadata jsonb); app DB role INSERT/SELECT only; user-facing "Recent activity", admin view,
  1-year retention. Audit path = route template; never log bodies or Set-Cookie; apply the
  redacting converter in the production encoder with a unit test that logs a JWT and an email
  under `prod`; wire a09 into the P6 real-stack job.
- Phase P2 · Effort M · **must**

### P3 — Web

**18. Content-Security-Policy for the SPA and the API**
- Why: CSP is the main XSS blast-radius control for a cookie-authenticated SPA that renders
  bank-supplied merchant strings and LLM output.
- Now: absent — no `content-security-policy|permissions-policy` in `apps/web/nginx.conf` (only
  Cache-Control at `:59,65`) or `infra/full-stack/traefik/dynamic.yml`;
  `tests/security/a05-security-headers.sh` expects it but is syntax-checked only. Good baseline:
  no `dangerouslySetInnerHTML`/`react-markdown` in `apps/web/src`.
- Proposal: nginx `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
  img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self';
  form-action 'self'; object-src 'none'`; ship Report-Only one release with a `/csp-report`
  sink, then enforce; build-time check that Vite emits no inline scripts; API responses
  `default-src 'none'; frame-ancestors 'none'`; same-origin deploy so `connect-src` stays
  `'self'`. Add `Permissions-Policy: camera=(self), geolocation=(), microphone=(), payment=()`.
- Phase P3 · Effort S · **must**

### P4 — Mobile

**19. Runtime "connect to your server" flow, TLS policy, and a `/meta` compatibility handshake**
- Why: every user runs their own trove-core on their own domain; a store binary can ship only one
  baked-in URL, so today the mobile app is unusable for anyone but the owner. Store binaries
  and self-hosted servers upgrade on different schedules, so the app must also learn the server
  version and which optional features (AI, SimpleFIN, OIDC, push) are on.
- Now: `apps/mobile/app.config.ts` `extra.apiBaseUrl` from `EXPO_PUBLIC_API_BASE_URL`; read once
  at module load in `src/api/axiosInstance.ts:6-10` and duplicated in
  `src/hooks/useCoachSession.ts:11-15`, both falling back to `http://localhost:8080`; `eas.json`
  bakes four domains per profile (`api.trove.app`, `staging-api.trove.app`); no URL field in
  `app/(auth)/login.tsx`, `setup.tsx`, or `app/(tabs)/settings.tsx`; `NSAllowsArbitraryLoads:
  false` (`app.config.ts:24`) and no `expo-build-properties`, so the dev profile's `http://`
  cannot work on any physical device; no `version|meta|capabilit` client code and no such
  endpoint in `openapi/*.yaml`; Coach tab unconditional in `app/(tabs)/_layout.tsx:80-84`.
- Proposal: `app/(auth)/connect.tsx` before login: URL input, normalise, reject `http://` except
  RFC1918/`.local` hosts in `__DEV__`, probe unauthenticated `GET /api/v1/meta` →
  `{serverVersion, minMobileVersion, features:{ai, simplefin, ofx, push}, authModes}`; specific
  failure copy (DNS, TLS untrusted → "see docs", not a Trove server, version mismatch); store in
  SecureStore; single `getBaseUrl()` for axios and coach; "Change server" in Settings; optional
  QR pairing from web Settings › Devices. Hide Coach/receipt-scan/SimpleFIN when the feature is
  off; show OIDC only when advertised. Policy: no cert pinning; self-signed certs unsupported —
  direct users to ACME or Tailscale certs. Remove baked URLs from production/preview profiles.
- Phase P4 (endpoint in P2) · Effort L · **must**

**20. Mobile device security posture: app lock, https-only rule, log sanitisation, snapshot
blur**
- Why: a finance app that stays open forever after one login, with a decorative biometric
  button, is below the bar for a lost or shared device; `logError` passes raw `AxiosError`
  objects (with `config.headers.Authorization` and response bodies) to `console.error`, which
  release builds keep in logcat/os_log.
- Now: `app/_layout.tsx:36` checks `isAuthed === undefined` but `authStore.ts:38` initialises it
  to `false`, so the guard redirects before `hydrate()` and the biometric branch in
  `login.tsx:147-160` is unreachable in the normal flow; no `AppState` listener anywhere in
  `app/` or `src/` (grep → 0); `authStore.ts:78` `SecureStore.setItemAsync` without
  `keychainAccessible`; `src/lib/logError.ts` is `console.error(\`[${code}]\`, error)` and its
  header comment claims it "ships errors to the observability backend"; `babel.config.js` has no
  `transform-remove-console`; `expo-screen-capture` not in `package.json`.
- Proposal: `AppLock` gate above `<Slot/>`: lock on cold start and after N minutes in background
  (default 5, user-settable), unlock via `LocalAuthentication` with passcode fallback, `/me`
  re-validation on unlock; `keychainAccessible: WHEN_UNLOCKED_THIS_DEVICE_ONLY`; keep the 15-min
  JWT in memory, only the refresh token in SecureStore; clear on server 401. `logError`
  serialises only `{code, message, status, url}`; `transform-remove-console` in production; a
  unit test that an `AxiosError` with an `Authorization` header never reaches output. Opaque
  overlay on `inactive/background`; `expo-screen-capture` toggle (default on for Android). Fix
  the misleading comment.
- Phase P4 · Effort M · **must**

**21. Mobile release pipeline: build-number source, OTA decision, CI generate step, release job**
- Why: store uploads are rejected when the build number does not increase; EAS Update channels
  are declared without the package, which means a phone-home decision has been made by
  accident; `mobile-ci.yml` runs a generate step whose input directories vanish after P1; the
  docs claim CI submits builds and it does not.
- Now: `app.config.ts` sets `version: '1.0.0'` with no `ios.buildNumber`/`android.versionCode`;
  `eas.json` production has `autoIncrement: true` but `cli` lacks `appVersionSource`; `eas.json`
  sets `channel: staging/production` while `expo-updates` is absent from `package.json` and
  `e2e/eas-config.test.ts:25-29` asserts those channels; no `runtimeVersion`;
  `.github/workflows/release.yml` has no mobile job (only verify → 10 service images → GitHub
  release) although `docs-site/mobile/app-store-release.md` says it runs `eas build/submit`;
  `.github/workflows/mobile-ci.yml:32-33` runs `npm run generate` →
  `apps/mobile/scripts/generate-api-clients.js:10,15` scanning
  `services/*/src/main/resources/openapi`, writing `src/api/generated` which nothing imports.
  **Correction:** the reviewer's claim that the eas-config test hardcodes a personal Apple ID is
  wrong — `eas-config.test.ts:53-55` asserts the ids are *undefined* and `eas.json` submit has
  `ios: {}`; that part needs no fix.
- Proposal: `cli.appVersionSource: "remote"`; marketing version from the `v*` tag. OTA **off**
  for v1: delete `channel` keys and the matching test; document "every change ships as a store
  binary". Point the generator at trove-core's single OpenAPI file, commit the output, make the
  hand-written hooks import it. Add `mobile-release` to `release.yml` (`eas build --profile
  production --non-interactive` on tag/`workflow_dispatch`) or delete the false docs paragraph.
  Add `ios.config.usesNonExemptEncryption: false` (absent in `app.config.ts` and `Info.plist`;
  without it every TestFlight build stalls on "Missing Compliance").
- Phase P4 · Effort S · **must**

**22. Mobile distribution and store prerequisites** *(extends 03 "mobile store readiness 1w";
needs owner)*
- Why: nothing can be submitted without Apple/Google developer accounts, an EAS project, a
  privacy-policy URL, App Privacy labels / Play Data Safety and Financial Features declarations,
  a demo account the reviewer can reach (Guideline 2.1 — a reviewer cannot self-host), and a
  Play closed test (12 testers × 14 days for personal accounts). These have multi-week lead
  times independent of engineering. Sideload-only avoids all of it.
- Now: `EAS_PROJECT_ID` defaults to `''`; `eas.json` submit expects env vars and a gitignored
  `./google-service-account.json`; `apps/mobile/{android,ios}` are gitignored local artefacts
  (`.gitignore:68-69`) so EAS-managed credentials are the only path; no privacy policy anywhere
  (`grep -rIli 'privacy policy'` over docs-site, README, apps → 0); no demo/seed data
  (`grep 'demo|seed' apps/mobile` → 0); `apps/mobile/assets` holds four 70-byte placeholder
  PNGs, no monochrome adaptive icon; app.config `android.permissions` includes
  `READ_MEDIA_IMAGES`, and the default `expo-image-picker` plugin config plus unused
  `expo-camera`/`expo-file-system`/`expo-image` (zero imports) pull `RECORD_AUDIO` and a
  template microphone string into prebuild output.
- Proposal: decide store vs. sideload (Q2). Either way: a release job that builds and attaches
  a signed APK (+ TestFlight) on tag; prune unused packages and set `microphonePermission:
  false`; keep a checked-in expected-permissions test; one canonical privacy document (data
  types, processor = the user's own server, no analytics) at a stable URL from which the Apple
  labels and Play Data Safety answers are derived and checked in; a `mobile-store-checklist.md`
  (assets, screenshots, copy, Financial Features, content rating, review notes). If stores: a
  public demo trove-core seeded nightly with synthetic CAD+USD data and a "Try the demo server"
  button.
- Phase P4 · Effort M · **must** · owner input (Q2)

### P5 — Deploy & harden

**23. The `trove` CLI: up/down/status/logs/backup/restore/update/reset/doctor/support-bundle**
*(extends 04 Product Designer "self-hosting UX")*
- Why: 04 proposed the verb set; nothing placed it in a phase or designed it. Every other
  operational gap here needs this front door. The only installers today are Authentik/mkcert/
  Maven dev orchestrators with default admin creds; a user with port 80 taken or no domain yet
  has no path.
- Now: no `trove`/`trove.sh` at the repo root; `scripts/start-full-stack.sh` runs `./mvnw` on
  the host (a NAS has no JDK) and provisions Authentik; `scripts/bootstrap-user.sh:64-73`
  generates secrets only when the placeholder is still present; `infra/full-stack/.env.example`
  ships `POSTGRES_PASSWORD=trove`, `TROVE_JWT_SECRET=change-me…`, `TROVE_ENCRYPTION_KEY=000…`
  (`:7,11,20`); `docker-compose.yml:47-48` hard-binds `80:80`/`443:443` and hardcodes
  `trove.localhost` in 12 label rules with `TROVE_DOMAIN` appearing once as a literal (`:219`);
  `scripts/` also carries three generations of PowerShell/bash bootstrap scripts; no
  `support-bundle|diagnostics` anywhere.
- Proposal: one POSIX-sh `trove` at the repo root and in the release tarball, needing only
  docker + compose v2. `up`: doctor checks (ports, clock, disk, memory, arch) with the fix on the
  next line, generates every secret with `openssl` and refuses known placeholders, probes 80/443
  and falls back to `TROVE_HTTP(S)_PORT` or a `behind-proxy` profile exposing only
  `127.0.0.1:8080`, derives all `Host()` rules from one `TROVE_DOMAIN`, supports a LAN-IP domain
  with a printed warning, shows the setup URL once, runs `up -d --wait --wait-timeout 300` and
  on timeout prints the last 50 core log lines. `down` never maps to `down -v`; `reset` requires
  typing the hostname. `status` (readiness, version, disk free, DB/receipts size, last backup
  age, cert expiry, last sync). `support-bundle` with `*_SECRET|*_KEY|*_PASSWORD|*_TOKEN`
  redacted. Delete `scripts/*.ps1` and the bootstrap scripts.
- Phase P5 · Effort M · **must**

**24. Public TLS via ACME (HTTP-01 and DNS-01), cipher policy, HSTS/Permissions-Policy headers**
- Why: public exposure was decided; the maintained stack's only TLS is mkcert for
  `*.trove.localhost`. Without publicly trusted certs the mobile app cannot connect (iOS ATS)
  and browsers warn on every visit; NAS/RPi users behind CGNAT have no inbound port 80, so
  HTTP-01 alone will not work for them; `acme.json` must survive a restore or the rate-limited
  CA refuses re-issuance.
- Now: `infra/full-stack/traefik/traefik.yml` has no `certificatesResolvers`; `dynamic.yml:8-14`
  loads `/certs/cert.pem` from mkcert with `minVersion: VersionTLS12`, no cipherSuites/sniStrict;
  ACME exists only in the dead `infra/docker-compose.yml:10-12,136,224` that the plan deletes;
  headers middleware (`dynamic.yml:22-31`) sets HSTS 31536000 + includeSubdomains, frameDeny,
  nosniff, referrer — no `preload`, no Permissions-Policy, no `errors` middleware;
  `traefik.yml` `api.insecure: true`; `docs-site/self-hosting/traefik-tls.md` says public TLS "is
  not yet documented"; no cert-expiry monitor.
- Proposal: `letsencrypt` resolver with `httpChallenge` on `web` by default and `dnsChallenge`
  via `TROVE_ACME_DNS_PROVIDER` + provider token env; `ACME_EMAIL` required when `TROVE_DOMAIN`
  is not an IP/`.localhost`; `acme.json` on a named volume (0600) in the backup set; TLS options
  min 1.2, curated suites, `sniStrict`, X25519/P-256; HSTS 63072000 with `preload` only behind
  `TROVE_HSTS_PRELOAD=true`; dashboard off unless `TROVE_TRAEFIK_DASHBOARD=true`; document
  Tailscale `tailscale cert` as the no-public-domain path; keep mkcert for a `local` profile
  only; `trove doctor` tests external reachability of 80/443 and suggests DNS-01 when closed.
- Phase P5 · Effort M · **must** · owner input (Q4)

**25. Backup that includes the encryption key and receipts, is encrypted, scheduled, retained,
and has a verified restore with stated RPO/RTO** *(extends H4, 03 DoD "documented, tested
backup and restore")*
- Why: a Postgres dump alone is useless — bank credentials are AES-GCM encrypted with
  `TROVE_ENCRYPTION_KEY` from `.env` and receipts move to a local-disk volume; losing either
  silently loses data the dump appears to contain. An unencrypted backup on the same disk is a
  breach vector and useless after disk loss. A backup nobody has restored is a hope.
- Now: absent — `grep -rIli 'pg_dump|pg_restore'` over `scripts/`, `infra/`, `docker/`,
  `.github/` → 0; `infra.md:232` confirms; `.env.example` has no `BACKUP_*`;
  `FieldEncryptionService.java:106` reads the key via `System.getenv`; no RPO/RTO statement
  anywhere outside `docs/recovery`.
- Proposal: `trove backup` (also a tiny cron sidecar, default 03:00 daily): `pg_dump -Fc` +
  tar of receipts + `acme.json` + a manifest (schema version, key versions used — never the
  keys) piped through `age` to a recipient key generated at first `up` and shown once; write
  `backups/trove-<version>-<ts>.tar.age` and `backups/LAST_SUCCESS`; retention 7 daily / 4
  weekly / 6 monthly; optional `BACKUP_RCLONE_REMOTE`; first-run banner until the first backup
  succeeds. `trove restore <file>`: confirm by hostname, stop core, drop/create, `pg_restore`,
  untar, `--include-secrets` for `.env`, wait for readiness, print row counts before/after. CI
  drill (P6, on PRs touching migrations or the CLI): seed 1k transactions + 5 receipts → backup →
  `down -v` → restore → assert counts and a receipt SHA-256. Publish RPO = backup interval (24 h
  default) and RTO ≈ 10 min on a 2 GB box. README states plainly: back up the keys separately;
  the backup is unreadable without them.
- Phase P5 (drill P6) · Effort M · **must**

**26. Upgrade path: pinned version, no `latest` from `main`, migrate-before-serve, version
visibility, CHANGELOG, release gate, published multi-arch web image** *(extends 03 P5 "release
tagging")*
- Why: month six, the user runs `docker compose pull && up`. Every push to `main` overwrites
  `:latest`, so they get an unreleased commit with untagged migrations; Flyway runs in-process at
  boot, so a failed migration leaves a crash-looping container with no backup taken and no
  message; nothing exposes the running version, so "what version are you on?" cannot be
  answered; tagging today publishes after only `./mvnw compile`; the web app is never published
  as an image so a NAS user needs Node to build it.
- Now: `.github/workflows/ci.yml:130-134,189` pushes `:latest` on every push to main/develop;
  `release.yml:107-110` tags both `ref_name` and `latest`, `verify-release` runs only `./mvnw
  compile` (`:32`), matrix is 10 Java services with no `web` entry; `docker-compose.release.yml`
  uses `${VERSION:-latest}` ×10 and has no web service; `docker-compose.integration.yml`
  hardcodes `:latest`; `ci.yml` integration/performance jobs run only on push to main; no
  `build-info|git-commit-id` in any pom (grep → 0) so `/actuator/info` is empty; no
  `CHANGELOG*`; no `org.opencontainers.image.*` labels in either Dockerfile; `apps/web/Dockerfile:8`
  `npm install -g npm@11` floating; only tag is `rescue-baseline-2026-09-03`.
- Proposal: CI on main pushes `:edge`/`sha-<short>` only; release publishes `vX.Y.Z`, `vX.Y`,
  `vX`, `latest` for stable tags; shipped compose uses
  `ghcr.io/${TROVE_IMAGE_OWNER}/trove-core:${TROVE_VERSION}` written by `trove up` and bumped
  only by `trove update [--to]`. `trove update` = backup → pull → one-shot `trove-core migrate`
  container (prints target, fails loudly with the SQL and a `migrate repair` hint) → `up -d
  --wait`; app refuses to serve with pending migrations; on readiness timeout print the exact
  `trove restore` + `TROVE_VERSION=<previous>` commands. `build-info` + `git-commit-id` plugins,
  version from the tag, exposed in `/actuator/info`, `/api/v1/status`, Settings › About,
  `trove status` (running vs. latest available, opt-out). `CHANGELOG.md` (Keep-a-Changelog)
  with mandatory Upgrade-notes and Migrations sections; semver: major = manual step, minor =
  auto-migrating, patch = images only. Release gate: job 1 fails unless Backend, Web, Mobile,
  Integration, Security check-runs are `success` for the SHA; integration tests on
  `pull_request`. Two images — `trove-core` (JAR built in a Maven stage) and `trove-web`
  (nginx) — amd64+arm64 with `docker/metadata-action` labels; `build:` only in a dev override.
- Phase P5 · Effort M · **must** · maintainer input (Q5, CI availability)

**27. Key and secret rotation: JWT `kid`, versioned ciphertext, rotation commands, "I lost my
.env" runbook**
- Why: rotating `TROVE_JWT_SECRET` logs everyone out; rotating `TROVE_ENCRYPTION_KEY` makes
  every stored bank credential unreadable — so neither will ever be rotated after a suspected
  compromise, and a `.env` recreated from the example silently bricks all credentials.
- Now: no `"kid"|jwks|EdDSA` in JWT issuance (`services/api-gateway/src/main`, `libs/common`
  grep → only Authentik JWKS consumption); `FieldEncryptionService.java` has no
  version/keyId (`grep -i 'rotat|version|keyId'` → 0); `docs/adr/0010:40` says rotation is
  "redeploy with new key", which is data loss; `.env.example:20` ships an all-zero key; no
  rotation runbook.
- Proposal: JWT → Ed25519 keypair generated at install (0600 on the data volume), `kid` in the
  header, verifier accepts current + previous for one refresh TTL, `trove rotate jwt`. Field
  encryption: ciphertext prefixed `enc:v<N>:`; `TROVE_ENCRYPTION_KEYS=v2:<hex>,v1:<hex>`;
  `trove rotate encryption` re-encrypts in batches and drops v1 at zero. Startup refuses
  blank/default/short keys with the exact fix. `docs/security/ROTATION.md` plus a "Losing your
  .env" page: without the key, bank credentials are gone — re-link accounts.
- Phase P5 · Effort M · **must**

**28. Actuator, Prometheus metrics, Grafana default password, and the Traefik dashboard off the
public front door**
- Why: on the internet `/actuator/prometheus` and `/actuator/metrics` leak endpoint names, JVM
  details and traffic shape to anyone; Grafana `admin/admin` is a takeover of the monitoring
  host.
- Now: `infra/full-stack/docker-compose.yml:239-244` router `api-actuator` exposes
  `PathPrefix(/actuator)` on the public host to management port 9090;
  `libs/common/.../SecurityConfig.java:86` permitAll includes `/actuator/health` and
  `/actuator/prometheus`; every `application.yml` exposes `health,info,prometheus,metrics`;
  `GRAFANA_ADMIN_PASSWORD=admin` (`.env.example:79`); `traefik.yml` `api.insecure: true`.
- Proposal: delete the `api-actuator` router; Prometheus scrapes the management port on the
  internal network only; public health = the detail-free `/api/v1/status`; exposure =
  `health,prometheus` only; monitoring behind an opt-in profile with a generated Grafana
  password; startup check fails when it equals `admin`; dashboard off by default.
- Phase P5 · Effort S · **must**

**29. Docker log rotation; remove the Loki driver** *(extends H1b / critical-path item 9)*
- Why: the default json-file driver is unbounded; one chatty JVM writing a per-request completion
  line fills a 32 GB SD card in months. Item 9 deletes the Loki blocks but adds no rotation.
- Now: `docker/docker-compose.yml` has 10 `driver: loki` blocks; `infra/full-stack/docker-compose.yml`
  and `docker-compose.release.yml` have no `logging:` at all (grep → 0).
- Proposal: one `x-logging: &logging {driver: json-file, options: {max-size: "20m",
  max-file: "5"}}` anchor on every service; drop Loki; keep JSON on stdout; `trove logs` wraps
  `docker compose logs`.
- Phase P5 · Effort S · **must**

**30. Unbounded disk growth: audit_log retention, receipt downscale, Prometheus retention**
- Why: years on a fixed disk. The gateway writes an audit row for every request (including
  health polls) with no purge; receipts are stored as uploaded (20 MB each, no quota);
  Prometheus keeps everything.
- Now: `V1__create_audit_log.sql` has an index only; no `retention|purge|deleteOlderThan` job in
  `services/` or `libs/` (grep → only annotation `@Retention` hits); no receipt resize (gap 11);
  no Prometheus `retention` flags (grep → 0).
- Proposal: nightly `RetentionJob`: delete `audit_log` older than `TROVE_AUDIT_RETENTION_DAYS`
  (default 90) in 10k batches and skip logging health/static paths at all; receipts per gap 11;
  Prometheus `--storage.tsdb.retention.time=15d --storage.tsdb.retention.size=512MB`; DB size,
  receipts size and free disk in `trove status` and an admin status page.
- Phase P5 · Effort M · **must**

**31. In-app system checks instead of Alertmanager: disk, backup age, cert expiry, failing sync**
*(extends 03 P5 "alert rules", H8)*
- Why: nobody on a home NAS runs Alertmanager. The failures that end a self-hosted install are
  silent: disk fills, backups stop, the cert lapses, the bank sync has been 401-ing for three
  weeks. The product already has email/webhook channels.
- Now: no `rule_files`/`alerting` in either `prometheus.yml` (grep → 0); none of the ten
  `@Scheduled` classes is a self-check; no cert-expiry check anywhere.
- Proposal: `SystemChecksScheduler` (every 15 min): disk < 10 % or < 2 GB; `LAST_SUCCESS` older
  than 1.5× the backup interval; cert on `TROVE_DOMAIN` < 14 days; ≥ 3 consecutive sync
  failures; pool exhaustion. Each produces a persisted `system_alert` → dismissable admin banner
  → optional email/webhook through the existing notification code; matching
  `trove_system_check{name}` gauges and an `alerts.yml` for the observability profile.
- Phase P5 · Effort M · **must**

**32. Operator documentation: install, sizing, upgrade, backup/restore, runbook,
troubleshooting-by-symptom, FAQ** *(extends the doc-accuracy audit (private project notes))*
- Why: the current self-hosting docs describe Authentik, MinIO and Kubernetes — dropped or
  fictional — and `docs.yml` publishes them to GitHub Pages on every push. A product meant to
  run unattended for years needs the docs that get read at 11 pm when it is broken.
- Now: `docs-site/self-hosting/` = `authentik-setup.md`, `minio-setup.md`, `kubernetes.md`,
  `traefik-tls.md` (local-dev only), `docker-compose.md`; `docs-site/mobile/` = two inaccurate
  store pages; no runbook/FAQ/troubleshooting/upgrade/backup page anywhere.
- Proposal: replace with `install.md` (one path: `trove up`), `sizing.md`, `upgrade.md`,
  `backup-restore.md` (RPO/RTO, off-box copies, drill), `runbook.md` (weekly/monthly/yearly
  checklist), `troubleshooting.md` keyed by symptom ("502 from web", "login loops", "sync stuck",
  "disk full", "container restarting") each with the `trove` command and the log line to look
  for, `faq.md`; gate `docs.yml` on a link checker and delete the three fictional pages in the
  same PR.
- Phase P5 · Effort M · **must**

**33. README privacy statement scoped to what is true; `PRIVACY.md` enumerating every outbound
call** *(extends 03 Phase 1 "strip false README claims", 04 "rewrite the README")*
- Why: with a user-configured AI endpoint, SimpleFIN, webhooks and SMTP, "runs entirely on your
  own hardware with no required internet connection" is true only in one configuration; the
  audit showed the claim induces the exposure.
- Now: `README.md:5` still makes the claim; `:10` Privacy Gate badge points at a workflow
  deleted in P0; `:23` "local-first via Ollama"; `:30` "Authentik OIDC + RS256" — all false
  after the decisions.
- Proposal: "Your data lives in a Postgres database and a folder you control. Nothing leaves
  your server unless you turn on: a bank aggregator (SimpleFIN), an AI endpoint, outbound
  webhooks, or email — each listed under Settings › Data egress." Remove the badge.
  `docs/PRIVACY.md` listing every outbound host and the data it carries; "no telemetry"; a CI
  test that greps outbound hosts and fails if one is missing from PRIVACY.md.
- Phase P5 · Effort S · **must**

### P6 — Verify

**34. Security documentation the pen test needs: an accurate threat model, pen-test scope,
`SECURITY.md` and `security.txt`**
- Why: decision 4 requires an external pen test before real bank data; the existing threat model
  describes a different system and cites controls that do not exist, so a tester would be
  misled; an open-source app holding bank data will receive vulnerability reports and has no
  channel for them.
- Now: `docs/adr/0011-threat-model.md:33-58` assumes Authentik lockout, MinIO SSE-S3,
  read-only root filesystems (none in `infra/full-stack/docker-compose.yml`: `grep read_only|
  cap_drop|no-new-privileges` → 0), `AccessControlIT` (disabled), prod log redaction (off); no
  `SECURITY.md`, `security.txt`, `docs/security/` (find → none); no `/.well-known` route in
  `nginx.conf`; no pen-test scope or staging definition in `docs/`.
- Proposal: `docs/security/THREAT-MODEL.md` for trove-core (assets, trust boundaries, personas
  incl. malicious bank file / hostile AI endpoint / stolen unlocked phone, per-threat control +
  file + proving test; ≤ 3 pages; supersedes ADR 0011). `PENTEST-SCOPE.md`: grey-box; web +
  API on a staging domain, both mobile builds, compose review, a backup file; in scope: local
  auth + TOTP + OIDC, sessions, IDOR on every user-owned resource, upload/OFX/CSV parsers,
  SSRF via AI/SimpleFIN/webhook settings, headers/TLS, mobile token storage, admin/invite flows,
  rate limits; out: volumetric DoS, social engineering; 2 users + 1 admin seeded; retest
  included; entry criteria P5 complete + CI green. `SECURITY.md` (private vulnerability
  reporting, 3-day ack, 90-day disclosure) and `/.well-known/security.txt` baked into the web
  image.
- Phase P6 · Effort S · **must** · owner input (Q6)

---

## SHOULD

### P1

**35. Request-id consolidation and propagation to the proxy log and error bodies**
- Now: `libs/common/.../logback-trove.xml` LogstashEncoder carries requestId/userId MDC (good)
  and `RequestIdMdcFilter` honours inbound `X-Request-Id`; but `AuditLogFilter.java:58` mints its
  own UUID and overwrites the attribute; Traefik `accessLog: {}` has no header fields.
- Proposal: keep only `RequestIdMdcFilter`; Traefik `accessLog.fields.headers.names:
  {X-Request-Id: keep}`; `requestId` in every error body and in the web error toast
  ("Reference: …"); `TROVE_LOG_LEVEL` env override for support.
- Phase P1 · Effort S · **should**

**36. Separate migration and runtime DB roles; scram auth; optional TLS to Postgres**
- Now: every service uses `${POSTGRES_USER:-trove}` (`infra/full-stack/docker-compose.yml:76,212,
  269,…`); `postgres-init/01-create-databases.sql` creates databases only, no `CREATE ROLE|GRANT`;
  no `sslmode` anywhere (grep → 0).
- Proposal: `trove_owner` (DDL, Flyway datasource only) and `trove_app` (DML; INSERT/SELECT only
  on audit/security_event; no CREATE); passwords generated at install;
  `POSTGRES_HOST_AUTH_METHOD=scram-sha-256`; JDBC `sslmode=${TROVE_DB_SSLMODE:-prefer}`; port
  not published; startup check fails if the runtime role is superuser.
- Phase P1 · Effort S · **should**

### P2

**37. Session inventory: list/revoke, refresh-family reuse detection, idle timeout,
remember-device**
- Now: `RefreshTokenStore.java` is ValKey-only (`StringRedisTemplate`, `:55`), single-use rotation
  via `GETDEL` (`:117`), `invalidate` deletes one token, no user → sessions index, no reuse
  detection; `JwtProperties.java:39-40` 900 s / 86400 s, no idle timeout; `database.md:675-681`
  plans a `refresh_token` table with ip/user_agent that nothing reads; no endpoint or UI lists
  sessions.
- Proposal: Postgres session table (family_id, refresh_hash, last_seen_at, ip, ua_summary,
  revoked_at, trusted_device_id); presenting an already-rotated token revokes the family;
  absolute 30 d, idle 7 d; `GET/DELETE /v1/auth/sessions[/{id}|/all]`; password or TOTP change
  revokes all others; remember-device = hashed 32-byte cookie skipping TOTP, listed and
  revocable; Settings › Security on web and mobile with the last 20 security events. Drops the
  ValKey dependency.
- Phase P2 · Effort M · **should**

**38. Transaction splits** *(04 scope #5 lists "split" with no design)*
- Now: absent — `grep -i split` over ingestion and budget main code hits only
  `TransactionQuery.java:49 sortRaw.split`; no child table in any migration.
- Proposal: `transaction_split` (transaction_id, category_id, amount, memo), sum must equal the
  parent (service-enforced); parent category null when splits exist; budgets, reports and the tax
  export aggregate from splits when present; "Split" action with a remaining-amount indicator.
- Phase P2 · Effort M · **should**

**39. Budget month materialisation, timezone-correct boundaries, overspend policy**
*(extends D1)*
- Now: `RolloverScheduler.java:34-39` cron `0 0 1 1 * *` processes only `BudgetType.ROLLOVER`
  users in server time and is skipped entirely if the container was down;
  `RolloverService.java:55` `.max(BigDecimal.ZERO)` forgives overspend silently;
  `BudgetService.java:141` only looks up an existing period. D1 covers `setSpent`, not month
  creation.
- Proposal: on read of the current month, materialise it idempotently from the previous month
  (copy allocations; apply rollover for ROLLOVER type) with `rolled_from_period_id`; user
  timezone (gap 15); per-budget overspend setting (forgive vs. deduct) with a visible note;
  delete the cron.
- Phase P2 · Effort S · **should**

**40. Net-worth history from real linked-account balances** *(extends F2, 03 "net-worth
multi-currency")*
- Now: `connected_account` has no balance column (`V1__connected_account.sql`);
  `SimplefinAccount.java:27-28` carries `balance`/`balanceDate` but nothing persists it;
  `NetWorthSnapshotService.java:33-45` sums manual assets/liabilities only and defaults `"CAD"`.
- Proposal: `connected_account.balance/balance_as_of/balance_currency` written by every
  sync/import (OFX LEDGERBAL); snapshots include linked accounts (CHECKING/SAVINGS/INVESTMENT
  as assets, CREDIT as liabilities) in the user's home currency via a stored daily CAD/USD rate
  (Bank of Canada Valet API, manual override); snapshot on every sync; backfill missed days.
- Phase P2 · Effort M · **should**

**41. Scheduled background bank sync with per-account status and a fix hint**
- Now: no `@Scheduled` in `services/ingestion-service/src/main` (grep → 0);
  `SyncController#triggerSync` runs fetch+dedupe inline in the request thread
  (`ingestion-service.md:74`); mobile axios timeout 15 s; `connected_account.sync_status/
  error_message` exist but no UI reads them.
- Proposal: ShedLock'd scheduler (every 6 h, jittered) plus "Sync now" returning 202 with a
  `sync_log` id to poll; Accounts page shows last_synced_at, status and the error with a hint
  (e.g. "SimpleFIN token expired → reconnect"); `trove_sync_runs_total{result}` counter.
- Phase P2 · Effort S · **should**

**42. Category management: per-user categories, rename, merge, safe delete, CA/US seed**
- Now: `category.name UNIQUE` with no `user_id` (`categorisation V1__category.sql:3`);
  `CategoryController.java:68,99,127` = GET/POST/DELETE only, delete does not reassign;
  `transaction.category_id` has no FK (`V2__transaction.sql:14`); the seed is Canada-flavoured.
- Proposal: scope by `user_id` (system seed copied per user; E-Transfer/Interac for CA,
  Zelle/Venmo/ACH for US); PUT rename; `POST /categories/{id}/merge-into/{target}` reassigning
  transactions, splits, overrides, merchant maps and envelopes in one transaction; DELETE
  reassigns to Uncategorised; hide-instead-of-delete for system rows.
- Phase P2 · Effort M · **should**

**43. Explicit dual-client CSRF/CORS contract with Origin validation**
- Now: `CsrfRequiresHeaderFilter.java:44` requires `X-Requested-With` on mutations (web sets it,
  `apps/web/src/api/axiosInstance.ts:12`); cookies are `__Host-`, HttpOnly, Secure,
  SameSite=Strict (`AuthCookies.java:12-30`); `CorsConfig.java:30-40` defaults to
  `http://localhost:5173`, `allowCredentials true`, comma-split env with no rejection of `*`;
  mobile sends Bearer.
- Proposal: document web = cookies + custom header, mobile = Bearer only (never issued cookies);
  for cookie-authenticated mutations also require `Sec-Fetch-Site ∈ {same-origin, none}` or
  Origin ∈ allowlist; fail startup if allowed-origins contains `*` or a non-https origin outside
  the local profile; a `CsrfIT` posting a valid cookie with a foreign Origin expecting 403.
- Phase P2 · Effort S · **should**

**44. LLM output treated as untrusted; conversations encrypted; egress disclosed and opt-in**
- Now: `AiCategorisationService.java:48,95-103` templates the merchant name into the prompt and
  returns the `category` string as-is with no lookup against the category table;
  `model-manager V3__coach_conversation.sql:5 content TEXT NOT NULL` unencrypted; no
  `consent|egress` in `apps/web/src` (grep → 0). (Timeouts are already planned in 03 §3.)
- Proposal: map returned category to a known id by case-insensitive exact match else
  Uncategorised, strip control chars, cap length; encrypt conversation content with the
  field-encryption service; Settings › AI shows the host and "merchant names, amounts and
  receipt images are sent here"; per-user opt-in on top of the admin-configured endpoint.
- Phase P2 · Effort M · **should**

### P3

**45. Notification inbox on both clients; a push-notification policy** *(extends 03 "in-app
notification writer 2d"; needs owner)*
- Now: `in_app_notification` table (`notification-service V3`) and read endpoints in
  `openapi/notification-service.yaml:116,160` with no web consumer (`grep notification
  apps/web/src` excluding tests → 0) and no mobile tab; mobile `registerPushToken.ts:30` posts to
  `/api/notifications/device`, which has no server route (`mobile.md:229`), depends on
  `EAS_PROJECT_ID` (default `''`), and no `addNotificationResponseReceivedListener` exists so
  taps do nothing; Expo Push routes notification text through Expo's servers — a privacy
  decision for a "your data stays on your server" product.
- Proposal: web bell + `/notifications` page and a mobile tab on `GET /v1/notifications`
  (unread count, mark read/dismiss); budget/subscription/sync-failure/system-check events write
  rows. Push: (a) none in v1 — remove the plugin, permission prompt, onboarding step 3 and the
  aps entitlement; or (b) opt-in content-free pings via Expo with `POST/DELETE /v1/devices` and
  tap routing; or (c) direct APNs/FCM with owner-held keys.
- Phase P3 · Effort M · **should** · owner input (Q3)

**46. Measurable accessibility gate for web and mobile** *(extends 03 Phase 4 "a11y pass
1.5w" — web only, no definition of done)*
- Now: web has 52 `aria-*` attributes in total and `QlModal` has `role=dialog` without a focus
  trap (`web.md` I9); no `jsx-a11y`/`axe` in `apps/web/package.json`; mobile has 25
  `accessibility*` props across `app/` and `src/`, `TransactionRow.tsx:15` is a
  `TouchableOpacity` with no label/role, `NetWorthChart` has none, no `useReducedMotion`, fixed
  tab-bar height 84 (`(tabs)/_layout.tsx:25-26`) with no `useSafeAreaInsets`.
- Proposal: WCAG 2.1 AA target; `eslint-plugin-jsx-a11y` + `@axe-core/playwright` failing the
  three e2e specs on serious/critical; focus trap + Escape in `QlModal`; visible focus and
  `prefers-reduced-motion` in tokens. Mobile: row labels ("{merchant}, {amount}, {date},
  {category}"), chart summary label, `accessibilityState` on tabs/chips, Dynamic Type at
  accessibility sizes, reduce-motion, `eslint-plugin-react-native-a11y`, VoiceOver/TalkBack
  walkthrough in the release checklist; add "a11y" to the mobile DoD.
- Phase P3 · Effort M · **should**

**47. Maintenance/error page at the edge during boot, migration, or crash**
- Now: `dynamic.yml` defines only `security-headers` and `compress` middlewares (no `errors`);
  `apps/web/nginx.conf` returns nginx's default 502; `ErrorBoundary` exists but is mounted only
  in `pages/Today/index.tsx`, not at the router root; P3 plans only the SPA 404 route.
- Proposal: Traefik `errors` middleware (502-504) serving a static "Trove is starting… (retries
  every 5 s)" page from a tiny static container, with a distinct message when `/status`
  reports migrations pending; mount `ErrorBoundary` at the router root with "copy diagnostics"
  (version, request id).
- Phase P3/P5 · Effort S · **should**

### P4

**48. Mobile UX states: hydration race, blank detail screens, production error boundary,
safe-area**
- Now: `app/_layout.tsx:36` guards on `isAuthed === undefined` while `authStore.ts:38` initialises
  `false`, so the login form flashes before `hydrate()` resolves; splash hides on `fontsLoaded`
  only and a font error hangs on a blank screen; `budgets/index.tsx` and `receipts/index.tsx`
  ignore `isError` (only 5 screens use it); `transactions/[id].tsx:15`, `budgets/[id].tsx:16`,
  `receipts/[id].tsx:14` `return null` on loading/missing/error; no `ErrorBoundary`/`ErrorUtils`
  anywhere; tab bar hardcodes height 84 / paddingBottom 28 while `android/gradle.properties:47`
  `edgeToEdgeEnabled=true`; the remediation plan (private project notes) DoD requires loading/empty/error states
  for web pages only.
- Proposal: `hydrated` flag in the store; keep splash until fonts (or font error) **and**
  hydration; `<QueryState query skeleton empty>` on every screen incl. detail routes; root
  `ErrorBoundary` export in `_layout.tsx` with a branded retry view; tab-bar padding from
  `useSafeAreaInsets`; extend the mobile DoD line; jest cases per state.
- Phase P4 · Effort M · **should**

**49. Mobile dark-mode parity** *(extends 03 "dark mode 1w"; 04 recommends removing the mobile
toggle until the data layer works)*
- Now: `useTheme` is consumed only by `app/(tabs)/_layout.tsx`; 29 files import static `Colors`
  from `@design/tokens`; splash background is light-only (`app.config.ts:11-15`); no status-bar
  style binding. With `userInterfaceStyle: 'automatic'` an OS-dark device gets a light app with
  a dark tab bar.
- Proposal: `ThemeProvider` + `makeStyles(theme)`, convert screens/components, bind StatusBar and
  navigation theme, splash `dark` variants, keep `DarkColors` in lockstep with
  `apps/web/src/styles/tokens.css`, persist `uiStore.theme` (`system|light|dark`). If not done
  in P4, remove the toggle per 04 rather than ship a half-themed app.
- Phase P4 · Effort M · **should**

**50. Read-only cached data with visible staleness on mobile** *(03 says delete the
"offline-first" claim; this defines the minimum acceptable behaviour instead)*
- Now: QueryClient is memory-only (`app/_layout.tsx:21-25`); no persister, `NetInfo`,
  `onlineManager`, or `dataUpdatedAt` display (grep → 0; `mobile.md:296-303`); error cards
  replace the whole view.
- Proposal: `@tanstack/query-persist-client` with an MMKV persister keyed from SecureStore
  (maxAge 7 d), `onlineManager` on `@react-native-community/netinfo`, an "Offline — showing data
  from {time}" banner, mutations disabled offline (no write queue), biometric unlock against the
  cached token with forced re-validation when back online; wipe on sign-out/deletion.
- Phase P4 · Effort M · **should**

**51. Deep links and auth-aware routing; OIDC on mobile only when the server advertises it**
*(extends 03 "mobile OIDC+PKCE 1.5w")*
- Now: scheme `trove` only (`app.config.ts:64`); no `associatedDomains`/App Links (impossible
  for user-supplied domains anyway); `AuthGuard` replaces to login and discards the requested
  route; no `expo-auth-session` in `package.json`; Apple Guideline 4.8 requires Sign in with
  Apple if a social OIDC button appears.
- Proposal: document "custom scheme only"; preserve `redirectTo` through the guard and honour
  `Linking.getInitialURL` after hydration/unlock; OIDC via `expo-auth-session` PKCE +
  `openAuthSessionAsync`, redirect `trove://oidc/callback`, state validated, tokens exchanged
  only with the connected server; show the button only when `/meta.authModes` includes `oidc`.
- Phase P4 · Effort S · **should**

**52. Crash reporting without a third-party SDK**
- Now: no Sentry/Bugsnag/Crashlytics and no global handler (grep → 0); `logError.ts` claims to
  ship errors and does nothing.
- Proposal: global JS error handler writes the last redacted crash to storage; on next launch
  offer "Send report to your server" → `POST /api/v1/client-errors` stored in Postgres and
  visible in web admin; rely on App Store Connect / Play Console for native crashes.
- Phase P4 · Effort S · **should**

### P5

**53. Memory and Postgres sizing for 1–2 GB boxes** *(extends O5)*
- Now: `deploy:`/`mem_limit` exist only in the dead `infra/docker-compose.yml`;
  `infra/full-stack/docker-compose.yml:74` `max_connections=300` with no
  shared_buffers/work_mem; every JVM `-XX:+UseZGC -XX:MaxRAMPercentage=75.0` (`:14`); ZGC is a
  poor fit below ~1 GB heaps.
- Proposal: `TROVE_SIZE=small|medium|large`; small = core `mem_limit: 768m`,
  `-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k`; Postgres
  `mem_limit: 512m`, `shared_buffers=128MB work_mem=4MB effective_cache_size=384MB
  max_connections=30`, Hikari pool 10; `trove doctor` refuses `up` below 1 GB; sizing table in
  docs.
- Phase P5 · Effort S · **should**

**54. Observability as an opt-in profile with a dashboard that queries real metrics**
*(extends H8, 03 P5 "Micrometer instruments")*
- Now: `micrometer-registry-prometheus` on the classpath but zero custom meters
  (`Counter.builder|Timer.builder|MeterRegistry` in `services/` → 0); 3 of 8 panels in
  `docker/grafana/provisioning/dashboards/trove-overview.json` query `trove_*` metrics that
  never existed; `infra/full-stack/grafana` does not exist so the maintained stack boots an
  empty Grafana; Prometheus + Grafana cost ~400–600 MB, a third of a 2 GB box.
- Proposal: default install has neither; `--profile observability` adds Prometheus (retention
  flags) and Grafana with one provisioned 6-panel dashboard using metrics that exist
  (http_server_requests, jvm_memory_used, hikaricp, disk_free) plus three P2 counters
  (`trove_sync_runs_total`, `trove_import_rows_total`, `trove_backup_last_success_timestamp`);
  delete the fictional panels.
- Phase P5 · Effort M · **should**

**55. Container hardening the threat model already claims**
- Now: no `read_only`, `cap_drop`, `security_opt: no-new-privileges`, `tmpfs`, or
  `pids_limit` in `infra/full-stack/docker-compose.yml` (grep → 0); base images tag-pinned, not
  digest-pinned; ADR 0011:58 claims read-only root filesystems.
- Proposal: `no-new-privileges`, `cap_drop: [ALL]` (+ `NET_BIND_SERVICE` for Traefik),
  `read_only: true` with `tmpfs: [/tmp]` and named rw volumes only for data/receipts;
  `pids_limit`, memory limits; digest pins kept fresh by Dependabot; a CI check asserting these
  keys on every service.
- Phase P5 · Effort S · **should**

**56. Vulnerability-management policy, SBOM, and image signing** *(needs maintainer decision: CI availability)*
- Now: `security.yml` runs CodeQL, gitleaks, npm audit (`--audit-level=high`), OWASP DC
  (`failBuildOnCVSS=7`), Trivy — but CI was unavailable during the rebuild
  (`06-PROGRESS.md`); `release.yml` has no `cosign|sbom|syft|attest` (grep → 0);
  no policy document; Dependabot monthly with majors ignored.
- Proposal: `docs/security/VULN-POLICY.md` (CVSS ≥ 9 in 72 h, ≥ 7 in 14 d, majors quarterly;
  every suppression has an expiry and a justification, checked in CI);
  `docker/build-push-action` with `provenance: mode=max, sbom: true`; keyless `cosign sign`
  with GitHub OIDC; CycloneDX BOMs attached to releases; Trivy against the pushed digest;
  `trove update` refuses CRITICAL unless `--force`; one `cosign verify` line in the README.
- Phase P5 · Effort S · **should** · owner input (Q5)

**57. License compliance: AGPL §13 source offer, third-party notices, mobile-client licence**
*(needs owner)*
- Now: `LICENSE` is AGPL-3.0; no `THIRD-PARTY`/`NOTICE` (find → none); no `license-maven-plugin`
  or npm `license-checker` (grep → 0); no About/Source link in the web app; the FSF's position is
  that GPL-family code cannot be distributed through the Apple App Store, which collides with a
  store-distributed Expo client.
- Proposal: Settings › About shows version, commit and a "Source code for this version" link
  to the tag; CI `license-maven-plugin:add-third-party` + `license-checker --failOn` producing
  `THIRD-PARTY.md` per release; mobile: dual-license `apps/mobile` (Apache-2.0/MIT) or add an
  App Store exception to the AGPL notice.
- Phase P5 · Effort S · **should** · owner input (Q7)

### P6

**58. Mobile CI plan that fits the macOS-minute budget; Detox reduced to a viable smoke suite**
- Now: `.github/workflows/mobile-ci.yml` = lint, prettier, jest, eas-config with no
  `tsc --noEmit` (required by the 03 mobile DoD), `expo-doctor`, bundle export or native
  compile; a macOS Detox job on every mobile push (10× multiplier) while Actions is already
  blocked on spending; `.detoxrc.js:29,33` targets iPhone 15 / `Pixel_5_API_34`; 11 e2e specs
  where `auth.e2e.ts:23-28` "invalid credentials shows error" passes only because
  `localhost:8080` is unreachable and the authenticated specs have no fixture; detox 20.x against
  RN 0.83 + new architecture unverified; `patches/@react-native+gradle-plugin+0.83.6.patch` is
  required for Gradle and undocumented.
- Proposal: PR gate on ubuntu (~15 min): `npm ci` → lint → prettier → `tsc --noEmit` → jest →
  `expo-doctor` → `expo export` → `expo prebuild --platform android --no-install` +
  `gradlew assembleDebug` (cached); iOS `xcodebuild -sdk iphonesimulator` nightly/manual only.
  E2E: a 4-flow Android smoke suite (launch, connect, login, one list) against a real
  trove-core + Postgres started by compose on the runner with a seeded user; evaluate Maestro;
  delete `tax.e2e.ts` with the tab; document local builds incl. the patch-package note.
- Phase P6 · Effort M · **should**
- **Superseded 2026-09-27** by step 27 — Detox now runs against RN 0.86.3 (Expo SDK 57) with
  green Android and iOS runs on this Mac, and `patches/@react-native+gradle-plugin+0.83.6.patch`
  is deleted.

---

## NICE

**59. Recurring manual transactions (rent, cash allowances)**
- Now: no recurring/schedule table in any migration; subscription-detector only detects.
- Proposal: `recurring_transaction` (account, amount, category, cadence, next_date, end_date)
  posted by the ShedLock scheduler with `source_id=recurring:<id>:<date>`; an "upcoming"
  section on Today.
- Phase P2 · Effort S · **nice**

**60. PWA installability for the web app**
- Now: `apps/web/index.html` has no manifest link, theme-color, or apple-touch-icon;
  `apps/web/public` does not exist; no `vite-plugin-pwa`/workbox in `package.json`.
- Proposal: `manifest.webmanifest` (icons 192/512, `display=standalone`), theme-color and
  apple-touch-icon, a service worker precaching the app shell only (never `/api`), update
  prompt; Lighthouse PWA check in web-ci. Covers the partner who will not install a native app.
- Phase P3 · Effort S · **nice**

**61. Reproducible builds**
- Now: no `project.build.outputTimestamp` in any pom; base images tag-pinned
  (`eclipse-temurin:21-jre-alpine`, `node:20-alpine`, `nginx:1.31-alpine`), not digest-pinned;
  `apps/web/Dockerfile:8 npm install -g npm@11` floating.
- Proposal: `outputTimestamp` from the commit time; `@sha256` digest pins; `packageManager`
  field instead of a global npm install; a CI job that builds twice and compares JAR SHA-256 —
  the only way an AGPL self-hoster can confirm the image matches the source.
- Phase P5 · Effort S · **nice**

**62. Dependabot coverage for compose image tags and the post-collapse paths**
- Now: `.github/dependabot.yml:88-106` docker ecosystem for `/apps/web` and `/services/*`
  (matches nothing after P1); no `docker-compose` ecosystem; compose pins `traefik:v3.7`,
  `pgvector/pgvector:pg16`, `valkey:7-alpine`, and floating `minio:latest`/`ollama:latest`.
- Proposal: add a `docker-compose` entry for the single compose file; point docker at
  `/services/trove-core` and `/apps/web`; a quarterly scheduled issue listing ignored majors so
  H9 ("frozen below every major forever") does not recur.
- Phase P5 · Effort S · **nice**

**63. Household member removal and admin user management UI** *(companion to gap 16)*
- Now: covered server-side by gap 16's admin `DELETE /v1/users/{id}`; no admin Users page on
  either client.
- Proposal: Settings › Users (admin only): list, invite, disable/lock, reset password link, reset
  TOTP, remove — each writing a security event. Small once gaps 7, 8, 16 exist.
- Phase P3 · Effort S · **nice**

---

## Questions for the owner

Only items that genuinely need a decision. Each has a recommended default; the plan proceeds on
the default unless told otherwise.

1. **Household data model.** Should household members share one ledger (all accounts, budgets
   and net worth visible to every member) or keep fully separate ledgers with only login/invite
   sharing? *Default: one shared household ledger per install — every user-owned table gets a
   `household_id` scope in the P1 schema, which is cheap now and expensive later. If you answer
   "separate", tables stay `user_id`-scoped, invites only create logins, and a later "share
   with partner" feature becomes a full re-scope.* (gaps 7, 9, 16)

2. **Mobile distribution.** (a) App Store + Play Store listings (needs Apple $99/yr and Google
   $25 accounts, store review, a privacy-policy URL under a legal name and domain, a support
   email, a public demo server for App Review, the Play closed test, and the licence question
   in Q7), or (b) sideload-only — signed APK on each GitHub release plus TestFlight/AltStore
   for iOS? If (a): which Apple account (individual vs. organisation), is `com.trove.app`
   final, and who holds the upload-keystore backup? *Default: (b) for v1.0; revisit stores at
   v1.1 once a demo server exists. Choosing (a) adds gap 22's full checklist, the demo server
   and 4–8 weeks of account lead time.* (gaps 21, 22)

3. **Push notifications.** Ship push at all in v1? If yes, are content-free pings ("New
   activity in Trove") routed through Expo's push relay acceptable for a privacy-first product,
   or should push wait for a direct APNs/FCM path? *Default: no push in v1 — in-app inbox on
   both clients plus local notifications on app foreground; remove the Expo plugin, the
   permission prompt and the onboarding step. Choosing Expo push adds a `/v1/devices`
   endpoint, an Expo access token in server settings, tap routing, and Expo as a named
   processor in the privacy document.* (gap 45)

4. **Public domain and TLS.** What domain will the reference deployment use, is its DNS with a
   provider Traefik's ACME supports (Cloudflare, Route53, DigitalOcean, deSEC …), and do you
   want HSTS `preload` (irreversible for months)? *Default: HTTP-01 by default, DNS-01
   documented first-class for Cloudflare, no preload, Tailscale documented as the
   no-public-domain path. A provider outside Traefik's list means DNS-01 docs are generic and
   CGNAT users are on their own.* (gap 24)

5. **GitHub Actions and public source.** CI was unavailable during the rebuild. Will you restore hosted CI, or make the repository public
   (free Actions, branch protection, and the AGPL source-availability requirement satisfied at
   once)? And do you want signed images and SBOMs published to GHCR? *Default: make the repo
   public before P5 and publish signed images. Until one of these happens, the release gate,
   the restore drill, the security workflow and the mobile CI plan cannot be proven and local
   runs remain the only gate.* (gaps 26, 56, 58)

6. **Security contact and pen test.** Which contact (email or GitHub advisory URL) should
   `SECURITY.md` and `security.txt` publish? What budget and vendor preference for the external
   pen test, and can a staging subdomain be provisioned for it? *Default: GitHub private
   vulnerability reporting + a project email alias; grey-box pen test scoped per gap 34 on
   `staging.<domain>` after P5 exit; no bug bounty. Without a budget, decision 4's "pen test
   before real bank data" becomes an internal adversarial review only, and the README must say
   so.* (gap 34)

7. **Licensing of the mobile client.** Keep AGPL-3.0 for everything, or license `apps/mobile`
   separately (Apache-2.0/MIT, or AGPL + an explicit App Store exception) so it can be
   distributed via the App Store without a licence conflict? *Default: AGPL + App Store
   exception clause added to the mobile notice — only matters if Q2 = (a); under (b) keep AGPL
   unchanged.* (gap 57)

8. **Where the AI endpoint is configured.** `06-PROGRESS.md` says "user-configured
   OpenAI-compatible endpoint"; the egress-policy review recommends it be an **admin/instance**
   setting (one allow-listed host, DNS-pinned) with per-user opt-in, because a per-user URL is a
   per-user SSRF primitive. *Default: admin-level endpoint, per-user opt-in toggle and
   disclosure. If you want true per-user endpoints, gap 10's policy must be enforced per user
   and the privacy statement must say each member can send household data to a host of their
   choosing.* (gaps 10, 44)
