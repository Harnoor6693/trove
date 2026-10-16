# Trove — Verified Findings Dossier

> **Historical record, 2026-09-03.** Findings about the pre-rebuild ten-service code at `9ad1b5f`. The system it describes no longer exists. The instruction in the note below applied on 2026-09-03; `docs-site/` is now regenerated from the code and is the maintained description. Current state: `docs-site/guide/architecture.md`.

**Audit date:** 2026-09-03 · **Commit audited:** `9ad1b5f` (main)
**Method:** 8 parallel forensic code audits + direct verification by the verifier.
**Status of claims:** every load-bearing claim below was verified against source or by
executing the build. Where two audits disagreed, the contradiction was resolved by running
the code. Section O records three findings that were CORRECTED during verification —
read it before trusting any earlier statement of the same fact.

> Treat every pre-existing document in this repo (README, docs/, docs-site/, ADRs, CodeTour)
> as a REQUIREMENTS ARTEFACT, not a description. See section N: the prose was generated from
> intent, not from the code. This file and its siblings in `docs/recovery/` are the only
> documents in the repository known to describe the system as it actually is.

---

# TROVE — CONSOLIDATED AUDIT FINDINGS (verified ground truth, 2026-09-03)

Project: self-hostable personal finance platform. Java 21 / Spring Boot 4.1, 10 microservices,
React 19 web SPA, React Native/Expo mobile. AGPL-3.0. Built Apr-May 2026 in "Stages/Phases"
(reached "Stage 3 Phase 30 - v1.0.0 Release Preparation"). Last human commit 2026-05-19.
325 commits, ~219 of them Dependabot. Dead ~3.5 months.

Size: 22,583 LOC main across 10 services; 2,533 libs; 4,109 plugins; 7,929 web TS/TSX;
8,455 mobile TS/TSX. 919 Java tests. 42 Flyway migrations. Checkstyle: 0 violations.

## THE ONE-LINE DIAGNOSIS
Every component was built to a high standard in isolation. Almost nothing is wired to
anything else. The seams between components are where the project died.

## A. BUILD / CI STATE (verified by execution)
- A1. `main` DOES NOT COMPILE. receipt-processor: `MinioStorageService.java:5` imports
  `io.minio.http.Method`; minio 9.x moved it to nested `io.minio.Http.Method`. Verified:
  the 9.0.3 jar contains ZERO entries under `io/minio/http/`, only `io/minio/Http$Method.class`.
  Introduced by Dependabot 8.5.10->9.0.0 on 2026-05-04 (PR #82). Broken ~4 months.
  NOTE: incremental builds give a FALSE PASS from stale target/classes. Must use `clean`.
- A2. 24 Java test ERRORS across 8 modules, all one cause: WireMock/Jetty
  `NoSuchMethodError: Environment.ensure`. pom.xml:240 has a comment explicitly pinning
  Jetty to 12.0.14 to prevent exactly this; Dependabot overwrote the pin to 12.1.11 (PR #160).
  The fix is to restore the value its own comment describes.
- A3. Java tests otherwise: 919 tests, 0 failures, 24 errors, 0 skipped. 13/21 modules green.
- A4. Checkstyle: GREEN, 0 violations, 25 modules. It IS a hard gate (failsOnError=true).
- A5. Web: build GREEN. Lint RED (217 errors: 159 = eslint-plugin-react-refresh 0.4->0.5
  ESM break from Dependabot; 58 genuine). Tests 2/171 fail — a date time-bomb in
  useDailySpendingTrend.ts (unguarded Map.set creates an 8th bucket for out-of-window dates;
  MSW fixtures hardcode 2026-05-14, now outside the rolling 7-day window).
- A6. Mobile: 40 of 48 Jest suites FAIL TO RUN. react-test-renderer 19.2.0 vs react 19.2.8
  (Dependabot bumped react, not the transitive test renderer). Only 64 tests execute.
  Mobile lint GREEN. Production TS is effectively type-clean (1 real error).
- A7. docs-site: GREEN.
- A8. WHY CI DIDN'T CATCH ANY OF IT: dependabot-auto-merge.yml:24 runs
  `gh pr merge --auto --squash`. `--auto` only defers to CI when branch protection declares
  REQUIRED status checks. Without it, PRs merge over red CI. ci.yml itself is well-written.
  This is a governance gap, not a missing test.
- A9. Zero git tags exist => release.yml has NEVER run.
- A10. .gitleaks.toml contains ONLY an [allowlist] block — no [[rules]], no
  `[extend] useDefault=true`. GITLEAKS_CONFIG replaces the default ruleset, so the secret
  scan loads zero rules and detects nothing by construction. One-line fix.
- A11. privacy-gate.yml greps surefire XML for the literal string HIGH_SENSITIVITY_CLOUD_LEAK.
  Nothing emits it. The gate can never fire. It is theatre.
- A12. Real .env is correctly gitignored, untracked, 0600, never committed. Safe.

## B. THE INTEGRATION LAYER IS THE PROJECT'S GRAVE (found independently by 2+ agents)
- B1. GATEWAY STRIPS `Authorization`. ProxyController.java:173-191 builds a FRESH HttpHeaders
  with only X-Trove-*, X-Request-Id, X-Forwarded-For. Downstream services enforce
  `anyRequest().authenticated()` and read only `Bearer`. => EVERY proxied request 401s.
  ~35+ endpoints across 4 services unreachable via the documented entry point (:8080).
  Corroborated independently by the gateway auditor and the budget/tax auditor.
- B2. HEADER NAME MISMATCH. Gateway sends `X-Trove-User-Id`; every ingestion controller reads
  `X-User-Id`. Nothing in services/ reads X-Trove-User-Id. => 400s, and /v1/dashboard/today
  silently returns ALL ZEROS for every user.
- B3. GATEWAY DROPS THE QUERY STRING. ProxyController.java:158 uses getRequestURI();
  getQueryString() is never called anywhere in the repo. => all filtering, sorting,
  pagination inert end-to-end.
- B4. Proxy forwards body as byte[] with NO Content-Type => every proxied POST/PUT would 415.
- B5. Why no test caught it: every downstream boundary is WireMocked, and ProxyControllerIT
  ASSERTS THE WRONG HEADER NAME (:68,:83 verify X-Trove-User-Id), cementing the bug.
  The one cross-user access-control test, AccessControlIT, is @Disabled.

## C. CLIENT/SERVER CONTRACT DRIFT
- C1. MOBILE: all 12 distinct API path prefixes lack `/v1`. Gateway is /api/v1/**.
  => every mobile request 404s. 3 endpoints don't exist at all: /api/auth/login (backend is
  OIDC-only, not password), /api/notifications/device, /api/coach/stream.
- C2. WEB has TWO API layers. `src/api/*.ts` = correct (/v1/...), used by Today/auth/onboarding
  (the final commit 7088df3). `src/hooks/use*.ts` = stale, missing /v1, 404s.
  The project was abandoned MID-MIGRATION. This reframes the work: finish a migration,
  don't rewrite.
- C3. Coach is architecturally impossible on both clients: they use EventSource (GET-only);
  the backend SSE endpoint is POST /v1/chat/completions with a JSON body. No URL fix helps.

## D. FEATURES THAT LOOK DONE BUT ARE INERT (each blocked by ONE missing link)
- D1. BUDGETS: `Envelope.spent` is NEVER WRITTEN. grep setSpent in src/main => zero hits.
  So healthPercentage always 0, isOverPace always false, and rollover carries the FULL
  allocation forward every month, compounding without bound. The core budgeting feature
  is inert. No transaction->budget integration exists in any service.
- D2. SUBSCRIPTION DETECTION: bootstrap deadlock. The nightly scheduler iterates
  `findDistinctActiveUserIds()` = SELECT DISTINCT userId FROM DetectedSubscription.
  Only users who ALREADY have a subscription get scanned. Fresh install: zero rows =>
  zero users => zero scans => zero rows, forever. It can never detect its first subscription.
  There is no manual trigger endpoint.
- D3. NOTIFICATIONS: delivery status is a lie. Dispatcher initialises status="SENT" and only
  downgrades in a catch, but NO channel ever throws (all three swallow). status is
  permanently "SENT", error_message permanently null. FAILED is unreachable.
  Also: the in-app notification centre has NO WRITER — 5 endpoints over a table nothing fills.
  Also: webhook delivery has NO PRODUCER — scheduleDelivery is called only from its own test.
  Service's own bootstrap javadoc says: "currently a stub pending full implementation".
- D4. CATEGORISATION: self-learning write-back is dead. `categorise` is
  @Transactional(readOnly=true) and calls persistMerchantMapping by SELF-INVOCATION, so the
  inner @Transactional never applies => insert never flushes, and the catch downgrades it to
  a warn. Every categorisation re-runs the LLM forever. Also the prefix query direction is
  INVERTED vs its own javadoc, and merchant_category_map ships with zero rows.
  Also: transaction.category_id is written by NOTHING. Transactions are never categorised.
  Also: /v1/categorise* is not routed by the gateway at all and no service calls it.
- D5. RECEIPTS: module doesn't compile (A1).
- D6. TAX EXPORT: Sa100PdfExporter is a @Component nothing calls. No export endpoint.
- D7. GATEWAY: GET /api/v1/health and /api/v1/audit are secured + published in OpenAPI but
  HAVE NO CONTROLLER => 404. HealthAggregator is a complete orphaned component.
- D8. [CORRECTED IN VERIFICATION] @EnableScheduling EXISTS in 4 services (notification,
  model-manager, subscription-detector, budget) via a SchedulingConfig class. It is ABSENT
  from api-gateway, which is the one service that has an unbacked @Scheduled
  (RateLimitService.reloadConfig). So: the gateway's rate-limit config reload never fires and
  `trove.rate-limit.enabled` is a dead flag — but the OTHER services' schedulers DO run.
  This makes D2 (subscription bootstrap deadlock) and the budget rollover compounding bug
  LIVE defects, not dormant ones.
- D9. Gateway has no route for /api/v1/goals or /api/v1/webhooks => budget-service GoalController
  (4 endpoints) and notification WebhookController (3 endpoints) are unreachable.

## E. DOMAIN CORRECTNESS — TAX ENGINE (highest-risk area)
README claims "UK Self-Assessment and US Schedule C / 1040".
- E1. NO Self-Assessment. No self-employment profit, no Class 2/4 NI, no payments on account,
  no allowable expenses, no dividend/savings allowances, no pension relief. "SA100" is a
  SLIP TYPE whose face value is added to gross income. Self-employment = "a number you type in".
- E2. NO Schedule C. No business income/expense netting, no SE tax. 1099 income runs through
  EMPLOYEE-side FICA (6.2%+1.45%) instead of SECA (15.3% on 92.35%). Understates by ~half
  the payroll component.
- E3. FICA levied on INVESTMENT income — 1099INT/1099DIV are included in the FICA base.
  $200k of dividends is charged ~$10.5k of phantom Social Security tax.
- E4. US state tax computed on FEDERAL taxable income. No state has that base.
- E5. AMT computed from POST-standard-deduction income. Real AMTI starts from AGI and
  disallows it. AMT can essentially never bind.
- E6. GB marginal rate returns 20% for anyone earning under the personal allowance.
- E7. GB NI computed on income NET of deductions; Class 1 NI is on gross.
- E8. tax-rates-gb-2024.yml IS NEVER LOADED. No GB @ConfigurationProperties class exists.
  All GB rates are hardcoded in GbTaxEngine.java:25-34. The YAML is decoration.
- E9. Rates hardcoded to 2024 with NO YEAR DISPATCH. GET /v1/tax/gb/2019 and /gb/2030 both
  return 2024-basis numbers silently.
- E10. CA: CPP/EI treated as income DEDUCTIONS; they are non-refundable CREDITS.
- E11. TaxDeduction and RrspTfsaFhsaRoom have entities, tables, repositories, and are READ by
  the calculation — but there is NO ENDPOINT to populate them. Itemized deductions are
  always 0; RRSP/TFSA/FHSA always 0.
- E12. The rate TABLES are accurate. The ARITHMETIC USING THEM is wrong in ways that
  materially change the answer. Verdict: a toy wearing a domain costume.

## F. MONEY / CORRECTNESS CROSS-CUTTING
- F1. Currency hardcoded "CAD" in budget-service (5 sites), subscription-detector,
  notification digest, tax schema defaults, web (en-CA), mobile onboarding.
  Mobile lets you PICK CAD/USD/GBP/EUR and then never uses it — everything renders "$".
  Web MoneyDisplay accepts a `currency` prop and ignores it.
- F2. Net worth sums assets and liabilities across ALL CURRENCIES INDISCRIMINATELY.
  A USD asset and a GBP liability are subtracted as if the same unit.
- F3. Subscription detector does money math in `double`.
- F4. Deduplication hardcodes "CAD" and DEBIT as fallbacks — silent data corruption for a
  product targeting UK/US.

## G. SECURITY (ranked)
- G1. CRITICAL — IDOR in ingestion-service. User identity comes from the CLIENT-CONTROLLED
  `X-User-Id` header, never tied to the JWT sub. Any authenticated user reads/mutates any
  other user's transactions and accounts by changing one header. docker-compose publishes
  8081:8081 on the host, so the gateway isn't even a barrier. TransactionControllerIT:144-149
  DEMONSTRATES this (mints a token for one UUID, passes a different UUID, request succeeds).
- G2. CRITICAL — SSRF via SimpleFIN setup token. ExchangeSetupTokenService validates a
  user-supplied URL with `decodedUrl.startsWith("http")`. No scheme allowlist, no host/IP
  filter, no private-range block. 169.254.169.254 and localhost both pass. The response is
  then fetched again and STORED ENCRYPTED => persistent SSRF primitive.
- G3. HIGH — Mass assignment on POST /v1/categories. The JPA ENTITY is bound as @RequestBody.
  Posting a system category's id performs a MERGE, overwriting it and flipping is_system=false
  so it becomes deletable. Categories are global => corrupts the taxonomy for every user.
- G4. HIGH — HS256 shared symmetric secret across all 10 services. README claims RS256; the
  code is `Keys.hmacShaKeyFor()` and a test asserts EXPECTED_ALG="HS256". Any one compromised
  service can mint tokens accepted by all others. No iss/aud/jti, no revocation, no rotation.
- G5. HIGH — Weak default secret ships everywhere:
  `TROVE_JWT_SECRET:-change-me-minimum-32-chars-in-production!!` — exactly long enough to pass
  the 32-char validation, so the stack boots with a publicly-known signing key.
  TROVE_ENCRYPTION_KEY defaults to 64 ZEROS (the AES key protecting bank credentials).
- G6. HIGH — Webhook SSRF via DNS rebinding: the URL is validated at registration time and
  re-resolved at send time with no re-check. IPv6 unique-local (fc00::/7) and CGNAT
  (100.64/10) are unblocked.
- G7. MED — X-Forwarded-For trusted unconditionally with no trusted-proxy allowlist =>
  per-IP rate limit fully bypassable and audit-log source IPs forgeable.
- G8. MED — /api/v1/auth/** rate limit is 600 req/min burst 1200. Auth brute force unthrottled.
  V4 migration also DELETED V2's LLM throttle on /api/v1/chat/**.
- G9. MED — `/test/**` is permitAll in the SHARED production SecurityConfig, in every service.
- G10. MED — Bean Validation is INERT in ingestion + categorisation: they never depend on
  spring-boot-starter-validation, so every @Valid/@NotBlank is decorative.
- G11. MED — ResponseStatusException maps to HTTP 500 across all of ingestion-service, because
  GlobalExceptionHandler's @ExceptionHandler(Exception.class) wins over Spring's resolver.
  Intended 400/403/404 all become 500. A @WebMvcTest passes because it never loads the advice.
- G12. MED — Timing leak in ServiceTokenAuthFilter (MessageDigest.isEqual on raw bytes, not
  digests; short-circuits on length mismatch).
- G13. LOW — web TestHarness route is unauthenticated and SHIPPED IN THE PRODUCTION BUNDLE.

## H. INFRASTRUCTURE
- H1. README quick-start CANNOT WORK. Three independent hard blockers: (a) all 10 Dockerfiles
  COPY a prebuilt jar that doesn't exist and README doesn't say to build first at that point;
  (b) all 10 services set `logging.driver: loki`, requiring a Docker plugin documented NOWHERE;
  (c) api-gateway requires AUTHENTIK_CLIENT_ID/SECRET with no defaults, and compose never
  sets them => Spring context refresh aborts.
- H2. HIGHEST-VALUE SINGLE INFRA FIX: supply AUTHENTIK_CLIENT_ID/SECRET. It simultaneously
  unblocks the README path, CI integration-tests, performance-tests, and release smoke test.
- H3. NO KUBERNETES. README advertises "Kustomize overlays" and a Kubernetes self-hosting
  guide. `git log --all --diff-filter=A -- 'infra/k8s*'` is EMPTY. It never existed.
  smoke-test.sh:112 runs `kubectl apply -k infra/k8s/` against the void (saved only by a
  `command -v kind` guard that is never satisfied on ubuntu-latest).
- H4. NO BACKUP OR DR WHATSOEVER. No pg_dump, no snapshot tooling, no restore procedure,
  no RPO/RTO. The only documented "stop" command is `docker compose down -v`, which destroys
  the volumes. For a product holding encrypted bank credentials and receipt images.
- H5. Six competing compose files, three generations of bootstrap scripts, three .env.example
  files (52/35/19 vars) that disagree. 46 env vars referenced but undocumented; 14 documented
  but unused; 15 have no default and hard-fail startup.
- H6. The maintained path is scripts/start-full-stack.sh -> infra/full-stack/, which is
  genuinely good (Traefik + docker-socket-proxy, HSTS, TLS>=1.2, non-root containers,
  restart policies, real healthchecks). It is NOT linked from the README quick-start.
- H7. infra/docker-compose.yml is completely dead — mounts two config files that don't exist,
  and its build context makes every service build fail. Superseded and never deleted.
- H8. OBSERVABILITY: Prometheus scrape targets are 10/10 port-accurate (genuinely well done).
  BUT there is not a single custom Micrometer instrument in the entire Java codebase
  (`grep io.micrometer` in services/ libs/ => zero hits), so 3 of the 8 Grafana panels query
  metrics that have never existed. Loki receives nothing in the maintained stack.
  No alert rules, no recording rules, no alertmanager.
- H9. Dependabot ignores ALL semver-major for maven, docker, and the key npm packages, and
  auto-merges every minor/patch. With no human left, the project is permanently frozen below
  every major boundary while drifting continuously within them. That combination is exactly
  what produced A1, A2, A5, A6.
- H10. `./mvnw package -DskipTests` still runs the full web vitest suite — apps/web is a Maven
  module whose npm-test execution binds to the test phase with no skip config.

## I. WEB APP
- I1. 1 of 14 pages is genuinely wired to a live backend (Today). 5 render HARDCODED FIXTURES
  in real components (Transactions, Budgets, Subscriptions, NetWorth, TaxCA) — including
  literal "+12.4% YTD" and "+$2,340.00". 3 call endpoints that 404. 2 are stubs.
- I2. HARD FIRST-RUN LOCKOUT. Onboarding step 4 (SimpleFIN) has canContinue=false and its
  Continue is disabled until the mutation succeeds. There is NO skip path (grep confirms).
  AuthGuard forces anyone with onboardingCompleted=false back to /onboarding.
  => A self-hoster without a SimpleFIN token can NEVER enter the app. And there is
  NO LOGOUT BUTTON ANYWHERE (authStore.logout is fully implemented, tested, and imported by
  zero components), so they cannot even escape.
- I3. A preferences-endpoint outage dumps EXISTING users back into onboarding, because
  AuthGuard reads `!prefs.data?.onboardingCompleted` and usePreferences has no isError branch.
- I4. Every anonymous first visit is treated as an expired session: /auth/me 401 -> the
  interceptor fires a doomed refresh -> hard `window.location` redirect to /login?error=expired
  -> and `expired` is not in ERROR_MESSAGES, so a brand-new visitor is greeted with
  "Sign-in failed. Please try again." before they have tried anything.
- I5. On mobile viewports the shell slices nav to 5 items with no overflow menu =>
  Receipts, Tax, Net Worth, Accounts and Settings become COMPLETELY UNREACHABLE.
- I6. Dark mode: a Settings toggle writes uiStore.theme; NOTHING reads it. Zero @media
  prefers-color-scheme, zero [data-theme]. Dark mode does not exist.
- I7. 98 Playwright tests in tests/ are ORPHANED — playwright.config testDir is ./e2e and
  vitest is limited to src/**. No runner sees them. They are also semantically wrong
  (seed a localStorage key authStore never persists).
- I8. No 404 route and no errorElement => a typo'd URL shows React Router's raw developer
  crash page.
- I9. Accessibility: 9 of 14 pages have ZERO aria attributes. Modal has no focus trap.
  Dropdown menu items are always in the a11y tree even when closed. Radio cards handle
  Enter but not Space. Receipts upload zone is role=button with no onKeyDown.

## J. MOBILE APP
- J1. Every screen: 0 fully working / 8 partial / 4 broken or dead. No screen renders fake
  data — the app is honest, it just cannot reach the API.
- J2. "Offline-first sync" (a headline README claim) is ZERO LINES OF CODE. No SQLite, no MMKV,
  no AsyncStorage, no NetInfo, no persistQueryClient, no outbox, no conflict resolution.
  Not a partial implementation — nothing. Every cold start is an empty cache.
- J3. Login implements EMAIL/PASSWORD against an OIDC-only backend. No expo-auth-session,
  no PKCE. The backend's real /api/v1/auth/token takes an idToken. Different auth model,
  not a path typo. No refresh handling at all => even if login worked, hard logout every 15 min.
- J4. Coach tab CRASHES on first send: crypto.randomUUID() with no polyfill on Hermes.
- J5. Settings screen has an infinite-render bug (object-returning zustand v5 selectors).
  The SAME AUTHOR FIXED THE IDENTICAL BUG in _layout.tsx (commit c8ea408) and wrote it up.
  Settings was missed, and its test over-mocks both stores so the bug cannot surface.
- J6. Receipts route leaks as an undeclared 9th tab (no _layout.tsx) and has a SECOND
  QueryClient, so captured receipts never appear in the list until app restart.
- J7. App icons and splash are 70-byte 1x1 transparent PNGs. Hard App Store / Play blocker.
- J8. EAS_PROJECT_ID defaults to '' => eas build fails AND push registration silently no-ops.
- J9. Missing ios/PrivacyInfo.xcprivacy — mandatory since 2024 for an app collecting financial
  data, camera, photos and biometrics.
- J10. The deleted native files in git status are `expo prebuild` COLLATERAL DAMAGE, not a
  deliberate removal. The deleted AndroidManifest.xml was a documentation STUB whose own
  comment warned prebuild would overwrite it "until the config plugin lands". The plugin never
  landed. Recovery is `git checkout`, but the feature was DEAD ON ARRIVAL anyway: no JS->native
  token bridge (nothing ever writes the SharedPreferences/UserDefaults keys both clients read),
  the target endpoint /api/ingestion/channel/6 does not exist, and there is no iOS NSE target
  in the pbxproj at all.

## K. WHAT IS GENUINELY GOOD (do not throw this away)
- 42 Flyway migrations, all services `ddl-auto: validate`, per-service DB isolation.
  Schema discipline is production-grade.
- Checkstyle clean across 25 modules. Consistent structure, heavy javadoc.
- 919 Java tests with real Testcontainers ITs (Postgres, MinIO, valkey) and WireMock.
- The gateway's auth/BFF layer: OIDC + PKCE, __Host- cookies, HttpOnly, SameSite=Strict,
  refresh rotation coalesced through a singleton, CSRF header filter. Well tested (173 tests).
- Web: the Today page, src/api/* adapters, authStore and axiosInstance are production quality.
- Mobile: coherent design system, SecureStore with corrupt-blob recovery, biometric
  re-validation against the server, exhaustive assertNever on billing cycles.
- infra/full-stack/: Traefik + docker-socket-proxy on an internal network, HSTS, TLS>=1.2,
  non-root containers, real healthchecks, restart policies.
- Prometheus scrape configs 10/10 port-accurate.
- Notification templates use th:text only + HtmlUtils.htmlEscape + an allowlist. Done right.
- SafeWebhookUrlValidator, ApnsChannel JWT caching, FcmChannel 410 handling — real work.
- start-full-stack.sh is 871 lines of careful, idempotent, well-commented orchestration.

## L. THE META-FINDING
There are only 3 TODOs and 0 FIXMEs in 51k lines of Java. The code does not LOOK unfinished.
It is javadoc'd, validated, consistently structured, and heavily tested. What is missing is
every seam. Each headline feature is blocked by exactly ONE missing link:
  budgets      -> no setSpent caller
  subscriptions-> a self-referential scan query
  notifications-> silently-swallowed channel errors + no in-app writer + no event producer
  categorisation-> a readOnly self-invocation
  tax export   -> no endpoint
  receipts     -> one import line
  everything   -> a gateway that drops the Authorization header
"v1.0.0 Release Preparation" does not describe this codebase.

## M. LATE FINDINGS (wave 1 final report + verification)

### M0. *** THE SINGLE WORST BUG: TROVE CAN STORE ONE TRANSACTION PER ACCOUNT ***
VERIFIED DIRECTLY BY THE VERIFIER. Chain of evidence:
  - `RawTransaction` record field order is (id, sourceId, accountId, ...).
  - ALL FOUR bank normalizers pass a CONSTANT PLUGIN NAME into the sourceId slot:
      SimplefinNormalizer.java:53  -> "simplefin"
      ImapNormalizer.java:36       -> "imap"
      OfxNormalizer.java:74        -> SOURCE_ID (a constant)
    The genuinely unique per-transaction id (tx.id(), fitId, emailMessageId) goes into
    `RawTransaction.id`.
  - DeduplicationService.java:63-66 step 1 does an EXACT MATCH on
    findByAccountIdAndSourceId(accountId, incoming.sourceId()).
  - `incoming.id()` is NEVER READ anywhere in ingestion (grep across dedup/ and sync/ => empty).
  - V2__transaction.sql:23 adds UNIQUE INDEX idx_transaction_source ON
    transaction(account_id, source_id) WHERE source_id IS NOT NULL.
=> The first transaction of a sync inserts. EVERY subsequent transaction matches
   (accountId, "simplefin") and is treated as a duplicate -> SKIPPED/UPDATED.
   AT MOST ONE TRANSACTION PER ACCOUNT CAN EVER EXIST.
   Transaction ingestion — the foundation of the entire product — is fundamentally broken.
   Fix is ~4 lines (pass the unique id into sourceId, or dedup on id). Impact is total.

### M1. model-manager has NO AUTHENTICATION AT ALL (verified)
It is the ONLY service of 10 with zero JwtAuthFilter references (verified per-service grep:
api-gateway 11, tax-engine 3, categorisation 2, report 2, budget/ingestion/notification/
receipt/subscription 1 each, model-manager 0). Its chain sets .anyRequest().authenticated()
with NO authentication mechanism => every /v1/** endpoint 401s in production.
`trove.jwt.secret` is set in its application.yml and read by nothing.
The AI Coach — the README headline feature — is therefore unreachable.
Every test masks it by injecting auth directly (@WithMockUser / authentication(...)).

### M2. THE INTEGRATION TEST SUITE RUNS ZERO TESTS AND REPORTS GREEN (verified)
All 6 classes in tests/integration are @Disabled ("blocked on /api/v1/auth/register").
The CI job literally named "Integration Tests (All 10 Services)" (ci.yml:194) executes
zero tests and passes. Nothing in this system has EVER been proven to work end-to-end.
Worse, the tests were written against an API that never existed: they post /api/receipts
(real: /v1/receipts/upload), await status "PROCESSED" (enum has COMPLETE), read
data.merchant/totalAmount (real DTO: extractedMerchant/extractedAmount).

### M3. 5 OF 7 PLUGINS ARE ON NO SERVICE'S CLASSPATH
Only plugin-bank-simplefin (ingestion) and plugin-model-paddleocr (receipt) are consumed.
ofx, imap, bank-emails, ollama-adapter, litellm-adapter => ~7,000 lines of compiled,
tested, JaCoCo'd, unreachable code. "Six ingestion channels" is one channel, and that one
returns nothing (M0).

### M4. THERE IS NO PLUGIN ARCHITECTURE. ADR-0003 IS MATERIALLY FALSE.
Repo-wide: ServiceLoader 0 hits, META-INF/services 0 dirs, URLClassLoader 0, spring.factories 0,
zero @Component in the whole plugin tree. It is 7 ordinary Maven jars.

### M5. PRIVACY-ENGINE IS THEATRE (the README's marquee differentiator)
- 6 of 8 public types are DEAD (annotation, aspect, context, classifier, rules, autoconfig
  beans nothing consumes).
- privacy-rules.yml (45 field classifications) is NEVER LOADED — no spring.config.import,
  no @PropertySource. The classifier is constructed with three EMPTY sets.
- privacy-gate.yml:27 greps surefire output for "HIGH_SENSITIVITY_CLOUD_LEAK". That string
  occurs exactly ONCE in the entire repo: on that grep line itself. The gate is a tautology
  wired to a green README badge.
- Its own aspect ALLOWS MEDIUM-tier data to reach CLOUD models, contradicting its javadoc,
  and a test asserts that as correct. ai-gateway-client's ModelRoutingService implements the
  OPPOSITE policy. Two enforcement paths, opposite policies, both green.
- "100% local" reduces to trusting a litellm.yaml TEMPLATE (the real file doesn't exist in
  the repo) with commented-out Anthropic/OpenAI/Groq blocks. No Java code verifies the
  destination is local. ChatController.java:129 hardcodes header X-Privacy-Tier: "HIGH".

### M6. MODEL-MANAGER'S HEADLINE CAPABILITIES ARE STUBS
- "Detects your hardware and pulls optimal models automatically": ZERO hardware-detection
  code repo-wide (no nvidia-smi, no OperatingSystemMXBean, no OSHI, no vram/cuda).
  POST /v1/models/pull validates a string and returns 202. Nothing is downloaded.
- POST /v1/embeddings returns a hardcoded SINGLE-ELEMENT ZERO VECTOR [0.0] for every input,
  published in openapi/model-manager.yaml as "OpenAI-compatible embeddings".
- ModelHealthScheduler @Autowired(required=false) three adapters that are not dependencies
  => all null => every model reported unhealthy forever.
  GET /v1/models/{alias}/health NPEs (Map.of rejects the null lastHealthAt) => HTTP 500.
- LiteLLM config never binds (LITELLM_* maps to litellm.*, code reads trove.litellm.*)
  => weekly insights are never generated, GET /v1/insights always empty.

### M7. REPORT-SERVICE: EVERY REPORT IS SILENTLY EMPTY AND ARITHMETICALLY WRONG
- Three independent contract breaks vs ingestion (envelope shape, missing X-User-Id,
  date_from vs from) => zero rows, no error. Plus a non-JWT bearer token => 401 first.
- GST computed as amount x 0.05 on TAX-INCLUSIVE bank amounts. Correct is amount x 5/105.
  A 5% OVERSTATEMENT on every line of every tax export. A test asserts "10.00" (should be 9.52).
- ANNUAL_SUMMARY does not aggregate at all — one row per raw transaction with
  transaction_count hardcoded to literal 1. A test asserts the buggy behaviour.
- Presigned MinIO URLs are signed against internal Docker DNS (http://minio:9000), so
  every download fails from a browser. No public-endpoint override exists.
- The AI narrative posts to http://ai-gateway:8085 — no such service in any compose file,
  and port 8085 belongs to receipt-proc. Has never worked in any environment.
- The user's PRIVATE NOTES are exported under a column headed "description".

### M8. RECEIPT-PROCESSOR: OCR IS REAL CODE AGAINST A FICTIONAL API
- The PaddleOCR client makes a genuine multipart POST — but NO paddleocr service exists in
  ANY compose file. No image, no Dockerfile. The hostname appears once as a dangling env var.
- The wire protocol is INVENTED: the code expects {"results":[{"text","confidence","box"}]};
  real PaddleOCR hub-serving takes base64 `images` and returns nested results[][] with
  text_region as [x,y] arrays. The only thing that ever validated it is a WireMock stub the
  same author wrote to match.
- LINE ITEMS ARE NEVER PERSISTED in normal operation: score ceiling without a matched
  transaction is 0.35+0.45 = 0.80, below the 0.92 review threshold, and the method returns
  before persistLineItems(). Combined with the auth break, that is ALWAYS.
- US-only date parsing (MM/dd/yyyy) silently corrupts UK/CA receipts: 05/06/2026 becomes
  May 6, not June 5. And `if (upper.contains("$")) return "CAD"` stamps every US receipt CAD.

### M9. IMAP PLUGIN SHIPS THE USER'S ENTIRE INBOX TO AN LLM
bankSenderPattern is parsed into config and never applied to the fetch. The message fetcher
retrieves all messages; every body is spliced verbatim into an LLM prompt with no escaping.
For a product whose headline promise is privacy. (Mitigated only by the plugin being on no
service's classpath — M3.)

### M10. TESTS ARE REAL BUT HERMETIC — the systemic reason nothing was caught
1. EVERY service test injects authentication directly (@WithMockUser /
   SecurityMockMvcRequestPostProcessors.authentication), so no test ever exercises the real
   auth path. That is how a gateway that drops Authorization AND a service with no auth
   filter at all both shipped green.
2. EVERY WireMock stub encodes the CALLER'S IMAGINED CONTRACT, never the real provider's.
   The tests are self-fulfilling.
3. Several tests ASSERT THE BUG AS CORRECT: wrong GST, no aggregation, MEDIUM->cloud
   allowed, constant sourceId, params never transmitted.
4. Tests systematically avoid the broken path: every SimpleFIN fetchTransactions test passes
   accountId=null — the one input that bypasses the bug that breaks production.
5. The 80% JaCoCo gate is inside an opt-in <profile>, and much of the coverage it bought is
   over dead code.
6. The one suite that would have crossed a real boundary is 100% disabled.

### M11. OTHER CONFIRMED
- registry/ is 158 lines of inert JSON (8 Canadian merchants, 6 models), referenced by ZERO
  code, untouched since the scaffold commit. README calls it "Community model and merchant
  databases".
- Production log redaction is OFF: %redact appears only in the local/test console pattern;
  the production LogstashEncoder never invokes it. `Redact`, self-described as "the primary
  defense against secret leakage in logs", has ZERO production call sites.
- libs/common ships no auto-configuration; all 10 services hand-duplicate the @Import list.
  JacksonConfig (SNAKE_CASE) is imported by nobody but picked up by api-gateway's repo-wide
  component scan => SPLIT PUBLIC API CONTRACT: gateway emits `field_errors`, 7 services emit
  `fieldErrors`.
- JwtAuthFilter is DOUBLE-REGISTERED in 7 of 9 services (@Component + addFilterBefore, with
  no disabling FilterRegistrationBean) => every JWT verified twice per request.
- AccessDeniedException -> HTTP 500 (not 403) platform-wide.
- No iss/aud/clock-skew validation => a token minted for one service is valid at all ten.
- No HTTP timeout on ANY AI call; `timeout-seconds` is defined in 5 modules and applied in 1.
- AI audit logging is 100% broken: sender uses trove.ai.service-token, receiver expects
  trove.internal.service-token, and all 4 compose files set them to DIFFERENT VALUES.

## N. DOCUMENTATION ACCURACY (final wave-1 agent)
137 falsifiable claims extracted across docs/, docs-site/, CONTRIBUTING.md:
  48 FALSE | 27 PARTIALLY TRUE | 58 TRUE | 4 UNVERIFIABLE

### N1. WHOLE DOCUMENTS THAT ARE FICTION
- docs-site/self-hosting/kubernetes.md (90 lines) — 100% FICTIONAL. Quotes YAML from
  infra/k8s/ and infra/kind-config.yaml and gives kubectl/kind commands. NEITHER EXISTS.
  Every command on the page fails immediately. index.md:18 inherits the lie.
- docs-site/guide/architecture.md "Data Flow" steps 4-6 — INVENTED. Step 4 (ingestion calls
  categorisation synchronously) — ingestion has NO HTTP client to categorisation. Step 5
  (budget-svc emits an event) — no publisher, no client, NO BROKER EXISTS IN THE REPO.
  Step 6 (notification-svc consumes it) — nothing to consume.
- docs-site/self-hosting/authentik-setup.md steps 3-4 — following the page CANNOT produce a
  working login. Wrong provider name (trove-oidc vs the script's trove-gateway) and a
  redirect URI that is not a real route => redirect_uri_mismatch.

### N2. THE AI COACH HAS FOUR MUTUALLY INCONSISTENT REPRESENTATIONS
  web SPA calls      /api/coach/stream | /api/coach/sessions | /api/coach/model-routing
  gateway maps only  /api/v1/**            => /api/coach/* matches NOTHING
  PREFIX_TO_SERVICE  has no `coach` entry  => even /api/v1/coach/* would 404
  rate-limit seed    V4 seeds a row for /api/v1/coach/stream
  actual routes      /v1/chat/completions | /v1/chat/history | /v1/models/routing
  docs               document /api/v1/coach/*
Four representations, none agreeing — on top of model-manager 401ing everything (M1).

### N3. OPENAPI SPECS ARE UNREACHABLE AT RUNTIME ON 9 OF 10 SERVICES
docs claim "every service publishes /v3/api-docs and Swagger UI". The shared permitAll list
does NOT include /v3/api-docs or /swagger-ui => .anyRequest().authenticated() 401s them.
Only the gateway permits them. The contracts cannot be fetched from the services that
generate them.

### N4. A FOURTH NAMING REGIME
/api/v1/auth/token returns a BARE generated TokenResponse with @JsonProperty snake_case
(access_token, refresh_token, expires_in, token_type) and NO ApiResponse envelope — while
the rest of the API is camelCase and enveloped. Combined with the gateway's field_errors vs
7 services' fieldErrors (M11), the platform has FOUR inconsistent wire conventions.

### N5. ENVIRONMENT-VARIABLE DOCS ARE UNSALVAGEABLE — REGENERATE
  34 vars consumed by code/compose but MISSING from docs — including AUTHENTIK_CLIENT_ID and
     AUTHENTIK_CLIENT_SECRET, BOTH HARD-REQUIRED (the gateway will not start without them).
  15 documented but DEAD — incl. 7 *_POSTGRES_URL names no service reads, and
     TROVE_JWT_EXPIRY_SECONDS/_REFRESH_ which are HARDCODED in application.yml:55-56.
   7 with WRONG DEFAULTS (Docker-DNS hostnames documented where .env.example uses localhost).
  Also: "TROVE_JWT_SECRET: symmetric FALLBACK if RS256 key material is unset."
  THERE IS NO RS256 KEY MATERIAL. It is the only signing secret in the system.

### N6. DOMAIN TYPES DOCUMENTED WRONG (docs/ONBOARDING.md + glossary.md)
  EnvelopeHealth   docs: enum ON_TRACK/AT_RISK/OVER_BUDGET | real: a RECORD of 5 numeric fields
  DeduplicationResult docs: NEW/DUPLICATE/NEAR_DUPLICATE   | real: NEW/UPDATED/SKIPPED
  SyncLog statuses docs: STARTED->SUCCESS, SYNCED          | real: RUNNING/OK/PARTIAL_FAILURE/ERROR
  BankSourcePlugin code sample WILL NOT COMPILE (wrong signature, missing 2 methods).
  Also referenced but nonexistent: spotless, -P docker profile, tools/ dir, DataSourcePlugin,
  PrivacyEngine.classify(). Grafana documented on :3000, actually :3001.

### N7. RS256 IS CLAIMED IN 5 PLACES, INCLUDING "validates using the gateway's PUBLIC KEY"
docs-site/api/index.md, guide/architecture.md (x3), authentik-setup.md, README.md:30,
environment-variables.md:15. The system is HS256 symmetric throughout (G4).
Notably ONBOARDING.md:284 and ADR-0002 get it RIGHT — the docs contradict each other.

### N8. EVERY docs-site/api/*.md PAYLOAD EXAMPLE IS WRONG
Auth body documented {oidcToken} — real field is id_token. Error envelope documented
{code,message,details:[{field,rule,value}]} — real ErrorDetail is
{type,title,status,detail,instance,fieldErrors}: EVERY FIELD NAME IS WRONG.

### N9. THE MOST ACCURATE DOCS IN THE REPO
docs-site/mobile/build.md (16/16 cells match eas.json) and docs/performance/slo-results.md —
and the latter is accurate precisely because it honestly admits it measured nothing.

### N10. THE DIAGNOSIS
The documentation is not stale — it describes a DIFFERENT, MORE FINISHED SYSTEM.
ADR docs were committed 2026-04-27 for code written 2026-04-23; the CodeTour anchors were
synthesised from Javadoc and never walked. The prose was generated from INTENT, NOT FROM CODE.
This is the same failure mode as the tests (M10): both describe the system that was meant to
exist. Treat every document in this repo as a requirements artefact, not a description.

## O. VERIFICATION CORRECTIONS (verified directly; these supersede earlier text)

### O1. PRIVACY-ENGINE — precise version (supersedes M5's "6 of 8 dead" AND the
###     staff-engineer claim that the aspect is "live in every service")
VERIFIED:
- PrivacyAutoConfiguration IS registered at
  libs/privacy-engine/src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports
  and DOES declare PrivacyEnforcementAspect as an @Bean. So it is NOT dead in the
  Spring sense — Spring instantiates it.
- BUT no service declares privacy-engine directly. It arrives ONLY transitively via
  libs/ai-gateway-client, so it is on the classpath of exactly THREE services:
  categorisation-service, model-manager, receipt-processor. NOT all 10.
- AND the aspect's pointcut is @Around("@annotation(finance.trove.privacy.PrivacyEnforced)"),
  while @PrivacyEnforced has ZERO usages anywhere outside the library itself.
=> THE ASPECT IS A LIVE SPRING BEAN THAT ADVISES NOTHING. It never evaluates a policy on
   any real call path.
=> The "two contradictory ENFORCEMENT paths both running" framing is wrong in practice:
   only ai-gateway-client's ModelRoutingService actually executes. The privacy-theatre
   conclusion STANDS and is arguably strengthened — the enforcement machinery is fully
   wired, auto-configured, bean-registered, and connected to nothing.
- PrivacyTier and PrivacyViolationException ARE live (imported by ai-gateway-client,
  categorisation, receipt-processor, model-manager, model-adapter-api). Do not delete.
- There are TWO distinct PrivacyViolationException classes with the same simple name:
  finance.trove.privacy.* and finance.trove.ai.gateway.*. Consolidate.

### O2. THE HIGHEST-LEVERAGE SINGLE FILE IN THE REPO
libs/common ships NO META-INF/spring auto-configuration (verified: no .imports file),
which is why all 10 services hand-duplicate a 5-class @Import list — and why
model-manager, which wrote its own config instead, is the one service with no auth (M1).
The DEAD module (privacy-engine) ships the correct auto-configuration pattern.
=> Copy that pattern into libs/common BEFORE deleting privacy-engine. One file
   structurally prevents M1 from ever recurring.

### O3. VERIFIED PROJECT FACTS (supersede looser earlier statements)
- Human development window: 2026-04-22 -> 2026-05-19 = 27 DAYS (not ~7 weeks).
  ~45,600 production LOC across 10 languages => ~1,690 LOC/day sustained.
  This strongly implies heavy LLM generation, which EXPLAINS the failure mode exactly:
  per-file quality is high (one file fits in context); every cross-component seam is
  broken (no seam ever did).
- ALL TEN service ports are published to the host in docker/docker-compose.yml
  (8080-8089, plus Mailhog 8025). So the gateway is NOT a security boundary, and the
  G1 IDOR is directly exploitable from the LAN by setting X-User-Id.
- receipt-processor genuinely does NOT compile on a clean tree (verified twice, incl. an
  authoritative online rebuild producing no JAR). Earlier "it builds fine" reports were
  artifacts of stale target/classes under Maven incremental compilation.
  ALWAYS use `clean` when assessing this repo.

### O4. NEW (from architect): BROKEN HOSTNAME DEFAULT
report-service application.yml:42 and subscription-detector application.yml:40 default to
http://ingestion-service:8081 — a hostname defined in NO compose file (the container is
named `ingestion-svc`). Only infra/full-stack/ overrides it; docker/docker-compose.yml —
the one the README points at — does not. Both services fail DNS there.
Corroborates D2/M7 from a second direction.

### O5. NEW (from architect): GC / MEMORY MISCONFIGURATION
-XX:+UseZGC -XX:MaxRAMPercentage=75.0 with NO mem_limit anywhere in the maintained stack
=> ten JVMs each sizing their heap to 75% of TOTAL HOST RAM. ~6.8GB resident before any
model loads, against a stated Raspberry Pi / NAS target.

### O6. NEW (from architect): "11 DATABASES" IS THE WORST OF BOTH WORLDS
It is ONE Postgres process with 11 catalogs. The design pays the full cost of
database-per-service (no cross-service joins, no FKs, no transactional consistency) and
receives NONE of the isolation benefit (one process, one failure domain, one backup unit).

### O7. M0 REFRAMED AS A MODELLING FAILURE, NOT A CODING BUG (architect)
`sourceId` has two coherent readings: "id OF the source" (a plugin name) and "id FROM the
source" (the provider's transaction id). The four normalizers hold the first reading;
ADR-0009, DeduplicationService and the UNIQUE INDEX all hold the second. The database was
correct throughout. The NAME was ambiguous, and nothing in the type system or the test
suite could reveal the disagreement because no test ever crossed that seam.
Rename to `providerTransactionId` as part of the fix so it cannot recur.
