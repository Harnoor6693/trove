# Inventory — CI, integration tests, specs, misc tooling

> **Historical record, 2026-09-03.** CI and test inventory of the pre-rebuild repository at `6a8b3cf` (branch `rebuild`, since deleted). Branch names and workflow descriptions are not current. `03-REMEDIATION-PLAN.md` is in the private archive and not published. Current state: `docs-site/guide/architecture.md`.

All paths repo-relative. Verified against source on 2026-09-03. Prior verified findings
referenced as A1-A12, M2, M10, N3, N7 come from `docs/recovery/01-FINDINGS.md` (section O
supersedes earlier sections); every one is re-verified against this checkout's actual source
below, not merely cited. Where this checkout's state has moved past what 01-FINDINGS.md
describes, that is called out explicitly.

## 0. Which commit this document describes — read this first

This checkout is on local branch **`rebuild`** (pushed to `origin/rebuild`), one commit ahead
of `main`:

```
main   = 9ad1b5f  (the audited baseline — "does not compile", A1-A12 as written)
rebuild= 6a8b3cf  "fix(build): restore a truthful build and test signal (Phase 0)" — on top
```

`6a8b3cf` is a P0 stabilization commit (see `docs/recovery/06-PROGRESS.md`) made by a parallel
session in this same recovery effort, **while this inventory was being written**. It already
fixes several of the exact CI defects this document was asked to catalogue. Every section below
states current-as-of-`6a8b3cf` fact first and labels anything that is still true only of `main`
as **[baseline/main only]**. A single annotated tag exists, `rescue-baseline-2026-09-03` on
`9ad1b5f` — not a semver release tag, does not match `release.yml`'s `v*.*.*` trigger.

**CI was unavailable during the rebuild.** Every workflow described below is therefore verified by reading
its YAML and cross-checking the commands it runs against local source/build behavior, not by
CI execution evidence — the same posture the original audit used for A1-A12. This is itself the top item in §12.

Nothing in `main`'s working set for `.github/`, `openapi/`, `apps/mobile/{android,ios}`,
`config/`, `registry/`, `docs-site/`, `.tours/`, `tests/integration/`, `tests/performance/`, or
`CONTRIBUTING.md` was touched by `6a8b3cf` **except**: `.github/dependabot.yml`,
`.gitleaks.toml`, deletion of `.github/workflows/{dependabot-auto-merge,privacy-gate}.yml`, and
deletion of the 7 native mobile bank-notification files. Everything else in this document (all
6 `.github/workflows/*.yml` proper, `tests/integration/*`, `tests/performance/*`, `openapi/*`,
`config/`, `registry/`, `docs-site/`, `.tours/`, `CONTRIBUTING.md`, `release.yml`) is identical
on both branches — confirmed with `git diff --stat 9ad1b5f 6a8b3cf`.

---

## 1. `.github/workflows/*.yml` — six workflows

| File | Trigger | Jobs |
|---|---|---|
| `ci.yml` | `push` to `main`/`develop`/`phase/*`; `pull_request` → `main`/`develop` | test-backend, test-web, test-mobile, build-images, integration-tests, performance-tests |
| `web-ci.yml` | `push`/`pull_request`, path-filtered to `apps/web/**` | build-and-test |
| `mobile-ci.yml` | `push`/`pull_request`, path-filtered to `apps/mobile/**` (PR has no workflow-file filter) | test, detox-ios, detox-android |
| `security.yml` | `push`/`pull_request` → `main`; weekly cron Mon 02:00 UTC | codeql, secret-scan, dependency-audit, owasp-dependency-check, trivy-scan, owasp-runtime-assertions |
| `release.yml` | `push` tags matching `v*.*.*` | verify-release, build-and-publish, create-github-release, eas-submit-ios, eas-submit-android, smoke-test |
| `docs.yml` | `push` to `main` path-filtered to `docs-site/**`; `workflow_dispatch` | build, deploy |

### 1.1 `ci.yml` (281 lines) — the main gate

**test-backend** (`ci.yml:15-96`): brings up 4 Postgres services (`postgres` on 5432,
`postgres-ingestion` on 5433, `postgres-categorisation` on 5434, `postgres-models` on 5435 — all
`pgvector/pgvector:pg16` or `postgres:16`) plus a `valkey/valkey:7-alpine`, then runs
`./mvnw verify checkstyle:check -Pintegration,coverage -B --no-transfer-progress` (:84). This is
the ONE command that activates both the `integration` profile (binds Failsafe to run every
`**/*IT.java` inside every service module — the Testcontainers-backed per-service ITs; see §6.3
for why this is a different, real suite from `tests/integration/`) and the `coverage` profile
(binds the JaCoCo 80% line-coverage `check` goal, root `pom.xml:524-554`). `actions/setup-java@v5`
caches `~/.m2` via `cache: maven` (:73-77) — the only caching in this job. Surefire/Checkstyle
results upload unconditionally (`if: always()`, :85-96).

**test-web** / **test-mobile** (:98-128): each has a "Check if \[app] exists" step
(`test -f apps/web/package.json …`) gating the actual `npm ci && npm test -- run` step. The app
directories obviously exist and always will post-collapse too, so this guard never does
anything — dead weight, not a "structurally can't fail" defect, just noise. Neither job passes a
`cache:` param to `actions/setup-node@v6` (:103-105, :119-121) — no npm cache, unlike
`web-ci.yml`/`mobile-ci.yml` below. `npm test -- run` for web resolves to `vitest run` (no
coverage, no `--coverage` flag) — a second, slightly different invocation of the same test suite
`web-ci.yml` already runs with `test:ci`. For mobile, `npm test -- run` is nonsensical for Jest
(`-- run` is a Vitest-ism copy-pasted into the mobile job); Jest ignores the unrecognized `run`
positional arg and just runs the default `jest --coverage` (`apps/mobile/package.json:8`, "test"
script) — harmless but confirms this job was templated from the web one without adaptation.

**build-images** (:130-192): `needs: [test-backend, test-web, test-mobile]`,
`if: push && (main || develop)` — never runs on PRs. Matrix of the 10 services (api-gateway
through report-service, :156-166); builds each JAR with `-DskipTests`, then
`docker/build-push-action@v7` to `ghcr.io/.../trove-<service>:latest`, multi-arch
(`linux/amd64,linux/arm64`), GHA layer cache (`cache-from/to: type=gha`, :190-191). All 10
`services/*/Dockerfile` paths it references exist on disk (verified).

**integration-tests** (:193-224, `name: "Integration Tests (All 10 Services)"`) — **the job named
in the task brief and in M2**. `needs: [build-images]`, same push-to-main/develop-only gate. It
`docker compose -f docker/docker-compose.integration.yml up -d --wait` (16 services: postgresql,
valkey, minio, mailhog, ollama, litellm-gateway, gateway, and the 9 downstream services — verified
by listing top-level compose keys), then `./mvnw verify -pl tests/integration`. Because all 6
classes in `tests/integration/` are `@Disabled` (§6), Failsafe finds zero enabled tests, exits 0,
and the job goes green having executed nothing end-to-end. **This is structurally unable to
fail**, exactly as A8-A10/M2 describe, and it is unaffected by `6a8b3cf` — still true on
`rebuild`.

**performance-tests** (:226-281): `needs: [integration-tests]`, `if: push to main` only (no
`develop`). Polls `/actuator/health` for up to 150s, then `./mvnw gatling:test -pl
tests/performance`. Unlike the vacuous integration-tests job, **this one is not theatre — every
Gatling scenario except the health check calls `POST /api/v1/auth/register` first
(`exitHereIfFailed`)**, which 404s (see §7); the run would fail loudly with near-0%
`successfulRequests`, tripping the `global.successfulRequests.percent.gte(99.0)` assertion
(`AllSloSimulation.scala:83`). Given `main` didn't compile until `6a8b3cf` and zero release tags
exist, there is no evidence this job has ever completed a real run; its actual behavior (hard
fail on first genuine attempt) is inferred from source, not observed.

### 1.2 `web-ci.yml` (51 lines)

Path-filtered to `apps/web/**`, triggers on `push` to *any* branch (no branch restriction) and
on PRs. Node **20** (`web-ci.yml:25`, vs. `ci.yml`'s Node 22 for the same app — an unexplained
version split between the two workflows that both test web). Steps: `npm ci` → `npm run lint`
→ `npm run test:ci` (`vitest run --coverage`) → `npm run build` (`tsc -p tsconfig.app.json &&
vite build` — this is where web's type-check actually happens; there is no separate `tsc --noEmit`
step). Proper npm cache via `cache-dependency-path: apps/web/package-lock.json` (:26-27).
Uploads `apps/web/coverage/` unconditionally. This workflow and `ci.yml`'s `test-web` job are
fully redundant with each other (both run lint-adjacent + test + build against the same app, on
different Node versions, with different flag sets) and neither is a superset of the other.

### 1.3 `mobile-ci.yml` (161 lines)

Path-filtered to `apps/mobile/**` on `push`; the `pull_request` trigger (:8-10) has **no** path
filter, so it fires on every PR touching anything in the repo, including the two expensive Detox
jobs (`macos-15` + Android-emulator-runner). `test` job (:12-58): `npm ci` → **`npm run
generate`** (§8 — this step always succeeds and always produces zero files) → `lint` → `prettier
--check` → `test:ci` (`jest --coverage --ci --reporters=default --reporters=jest-junit`) →
uploads coverage + `junit.xml` → `npm run test:eas-config` (validates `eas.json` profiles via a
small Jest file, `apps/mobile/e2e/eas-config.test.ts`). `detox-ios` (macOS runner, Xcode 26,
CocoaPods, `detox build`/`detox test`) and `detox-android` (KVM-enabled ubuntu runner, JDK 21,
`reactivecircus/android-emulator-runner@v2`, API 34) are both real, non-trivial E2E jobs against
the actual Expo app — genuinely more sophisticated than anything on the web side — but there is
no evidence either has ever passed, since `npm run generate` upstream of both silently produces
no API types (§8) and the app has never been proven to boot against a live backend end-to-end
(M2). Proper npm cache in all three jobs via `cache-dependency-path`.

### 1.4 `security.yml` (190 lines)

Six jobs, all `push`/`pull_request` → `main` plus a weekly cron.

- **codeql** — `./mvnw compile` then CodeQL `java` analysis, `security-and-quality` queries.
  Real, compiles the whole reactor; would have been red for 4 months while `main` didn't compile
  (A1), independent of whether CodeQL itself ran.
- **secret-scan** — `gitleaks/gitleaks-action@v2` with `GITLEAKS_CONFIG: .gitleaks.toml`
  (:59). On `main` (`9ad1b5f`) `.gitleaks.toml` has **only** an `[allowlist]` block, no
  `[extend] useDefault = true` and no `[[rules]]`; `GITLEAKS_CONFIG` *replaces* gitleaks' default
  ruleset rather than extending it, so **zero detection rules load and the scan can never find
  anything by construction** — A10, confirmed by inspecting the 9ad1b5f blob directly (no
  `[extend]` key present). **Fixed on `rebuild`** (`6a8b3cf`): `.gitleaks.toml` now opens with
  `[extend]\n  useDefault = true` (§3) — this job now actually scans. The allowlist's 13 regex
  entries are all clearly-labeled dev placeholders (`change-me-…`, `dev-*-token`, per-workflow
  `*-secret-minimum-32-chars` literals used as CI env-var fillers, and the 64-zero HMAC key) —
  reasonable suppressions, not secret-hiding.
- **dependency-audit** — `npm audit --audit-level=high` for both apps, each wrapped in
  `if [ -d apps/web ]` / `apps/mobile`; those directories are guaranteed to exist so the guard is
  inert, same pattern as `ci.yml`'s test-web/test-mobile existence checks.
- **owasp-dependency-check** — `org.owasp:dependency-check-maven:10.0.4:aggregate
  -DfailBuildOnCVSS=7 -DsuppressionFile=.owasp-suppressions.xml -DnvdApiKey=…` (:97-109). Real
  hard gate (fails the build on any CVSS ≥ 7 finding), correctly caches the NVD database
  (`actions/cache@v5` keyed on `hashFiles('.owasp-suppressions.xml')`, :90-96) so it isn't a
  multi-minute NVD re-download every run. `.owasp-suppressions.xml` (§4) is currently empty of
  actual suppressions (all-comment template) — so nothing is being hidden from this gate, but
  also nothing has been triaged; a first real run against 10 modules' worth of transitive
  dependencies (Spring Boot 4.1, Testcontainers 2.0.5, etc.) is likely to surface findings that
  need real suppression entries, not zero.
- **trivy-scan** — matrix over the same 10 services; `mvn package -DskipTests` once, then
  per-service `docker build` + `aquasecurity/trivy-action` with `exit-code: 1` on HIGH/CRITICAL.
  Real and would fail loudly on a genuinely vulnerable base image; not theatre.
- **owasp-runtime-assertions** (:165-190) — deliberately **does not** run
  `tests/security/*.sh` (6 scripts: a03-sql-injection, a05-security-headers, a07-brute-force,
  a08-webhook-hmac, a09-pii-in-logs, a10-ssrf), only `bash -n`-syntax-checks them. The job's own
  comment (:166-172) states why: the scripts assume a live auth bootstrap that doesn't exist yet
  ("Phase 26 C2 auth blocker … `/api/v1/auth/register` does not yet exist"), citing "review H3"
  — the same blocker `tests/integration`, `tests/performance`, and `scripts/smoke-test.sh` (§10)
  each independently document. This is the fourth independent place in the repo recording the
  identical missing-endpoint fact.

### 1.5 `release.yml` (234 lines) — see §10, never triggered (zero matching tags)

### 1.6 `docs.yml` (63 lines) — see §9.3

---

## 2. `.github/dependabot.yml`

Current content (post-`6a8b3cf`; differs from `main`, both shown):

- 5 ecosystems: `maven` (root, all 25 Java modules), `npm` (`/apps/web`), `npm` (`/apps/mobile`),
  `docker` (`/apps/web` + `/services/*` glob), `github-actions` (root).
- **`main` (`9ad1b5f`)**: every ecosystem `interval: weekly` (`day: monday`/`tuesday`).
  **`rebuild` (`6a8b3cf`)**: every ecosystem changed to `interval: monthly`, `day:` keys removed
  — matches the target's "monthly grouped" requirement already.
- Grouping: `maven-minor-and-patch`; web and mobile each split into `*-prod-minor-and-patch` /
  `*-dev-minor-and-patch` by `dependency-type`; `docker-minor-and-patch`; `actions-all` (all
  actions bumps in one group, no minor/patch split).
- `ignore` blocks correctly exclude semver-major from every grouped ecosystem, plus named
  framework majors for web (react, react-dom, react-router-dom, vite, @vitejs/plugin-react,
  eslint, typescript).
- Mobile's `ignore` list (`dependabot.yml:73-85`) has a subtlety worth flagging for the target
  config: `react-native` and `expo` are listed **bare, with no `update-types` qualifier**
  (:74, :77) — this ignores **every** update to those two exact packages, not just majors,
  while the wildcard siblings `react-native-*` / `expo-*` (:75-76, :78-79) are only
  major-qualified. That means `react-native`/`expo` themselves never move (by design — SDK-pinned,
  bumped manually with `expo prebuild`, per the comment at :73), but their satellite packages
  (`react-native-reanimated`, `expo-*` modules, etc.) keep auto-bumping on minor/patch — the same
  shape of version-skew risk that caused A6 (`react-test-renderer` bumped independently of
  `react`). Worth a comment in the target config making the asymmetry explicit, since it's easy
  to misread as "all react-native-family packages are frozen."
- No `dependabot-auto-merge.yml` any more (deleted, §1's secret-scan write-up / §10) — Dependabot
  now only opens PRs; nothing merges them automatically. Branch protection / required status
  checks are unavailable on this repo's plan (private repo, free tier → 403 per
  `06-PROGRESS.md:32`), so this is the correct mitigation for A8, not a partial one.

---

## 3. `.gitleaks.toml`

27 lines. `main` (`9ad1b5f`): allowlist-only, no `[extend]` — **A10, zero rules load** (see
§1.4). `rebuild` (`6a8b3cf`) adds:

```toml
[extend]
  useDefault = true
```

with a comment explaining exactly why it's required. This is the entirety of the fix; nothing
else in the file changed. Once this is on `main`, the `secret-scan` job in `security.yml`
becomes a real gate for the first time in the project's history.

---

## 4. `.owasp-suppressions.xml`

22 lines, entirely comment/template — a documented format (`<notes>` mandatory) and a commented
example, but **zero live `<suppress>` entries**. Not a defect by itself (an empty suppression
file is the correct starting state), but combined with `-DfailBuildOnCVSS=7` in `security.yml`
(§1.4) it means the very first real run of `owasp-dependency-check` against this dependency set
will either pass clean or fail on whatever CVEs are outstanding — there is no triage history to
fall back on, and the target CI plan should budget time for a first-run triage pass rather than
assuming the gate is pre-tuned.

---

## 5. `.actrc`

```
--container-architecture linux/amd64
-P ubuntu-latest=ghcr.io/catthehacker/ubuntu:full-latest
```

Two lines, for running `.github/workflows/*.yml` locally via `nektos/act`. `full-latest` is the
large (~20GB) catthehacker image with Docker-in-Docker and most common toolchains preinstalled —
appropriate given `ci.yml` needs a live Docker daemon (Postgres/Valkey service containers,
`docker compose`, `docker buildx`) inside the runner. Not itself wired into any workflow or npm
script; it's a local-dev convenience file. Given the CI outage (§0), `act`
+ this file is presently the only way to exercise the actual workflow YAML at all (as opposed to
running the same shell commands directly, which is what local verification has been doing per
`06-PROGRESS.md`).

---

## 6. `tests/integration/` — the cross-service suite

```
tests/integration/pom.xml
tests/integration/src/test/java/integration/
  IntegrationTestBase.java
  OnboardingIT.java
  BudgetSyncIT.java
  ReceiptOcrIT.java
  NotificationRoundTripIT.java
  AiCoachIT.java
  TaxCalculationIT.java
tests/integration/src/test/resources/
  junit-platform.properties
  fixtures/{transactions.csv, dining_over_budget.csv, tax_year_transactions.csv, tesco_receipt.jpg}
```

### 6.1 Mechanics

`pom.xml` binds `maven-failsafe-plugin:3.5.6` to `integration-test`+`verify`, `includes:
**/*IT.java` (:44-59), and injects 8 `systemPropertyVariables` — one base URL per service
(`gateway.url` … `report.url`, ports 8080-8088) — read by `IntegrationTestBase`'s static fields
(`IntegrationTestBase.java:36-47`, defaults matching the same ports). Dependencies: REST Assured
5.5.7, JUnit Jupiter, Awaitility 4.3.0, Jackson. **No Testcontainers dependency anywhere in this
module** — it is pure HTTP-client-against-already-running-services, invoked only by `ci.yml`'s
`integration-tests` job after `docker compose -f docker-compose.integration.yml up`. This is a
structurally different suite from the ~27 files across 9 service modules
(`services/*/src/test/java/.../*IT.java`, e.g. `AuthControllerIT`, `CsrfIT`, `MigrationIT`) that
*do* use `org.testcontainers` and run inside each service's own `mvn verify -Pintegration` — those
per-service ITs run on every push/PR via `ci.yml`'s `test-backend` job and are real
(contribute to A3's "919 tests, 0 failures"). `tests/integration/` only runs on push-to-main/
develop, gated behind a successful `build-images`, and is the suite M2 describes.

### 6.2 `IntegrationTestBase.java` — the documented blocker

Class-level Javadoc (:16-33) states plainly: the gateway exposes only OIDC-mediated
`/api/v1/auth/token` + `/api/v1/auth/refresh` (delegating to Authentik); there is no
email/password registration endpoint. `registerAndGetToken(email, password)` (:93-113) POSTs
`{"email":…,"password":…}` to `/api/v1/auth/register`, asserts `201` and a non-blank
`data.token` — an endpoint that does not exist on the gateway. Every one of the 6 IT classes is
therefore `@Disabled("blocked on /api/v1/auth/register endpoint — see IntegrationTestBase +
review C2")`, verified individually (grep for `@Disabled` matched all 6 files, one hit each).

**Correction to how this should be read post-decision**: the class comment (and
`03-REMEDIATION-PLAN.md §"Unblocking /api/v1/auth/register"`) reasons about this from inside the
*old* architecture, where Authentik-only OIDC is fixed and a password-register endpoint was
rejected as "contradicts the architecture." **That premise no longer holds.** The owner's fixed
decision for the collapse is built-in local accounts (argon2id, optional TOTP) as the primary
auth path, with OIDC optional. A real `POST /api/v1/auth/register` (or equivalent) is therefore
in scope as production code, not a test-only shim — see §11.

### 6.3 What each IT actually asserts, and why every one needs more than an endpoint fix

All 6 were written against an **imagined API contract that was never real anywhere** (M2), not
just blocked by the missing endpoint:

| Class | Calls | Documented-vs-real mismatch found |
|---|---|---|
| `OnboardingIT` | `POST /api/ingestion/csv` → poll `/api/transactions` → `PATCH .../category` | Endpoint prefix style (`/api/...`) doesn't match the `X-User-Id`-header + `/v1/...` convention documented elsewhere in this recovery effort |
| `BudgetSyncIT` | creates a budget, ingests an over-budget CSV, polls for `status=OVER` + a `BUDGET_EXCEEDED` notification | Currency hardcoded `GBP` (target scope: CAD/USD only) |
| `NotificationRoundTripIT` | `POST /api/ingestion/mobile-notification` with a raw notification-text payload ("Channel 6"), expects a categorised transaction | **This whole channel is gone.** Native bank-notification listeners are cut per decision; this endpoint exists only to receive payloads a cut client would have sent |
| `ReceiptOcrIT` | uploads `tesco_receipt.jpg`, polls receipt status, asserts `status=="PROCESSED"`, `data.merchant`/`data.totalAmount`/`data.currency=="GBP"` | Confirms M2 exactly: real enum is `COMPLETE` not `PROCESSED`; real DTO fields are `extractedMerchant`/`extractedAmount`; real upload path is `/v1/receipts/upload` not `/api/receipts` |
| `AiCoachIT` | ingests transactions, `POST /api/coach/chat`, polls for `status=="COMPLETE"`, asserts response text mentions seeded merchants | Structurally closest to reusable — matches the "user-configured OpenAI-compatible endpoint" decision — but hardcoded to a compose-provisioned LiteLLM+Ollama pair via `assumeTrue(canReachLitellm())`, not a generic configured-endpoint check |
| `TaxCalculationIT` | posts UK Self-Assessment and US Schedule-C tax events, asserts computed `netProfit`/`grossIncome`, downloads and asserts PDF magic bytes | **The entire class is void under the tax-engine-cut decision** — it tests computed tax numbers, which will not exist. Not "fix the endpoint," delete outright and replace with an assertion that a CSV/PDF export of *categorised transactions* downloads and is well-formed, with no computed figures in it |

All 3 CSV fixtures and the JPEG fixture are **100% GBP / UK-merchant** (`TESCO STORES 3722`,
`NANDOS RESTAURANT`, `COSTA COFFEE`, UK tax-year date ranges Apr-Mar) — every fixture needs
replacing for a CAD/USD-only target, not just the assertions that read them.

`junit-platform.properties` sets a 120s default per-test-method timeout as a backstop above the
per-`await()` deadlines inside each test (5-60s) and REST Assured's own 5s/30s connect/socket
timeouts (`IntegrationTestBase.java:54-58) — sound defensive layering, worth keeping.

---

## 7. `tests/performance/` — **Gatling (Scala), not k6**

```
tests/performance/pom.xml
tests/performance/src/test/scala/trove/
  TroveSimulationBase.scala
  AllSloSimulation.scala          (entry point — gatling-maven-plugin default)
  TransactionListSimulation.scala
  DashboardSimulation.scala
  GatewayHealthSimulation.scala
  CategorisationSimulation.scala
  ReceiptsSimulation.scala
tests/performance/src/test/resources/logback-test.xml
```

`pom.xml` uses `gatling-maven-plugin:4.21.10` + `scala-maven-plugin:4.9.10` (Scala 2.13.15),
`gatling-charts-highcharts:3.15.1`; `simulationClass` defaults to `trove.AllSloSimulation`,
results to `target/gatling-results` — matches `ci.yml:255-263`'s
`./mvnw gatling:test -pl tests/performance` + artifact upload.

`TroveSimulationBase.scala` is the shared trait: reads `GATEWAY_URL` env (default
`localhost:8080`), a `require()` guard refusing to fire load at anything not obviously local
(`:38-45`, override via `TROVE_PERF_ALLOW_REMOTE=true`) — a genuinely good safety rail against
accidentally DoS-ing a real deployment. Its own header comment (:9-29) documents the identical
`/api/v1/auth/register`-doesn't-exist blocker as `IntegrationTestBase`, explicitly calling the
resulting SLO numbers "PENDING" in `docs/performance/slo-results.md` and instructing **not** to
work around it by touching the gateway's auth surface. `standardLoad()` (:80-84): ramp 0→50
concurrent users over 60s, hold 10 minutes — a serious, sustained load shape, not a smoke test.

`AllSloSimulation` composes 5 scenarios (transactions list, dashboard, health, categorisation,
receipts upload) with per-scenario injection profiles and 15 concrete SLO assertions (p95/p99
latency + success-rate thresholds per endpoint, plus one global 99% success-rate assertion,
`AllSloSimulation.scala:67-84`). 4 of the 5 non-health scenarios call `registerAndLogin` first
with `.exitHereIfFailed`. Because that call 404s, **every one of those 4 scenarios aborts on its
first request for every simulated user**, and the `global.successfulRequests.percent.gte(99.0)`
assertion fails hard — unlike `tests/integration`'s vacuous @Disabled pass, a real invocation of
this job fails loudly and immediately. The 5 individual per-scenario `.scala` files
(`TransactionListSimulation`, `DashboardSimulation`, `CategorisationSimulation`,
`ReceiptsSimulation`, `GatewayHealthSimulation`) are single-scenario variants of the same
scenarios for standalone runs; only `GatewayHealthSimulation` doesn't need `registerAndLogin`.

---

## 8. `openapi/*.yaml` and API-client generation — how the specs are actually produced

10 files: one per service (`api-gateway.yaml` through `tax-engine.yaml`).

### 8.1 Generation mechanism — verified, and more consequential than N3 states

9 of the 10 have a matching `services/<name>/src/test/java/.../OpenApiTest.java` (all services
**except** api-gateway — confirmed by listing, 9 files found). Representative
(`services/budget-service/src/test/java/finance/trove/budget/OpenApiTest.java:17-31`):

```java
class OpenApiTest extends BaseIT {
    @Test
    void openApiYaml_isAccessibleAndContainsPathsAndInfo() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs.yaml")...).andReturn()...;
        assertThat(body).contains("openapi:"); // + "paths:", "info:"
        Path yamlOut = Path.of("../../openapi/budget-service.yaml");
        Files.writeString(yamlOut, body);      // <-- overwrites the tracked repo file
    }
}
```

Because the class is named `OpenApiTest` (matches Surefire's `**/*Test.java` include, not
Failsafe's `**/*IT.java`), **this runs as an ordinary unit test on every `mvn test`/`mvn
verify`** for that service — including `ci.yml`'s `test-backend` job on every push and PR. Each
run regenerates the live springdoc spec from the running MockMvc context and **overwrites the
checked-in `openapi/<service>.yaml`** as a side effect of testing, not as an explicit "generate"
step. This is exactly the mechanism that produced the openapi diffs observed between `9ad1b5f`
and `6a8b3cf` (`openapi/budget-service.yaml`, `openapi/ingestion-service.yaml`,
`openapi/model-manager.yaml` all changed — field reordering, new endpoints, reformatted
descriptions — purely as a byproduct of the P0 test run, not a deliberate spec edit). Locally
this means `openapi/*.yaml` shows as modified after any `mvn test` even when no one meant to
touch it; in CI it's silent because the runner is thrown away.

**`openapi/api-gateway.yaml` is the one exception** — no `OpenApiTest` exists for api-gateway
despite the module depending on `springdoc-openapi-starter-webmvc-ui`
(`services/api-gateway/pom.xml:92-93`); its last touch (`git log`) was a manual/one-off commit
during the OIDC Authorization-Code + BFF-cookie rewrite. It is therefore the one spec of 10 that
does **not** self-heal against the running application and is the most likely of the 10 to have
silently drifted from the gateway's real routes.

### 8.2 The clients don't consume any of this — verified for both apps

**`apps/web/scripts/generate-api-clients.js`** hardcodes two spec paths:

```js
{ spec: 'apps/gateway/src/main/resources/openapi.yaml', out: 'src/api/generated/gateway' },
{ spec: 'services/receipt-processor/src/main/resources/openapi.yaml', out: 'src/api/generated/receipts' },
```

Neither exists: `apps/gateway` is not a directory at all (the real module is
`services/api-gateway`), and no service has an `openapi.yaml` under `src/main/resources/`
anywhere (that's not where springdoc/OpenApiTest writes — they write to the repo-root
`openapi/` dir, §8.1). Each iteration hits `existsSync` → `false` → prints a `console.warn` and
skips. `npm run generate` for web therefore does nothing on every invocation, silently, exit 0.
**It also is not called from any CI workflow** — grep across `.github/workflows/` for
`generate`/`openapi-typescript` inside the web job returns nothing; `web-ci.yml` and `ci.yml`'s
`test-web` never run it. Consistent with this: `apps/web/src/api/generated/` does not exist, and
every file actually in `apps/web/src/api/` (`accounts.ts`, `budgets.ts`, `categories.ts`,
`dashboard.ts`, `preferences.ts`, `transactions.ts`, `axiosInstance.ts`, `_envelope.ts`,
`types.ts`) is hand-written, not generated.

**`apps/mobile/scripts/generate-api-clients.js`** takes a different, also-broken approach: it
scans `services/*/src/main/resources/openapi/` (a **directory**, plural specs) and generates a
client per file found via `openapi-typescript-codegen`. **No service has that directory** —
`find services -path '*/src/main/resources/openapi*' -type d` returns nothing. The `services`
array Node builds from `readdirSync(...).filter(existsSync(specDir))` is therefore always empty;
the `for` loop body never executes; the script prints `"API client generation complete."` having
generated zero files. Unlike web's script, **this one is actually invoked in CI** —
`mobile-ci.yml:32-33` (`test` job) and again in both `detox-ios`/`detox-android` jobs before
`expo prebuild` — as a real, always-run step that always "succeeds" while doing nothing. Same
conclusion as web: `apps/mobile/src/api/` (`axiosInstance.ts`, `types.ts`) is hand-written.

**Net finding for §11**: "which specs the clients consume" is a trick question on the current
codebase — the answer is neither app consumes any spec at all; both `generate` scripts are
pointed at paths nothing produces, and web's isn't even wired into CI. This is new ground not
previously documented in `01-FINDINGS.md` (N3 only says specs are unreachable at *runtime* on 9
of 10 services; it doesn't address that the generator scripts are independently dead/misrouted).

---

## 9. `config/`, `registry/`

**`config/checkstyle/checkstyle.xml`** (43 lines) — the ruleset behind A4's "0 violations, 25
modules, hard gate." It is intentionally small: import hygiene (unused/illegal/redundant),
naming conventions (type/constant/local/member/method/package/parameter/static), a handful of
correctness checks (empty block, equals/hashCode, missing-switch-default, boolean
simplification, string-literal-equality), modifier order, basic whitespace, newline-at-EOF, and
an 800-line file-length cap. **No line-length limit, no Javadoc requirement, no cyclomatic-
complexity/method-length check, no import-order enforcement.** "0 violations" is real but a
low bar — worth knowing before treating checkstyle-green as a strong quality signal for the
merged module.

**`config/checkstyle/suppressions.xml`** — 2 rules, both suppress everything under
`target/generated-sources` / `target/generated-test-sources`. Fine, standard.

**`config/litellm.yaml.template`** — model-manager's LiteLLM routing config: a 5-model local
Ollama tier (`trove-fast`/`smart`/`vision`/`reason`/`embed`) always present, commented-out cloud
tiers (Anthropic/OpenAI/Groq) to uncomment + supply an API key for. Under the "user-configured
OpenAI-compatible endpoint, off until configured" decision, this whole multi-tier LiteLLM router
concept is more machinery than the target needs (one user-supplied base URL + key, not a 5-model
local fleet with latency-based routing/fallbacks) — flag for the AI-integration design, not a CI
concern per se.

**`registry/models-registry.json`** (model catalogue metadata: id, adapter, tier, VRAM/RAM
minimums, tasks, privacy level, license) and **`registry/merchant-db.json`** (merchant
canonicalization: id, canonical name, category, aliases, **country** — e.g. `tim-hortons` /
`CA`, already Canada-flavored, unlike the GBP test fixtures). Grepping the entire repo
(excluding `target/`) for either filename finds **zero** references outside `CONTRIBUTING.md`'s
"AI model registry entries" contribution workflow — **no Java code loads either file**. Both are
orphaned data, same pattern as the rest of the codebase's finished-but-unwired components (per
the recovery effort's one-line diagnosis). `merchant-db.json`'s CA-tagged entries are worth
preserving/extending when categorisation gets wired for real; `models-registry.json`'s
multi-tier local-model framing is, again, more than the "one configured endpoint" decision needs.

---

## 10. `docs-site/`, `.tours/`, `CONTRIBUTING.md`, `release.yml`

### 9.3/10.1 `docs-site/` — VitePress → GitHub Pages

`docs-site/package.json`: `vitepress ^1.6.4`, scripts `docs:dev`/`docs:build`/`docs:preview`
(all just `vitepress <cmd> .`). `docs.yml` builds with Node 20 + npm cache
(`cache-dependency-path: docs-site/package-lock.json`), `npm run docs:build`, then
`actions/configure-pages` + `upload-pages-artifact` + `deploy-pages` to GitHub Pages
(permissions `pages: write`, `id-token: write`; `concurrency: group: pages`). Triggers on push
to `main` path-filtered to `docs-site/**`, plus manual `workflow_dispatch`. The **mechanism**
here is sound (A7: "docs-site: GREEN") — it's the **content** that's fiction per
the doc-accuracy audit (private project notes) (48/137 claims false), including two self-hosting guides for dependencies
this collapse drops entirely: `docs-site/self-hosting/minio-setup.md` and
`docs-site/self-hosting/authentik-setup.md`. Not re-litigating content accuracy here (that's
the doc-accuracy audit (private project notes)'s job) — just confirming the build/deploy pipeline itself works and needs no CI
fixes, only a full content pass once the collapse's real architecture exists to document.

### 10.2 `.tours/` — 6 CodeTour files, all describe the pre-collapse topology

`ai-coaching-flow` (9 steps), `api-request-path` (9), `architect-services` (16),
`ingestion-sync-flow` (10), `new-joiner-overview` (12), `security-trust-boundary` (10) — 66
steps total, every `"file"` entry a `services/<name>/...` or `libs/.../...` path (verified
sample: `ingestion-sync-flow.tour.json` steps through `SyncController`, `SyncOrchestrator`,
`FieldEncryptionService`, `PluginRegistry`, `BankSourcePlugin`, `DeduplicationService`,
`ConnectedAccount`, all under `services/ingestion-service/...` / `libs/common/...`). None
reference the mobile bank-notification files already deleted in `6a8b3cf`, but every tour's
file paths break the moment the collapse moves `services/*` content into `trove-core` packages —
these need a wholesale path rewrite as a late step of the collapse (or deletion + a fresh set
authored against the merged package layout), not a per-file patch.

### 10.3 `CONTRIBUTING.md` — commands that do not execute, verified one by one

| Command (line) | Verdict |
|---|---|
| `docker compose -f infra/docker-compose.infra.yml up -d` (:10) | **Works** — file exists |
| `./mvnw spring-boot:run` (:11) | **Fails.** Root `pom.xml` packaging is `pom` (aggregator, no main class); `spring-boot-maven-plugin` is only declared inside root `<pluginManagement>` (:389-401, template for children to opt into), never bound in root's own `<build><plugins>`. No single service is "the app" to run this way pre-collapse |
| `./mvnw -pl tools/bench exec:java` (:20, "AI model registry entry" row) | **Fails immediately** — `tools/` does not exist anywhere in the repo (`find tools` → no such directory). Not a stale path from a rename; there is no bench tool at all |
| `./mvnw -pl plugins/bank-sources test` (:75, "Bank source plugins" step 4) | **Fails** — `plugins/bank-sources/` is a plain directory with a `README.md`, no `pom.xml`, and is not itself declared as a module in root `pom.xml`; only its 4 children (`simplefin`, `ofx`, `imap`, `bank-emails`) are real Maven modules. `mvn -pl plugins/bank-sources` errors "could not find project in reactor" |
| `./mvnw -pl libs/privacy-engine test` (:65, PR checklist "Privacy gate passes") | **Works** — real module, declared in root `pom.xml` |
| `./mvnw -pl plugins/bank-sources/simplefin test` (implied correct form) | Would work, but isn't what the doc says |

Post-collapse, most of this document's process (bank-plugin PRs, the tax-rule-update row, the
multi-module `-pl` invocations) needs rewriting for a single-module reactor regardless of the
above bugs — flagging the specific broken commands mainly so the collapse's replacement
CONTRIBUTING.md doesn't inherit them verbatim.

### 10.4 `release.yml` — never run, and what it assumes

Zero semver tags exist (`git tag -l` → only `rescue-baseline-2026-09-03`, which doesn't match
`v*.*.*`), so **A9 stands**: this workflow has never fired, on either branch. Structurally (§1.5)
it's a real 6-job pipeline — semver-tag validation → compile → 10-image matrix build+push
(tagged both `:latest` and `:${{ github.ref_name }}`) → GitHub Release with generated notes →
conditional EAS submission to TestFlight/Play (stable tags only, needs `EXPO_TOKEN`, Apple/Google
secrets) → `scripts/smoke-test.sh`. The matrix still includes `tax-engine` (:56-66) — dead weight
once the tax engine is cut. `smoke-test.sh` (verified, 136 lines) brings up
`docker/docker-compose.release.yml` with a freshly-generated `.env.smoke` (weak, ephemeral,
clearly labeled "SMOKE-ONLY — never copy into staging/production," :29-33) and gates its own
registration-flow assertion behind `SMOKE_REGISTER=1` — off by default, with a comment citing
the same "Phase 26 C2 blocker" as everywhere else (:4-6). This is now the **fourth** independent
place in the repo (integration tests, perf tests, security.yml's runtime-assertions job, release
smoke test) that has separately worked around the missing register endpoint rather than any one
of them owning the fix — strong signal that shipping that endpoint for real unblocks four dormant
test surfaces at once, not one.

**`scripts/bootstrap-user.sh`** (304 lines) is the only thing in the repo resembling "register
admin via bootstrap" and it is entirely Authentik-shaped: interactive prompts for
email/password (defaults `test@yopmail.com` / `admin` — the same credentials
`apps/web/e2e/onboarding-to-today.spec.ts` logs in with), brings up
`infra/docker-compose.infra.yml` (postgres, valkey, **minio**, **authentik**, …), polls
Authentik's bootstrap token, then drives the **Authentik REST API** to create an OAuth2
provider + application + redirect URI (`:119-268`). None of this survives the
Authentik-dropped/MinIO-dropped decisions, and none of it is non-interactive, so it cannot be
lifted into CI as-is — §11's "register admin via bootstrap" needs new, non-interactive bootstrap
logic against local accounts, not an adaptation of this script.

**`apps/web/e2e/`** (Playwright, `playwright.config.ts:4` → `testDir: "./e2e"`) already has 3
real specs — **not wired into any CI workflow** (grep for `playwright`/`test:e2e` across
`.github/workflows/` → zero hits):
- `onboarding-to-today.spec.ts` (63 lines) — login → onboarding wizard (deployment mode → AI
  mode → SimpleFIN connect → first budget) → lands on Today. Closest existing thing to the
  target E2E job's shape, but depends on a **live external network call** to
  `beta-bridge.simplefin.org` to scrape a demo setup token (:26-38) — too flaky for CI as
  written; the target job should swap this step for CSV import per the task brief, not SimpleFIN.
  Targets `https://trove.localhost`, implying Traefik TLS termination.
- `auth-flow.spec.ts` (18 lines) — 100% Authentik-shaped (`auth.trove.localhost` redirect,
  `akadmin@…` login, gated on an `AKADMIN_PASSWORD` env var). Dead under the local-accounts
  decision; delete or fully rewrite, don't patch.
- `smoke.spec.ts` (35 lines) — generic route-loads/no-console-errors sweep over 6 routes. Fully
  reusable as-is post-collapse.

Separately, `apps/web/tests/` (23 files under `ui/`, `responsive/`, `pages/`, `helpers/`) sits
**outside** `playwright.config.ts`'s `testDir` and is referenced by no npm script or workflow —
confirmed orphaned, matching the remediation plan (private project notes)'s "22 orphaned Playwright files" finding.

---

## 11. TARGET CI for the monolith

Design goals: everything meaningful on **both** `pull_request` and `push to main`; every job a
real, current signal (no vacuous passes); reuse what already works instead of inventing new
tooling where the repo already has it (Playwright specs, Gatling harness, checkstyle/JaCoCo
config, gitleaks allowlist).

### 11.1 Jobs

| Job | Trigger | What it does |
|---|---|---|
| **backend** | PR + push/main | `./mvnw clean verify` (always `clean` — A1's lesson: incremental builds false-pass) against Testcontainers-managed Postgres — Docker is present on `ubuntu-latest` by default, no service-container YAML needed for a single-module build, unlike today's 4-Postgres matrix for 4 separate service databases. One database, one Flyway history. Include `checkstyle:check` (keep today's ruleset — real, if narrow) and JaCoCo (`coverage` profile promoted out of opt-in, per the remediation plan (private project notes)'s CI-gate recommendation, and re-based after dead code is deleted so the 80% denominator means something) |
| **web** | PR + push/main, path-filtered `apps/web/**` | one job, one Node version (pick 22, retire the 20/22 split between `ci.yml` and `web-ci.yml`): `npm ci` → `lint` → `tsc -p tsconfig.app.json --noEmit` (explicit, not folded invisibly into `build`) → `vitest run --coverage` → `vite build`. Fix or delete `generate-api-clients.js` before trusting it again (§8.2) — either point it at the real, single `openapi/trove-core.yaml` this module's own OpenApiTest now produces, or drop it and keep hand-written API clients as a deliberate choice |
| **mobile** | PR + push/main, path-filtered `apps/mobile/**`, PR trigger path-filtered too (fixes mobile-ci.yml's PR-fires-on-everything gap, §1.3) | `npm ci` → fix-or-drop `generate` (§8.2, same fix as web) → `tsc --noEmit` (not currently a discrete step anywhere) → `lint` → `jest --ci` → `expo-doctor` (not currently run anywhere — add it; it's the standard Expo config/dependency-health check and costs nothing). Keep Detox as a separate, slower job gated the same way it is today (still valuable, just shouldn't block the fast PR loop) |
| **secret-scan** | PR + push/main + weekly | Gitleaks with the now-fixed `.gitleaks.toml` (§3) — already real once `6a8b3cf`'s fix lands on `main` |
| **e2e** | push/main (PR-optional if runtime budget allows) | `docker compose up trove-core postgres` (replacing today's 16-service integration compose, §1.1) → non-interactive admin bootstrap (new: `scripts/bootstrap-user.sh` cannot be adapted, §10.4 — needs an env-var-driven `POST /api/v1/admin/bootstrap` or equivalent, run once against an empty database) → Playwright against the **built web image** (not `vite dev`), reusing `smoke.spec.ts` as-is and rewriting `onboarding-to-today.spec.ts`'s SimpleFIN step to a **CSV import** (drop the live external network dependency to `beta-bridge.simplefin.org`) → assert transactions read back. This single job replaces `tests/integration/`'s 6 @Disabled classes entirely; there is no more cross-service boundary to integration-test once it's one JVM — what needs testing is the one real seam that's left (browser ↔ trove-core ↔ Postgres), which is exactly what Playwright-against-the-built-image gives you. `TaxCalculationIT`'s replacement here is "download the CSV/PDF export and assert it contains categorised transactions with no computed tax fields," not a ported version of the old test |
| **dependabot** | schedule (not a CI job) | Keep `rebuild`'s monthly-grouped config (§2) as-is; **no auto-merge**, since branch protection / required status checks are unavailable on this plan (confirmed 403, `06-PROGRESS.md:32`) — someone merges by hand after reading the (now-real) CI result. Note the `react-native`/`expo` bare-ignore asymmetry (§2) when this config is next touched |
| **release** | push tags `v*.*.*` | One tagged `trove-core` image (+ compose bundle), not a 10-service matrix; drop `tax-engine` from anywhere it still appears; keep the semver-validation and GitHub-Release steps; keep EAS submission conditional on a stable tag; rebuild `smoke-test.sh` against the single-image compose and turn `SMOKE_REGISTER` on by default once the real register endpoint ships, rather than leaving it permanently gated off |

### 11.2 What can be reused vs. must be rebuilt

**Reuse directly**: gitleaks config + allowlist (§3, already fixed), checkstyle ruleset (§9,
real if narrow), JaCoCo 80% gate mechanics (just promote out of the opt-in profile),
`smoke.spec.ts` (§10.4, generic and already correct), the OWASP dependency-check job's NVD
caching pattern, `.actrc` for local iteration during the outage (§0/§5), Gatling harness
structure (`TroveSimulationBase`'s safety `require()` guard and load-shape helpers are worth
keeping even as scenario targets change to trove-core's actual routes).

**Must be rebuilt, not patched**: `tests/integration/`'s 6 classes (delete `TaxCalculationIT`
outright; rewrite the other 5 against real trove-core routes and CAD/USD fixtures, §6.3); both
`generate-api-clients.js` scripts (§8.2, currently pointed at paths nothing produces); the
`OpenApiTest`-per-service pattern collapses to one spec for one module — decide once whether that
spec still auto-writes into a tracked `openapi/trove-core.yaml` on every `mvn test` (§8.1's
side-effect-on-disk behavior is convenient locally, noisy in CI) or whether generation moves to
an explicit `mvn -Pgenerate-openapi` step instead; `bootstrap-user.sh` (§10.4, entirely
Authentik-API-shaped, not adaptable); `auth-flow.spec.ts` (§10.4, entirely Authentik-shaped);
every `.tours/*.tour.json` file path (§10.2); `CONTRIBUTING.md`'s multi-module `-pl` examples
(§10.3).

**Net new**: a real E2E job (nothing currently runs Playwright in CI at all, §10.4); an
admin-bootstrap mechanism suitable for non-interactive CI (nothing exists for local accounts
today, §10.4); `expo-doctor` in mobile CI (never run today); an explicit web `tsc --noEmit` step
(currently invisible inside `vite build`); Testcontainers-Postgres wiring for the single-module
backend build (today's per-service Testcontainers ITs already prove the pattern works, just not
yet at trove-core scale).

---

## 12. Risks

1. **CI was unavailable during the rebuild**, on any branch, for a reason unrelated to any YAML content
   (`06-PROGRESS.md`). This is not a code fix, and it blocks *validating* every other item in this document via
   real CI execution — everything here is verified by reading source and reasoning about what
   the commands do, the same posture the original A1-A12 audit used. Resolve this before
   trusting any "CI is green" signal on this repo again.
2. **The repository is being actively rewritten by a parallel process while this document was
   written** (§0) — `main` moved from `9ad1b5f` to a `rebuild` branch at `6a8b3cf` mid-session,
   already fixing 2 of the exact defects (A10 gitleaks, A8/A11 auto-merge+privacy-gate deletion)
   this inventory was asked to catalogue. Anyone consuming this document should re-diff against
   current `main`/`rebuild` before acting on any "still broken" claim, not assume this snapshot
   stays accurate.
3. **Four independent test/CI surfaces silently work around the same missing
   `/api/v1/auth/register` endpoint** (`tests/integration`, `tests/performance`, `security.yml`'s
   owasp-runtime-assertions job, `release.yml`'s smoke-test) rather than any one of them driving
   the fix. Under the local-accounts decision this is now buildable as real production code, not
   a test shim — shipping it unblocks all four at once and should be sequenced early in P2, not
   treated as four separate test-fixture problems.
4. **Both `generate-api-clients.js` scripts are dead** (§8.2) — web's isn't called from CI at
   all and points at nonexistent paths; mobile's runs in CI on every PR/push and "succeeds" while
   generating zero files. If the target design keeps hand-written API clients deliberately, fine
   — but as written today these scripts actively mislead anyone who assumes "generate" means
   generate, and mobile's silent-no-op status is currently invisible in CI logs (exit 0, no
   warning printed unlike web's `console.warn`).
5. **The one CI job that would genuinely fail hard right now (`performance-tests`) is gated
   behind two other jobs that themselves gate on push-to-main only**, so there is no evidence it
   has ever actually run to completion, pass or fail — its "PENDING" SLO numbers
   (`TroveSimulationBase.scala:27`) are not a stale-but-once-valid measurement, they are a
   measurement that has likely never been taken.
6. **`openapi/*.yaml` self-overwrites as a side effect of `mvn test`** for 9 of 10 services
   (§8.1) — anyone running the backend test suite locally will see these tracked files show as
   modified with no corresponding intentional edit, which is confusing and, in a monolith with
   one aggregate spec, needs a deliberate decision (auto-write on every test run, vs. an explicit
   generate step) rather than carrying the current per-service behavior forward by default.
7. **`main`'s checkstyle ruleset is real but narrow** (§9) — no complexity, length, or Javadoc
   checks. "Checkstyle: 0 violations" should not be read as a strong code-quality signal for the
   merged `trove-core` module without deciding whether to tighten the ruleset as part of the
   collapse.
8. **`.owasp-suppressions.xml` has no triage history** (§4) — the first real run against the
   full merged dependency tree (post-collapse, one large module instead of 25 small ones) may
   surface CVSS≥7 findings with no prior suppression decisions to lean on; budget time for this
   rather than assuming the gate is pre-tuned.
9. **Branch protection / required status checks are confirmed unavailable** on this repo's plan
   (private, free tier → HTTP 403, `06-PROGRESS.md:32`). The target CI plan's jobs are only as
   good as someone actually reading their result before merging — there is no platform
   enforcement backstop even with a fully truthful CI, which is exactly the gap A8 exploited
   once already (Dependabot auto-merge over red CI for 4 months). Removing auto-merge fixes the
   automated version of this gap; it does not fix the human one.
10. **Every `.tours/*.tour.json` file and most of `CONTRIBUTING.md`'s process rows describe the
    pre-collapse 10-service topology** (§10.2, §10.3) — low CI risk directly, but high risk of
    quietly misleading the next contributor (or the next recovery-effort session) who trusts a
    tour or a contribution-workflow row without re-verifying it against the post-collapse layout,
    the same failure mode the doc-accuracy audit (private project notes) already documents at the docs-site level.
