# Inventory: services/budget-service

> **Historical record, 2026-09-03.** Inventory of the pre-rebuild code, since removed. Current state: `docs-site/guide/architecture.md`.

Audited against commit `9ad1b5f` (main), 2026-09-03. Read-only audit; every claim below was
verified against source in this pass (not copied from 01-FINDINGS.md without re-checking).

## 1. Purpose and LOC

Owns envelope budgeting (`BudgetPeriod` + `Envelope`), savings goals (`SavingsGoal`), and
net-worth tracking (`Asset`, `Liability`, `NetWorthSnapshot`) for the authenticated user.
Also runs two schedulers: monthly envelope rollover and a daily net-worth snapshot.

```
$ find services/budget-service/src/main -name '*.java' | xargs wc -l | tail -1
    2531 total
$ find services/budget-service/src/test -name '*.java' | xargs wc -l | tail -1
    1682 total
```

## 2. Spring wiring

- **Application class**: `finance.trove.budget.TroveBudgetApplication` — plain
  `@SpringBootApplication` (`TroveBudgetApplication.java:17`), component-scans
  `finance.trove.budget.*` only (root package), so it does **not** pick up `libs/common`
  beans by classpath scanning — they must be `@Import`ed explicitly (and are).
- **`@Import`/`@Configuration`**: `BudgetSecurityConfig`
  (`config/BudgetSecurityConfig.java:29`) imports exactly 5 beans from `libs/common`:
  `SecurityConfig`, `JwtAuthFilter`, `JwtProperties`, `GlobalExceptionHandler`,
  `RequestIdMdcFilter`. No `JacksonConfig` import anywhere in this module (verified:
  `grep -rn JacksonConfig services/budget-service` → zero hits) — this service therefore
  serializes JSON with Jackson's **default camelCase** naming, not the SNAKE_CASE that
  `JacksonConfig` would apply (that class is only picked up by api-gateway's repo-wide
  component scan per 01-FINDINGS M11). Confirms the split-wire-convention finding from this
  service's side: budget-service emits camelCase (`totalIncome`, `categoryId`, …).
- **Security filter chain** (from `libs/common/.../SecurityConfig.java:59-91`, imported
  as-is, not overridden): CSRF disabled, STATELESS sessions, CSP/HSTS/frame-deny/referrer-
  policy/permissions-policy headers set. `permitAll` paths: `/test/**`, `/actuator/health`,
  `/actuator/prometheus`, `/api/v1/auth/**`, `/api/v1/health`, `/api/health` — none of these
  prefixes are used by budget-service's own routes (all real routes are under
  `/v1/budgets`, `/v1/goals`, `/v1/networth`), so **every actual budget-service endpoint
  requires a valid JWT**. Everything else falls to `.anyRequest().authenticated()`.
- **Identity source**: JWT `sub` claim only. `JwtAuthFilter`
  (`libs/common/.../JwtAuthFilter.java:96-108`) reads `Authorization: Bearer <token>`,
  verifies HS256 with `trove.jwt.secret`, parses `sub` as a UUID, and populates
  `SecurityContextHolder` with a `TroveUserDetails(userId, email, roles)` principal. Every
  controller method takes `@AuthenticationPrincipal TroveUserDetails user` and calls
  `user.getUserId()` (verified across all 26 endpoints in `BudgetController`,
  `GoalController`, `NetWorthController`). **This service reads NO `X-User-Id` header
  anywhere** (`grep -rn X-User-Id src/main/java` → zero hits) — it is one of the services
  that does the JWT-`sub` thing correctly. Because gateway finding B1 strips the
  `Authorization` header on proxied requests, this service is unreachable through the
  documented `:8080` entry point, but is directly correct if called with a real bearer
  token (e.g. hitting `:8084` directly, or after B1 is fixed).
- **Jackson naming strategy in effect**: default camelCase (no `JacksonConfig` import — see
  above).
- **`@EnableScheduling`**: yes, via `config/SchedulingConfig.java:19` (a dedicated
  `@Configuration` class, isolated so tests can exclude it). Two `@Scheduled` methods exist
  in this module (see §7); both are live because this class is present in the app context
  (D8 in 01-FINDINGS confirms budget-service is one of the 4 services where scheduling
  actually fires).
- **`@EnableAsync`**: not present. No `@Async` anywhere in `src/main` (verified by grep).

## 3. HTTP routes (26 total, no omissions)

All paths are relative to the service root (no `/api` prefix; the gateway would add
`/api/v1/**` externally). All require JWT (see §2). Response envelope is
`finance.trove.common.dto.ApiResponse<T>` unless noted.

| Method | Path | controller#method | Identity source | Request DTO | Response DTO | Notes |
|---|---|---|---|---|---|---|
| GET | `/v1/budgets` | `BudgetController#list` | JWT sub | — | `List<BudgetPeriod>` | Ordered by `month DESC` |
| POST | `/v1/budgets` | `BudgetController#create` | JWT sub | `CreateBudgetRequest` | `BudgetPeriod` | 201; 409 if `(user,month)` exists; month normalized to day 1 |
| GET | `/v1/budgets/current` | `BudgetController#getCurrent` | JWT sub | — | `BudgetPeriod` (raw `ResponseEntity`, not the generic envelope wrapper path) | 404 (as `ApiResponse.error`) if none for this calendar month; **this is the special-attention route** — see §12 |
| GET | `/v1/budgets/{id}` | `BudgetController#get` | JWT sub | — | `BudgetPeriod` | 404-not-403 ownership |
| PUT | `/v1/budgets/{id}` | `BudgetController#update` | JWT sub | `UpdateBudgetRequest` | `BudgetPeriod` | partial update: totalIncome, budgetType |
| GET | `/v1/budgets/{id}/envelopes` | `BudgetController#listEnvelopes` | JWT sub | — | `List<Envelope>` | |
| POST | `/v1/budgets/{id}/envelopes` | `BudgetController#createEnvelope` | JWT sub | `CreateEnvelopeRequest` | `Envelope` | 201; 422 if ZERO_BASED sum≠totalIncome |
| GET | `/v1/budgets/{id}/envelopes/{envelopeId}` | `BudgetController#getEnvelope` | JWT sub | — | `EnvelopeWithHealth` | health computed on read, never persisted |
| PUT | `/v1/budgets/{id}/envelopes/{envelopeId}` | `BudgetController#updateEnvelope` | JWT sub | `UpdateEnvelopeRequest` | `Envelope` | 422 if ZERO_BASED sum≠totalIncome |
| DELETE | `/v1/budgets/{id}/envelopes/{envelopeId}` | `BudgetController#deleteEnvelope` | JWT sub | — | 204 | |
| GET | `/v1/goals` | `GoalController#list` | JWT sub | — | `List<SavingsGoal>` | ordered `createdAt DESC` |
| POST | `/v1/goals` | `GoalController#create` | JWT sub | `CreateGoalRequest` | `SavingsGoal` | 201; currentAmount forced 0, isAchieved forced false |
| PUT | `/v1/goals/{id}` | `GoalController#update` | JWT sub | `UpdateGoalRequest` | `SavingsGoal` | auto-sets isAchieved when currentAmount≥targetAmount |
| DELETE | `/v1/goals/{id}` | `GoalController#delete` | JWT sub | — | 204 | |
| GET | `/v1/networth/series` | `NetWorthController#series` | JWT sub | `range` query param (`3M\|6M\|1Y\|ALL`, default `1Y`, `@Pattern`-validated) | `NetWorthSeriesResponse` | if no snapshots in range, takes one synchronously as a side effect of a GET |
| POST | `/v1/networth/snapshot` | `NetWorthController#snapshot` | JWT sub | — | `NetWorthSnapshot` | 201 |
| GET | `/v1/networth/assets` | `NetWorthController#listAssets` | JWT sub | — | `List<Asset>` | |
| POST | `/v1/networth/assets` | `NetWorthController#addAsset` | JWT sub | `CreateAssetRequest` | `Asset` | 201 |
| PUT | `/v1/networth/assets/{id}` | `NetWorthController#updateAsset` | JWT sub | `UpdateAssetRequest` | `Asset` | |
| DELETE | `/v1/networth/assets/{id}` | `NetWorthController#deleteAsset` | JWT sub | — | 204 | |
| POST | `/v1/networth/liabilities` | `NetWorthController#addLiability` | JWT sub | `CreateLiabilityRequest` | `Liability` | 201 |
| PUT | `/v1/networth/liabilities/{id}` | `NetWorthController#updateLiability` | JWT sub | `UpdateLiabilityRequest` | `Liability` | |
| DELETE | `/v1/networth/liabilities/{id}` | `NetWorthController#deleteLiability` | JWT sub | — | 204 | |

(That's 11 BudgetController + 4 GoalController + 11 NetWorthController = 26; matches the 13
distinct path templates × their methods in `openapi/budget-service.yaml`.)

No pagination anywhere (`list`, `listAssets`, `getAssets`, `getLiabilities` all return
unbounded `List<T>` with no `page`/`size`/`limit` params — a per-user unbounded-growth risk
at scale, not a defect for now). No filter params except `NetWorthController#series`'s
`range` enum.

## 4. Persistence

**Database**: `trove_budget` (application.yml `spring.datasource.url`:
`jdbc:postgresql://localhost:5432/trove_budget`, overridable via `DB_URL`).

| Entity | Table | Key columns | FKs / unique / indexes |
|---|---|---|---|
| `BudgetPeriod` | `budget_period` | `user_id`, `month` (normalized day=1), `total_income`, `currency` (CAD default), `budget_type` (CHECK FLEXIBLE/ROLLOVER/ZERO_BASED) | `UNIQUE(user_id, month)`; `INDEX(user_id, month DESC)` |
| `Envelope` | `envelope` | `user_id` (denormalized), `period_id`, `category_id`, `category_name`, `allocated`, `spent`, `currency`, `rollover_amount` | `period_id REFERENCES budget_period(id) ON DELETE CASCADE`; `UNIQUE(period_id, category_id)`; `INDEX(period_id)`; `INDEX(user_id)`; `INDEX(user_id, category_id)` |
| `SavingsGoal` | `savings_goal` | `user_id`, `name`, `target_amount`, `current_amount`, `deadline`, `currency`, `is_achieved` | `INDEX(user_id)`; no unique constraint |
| `NetWorthSnapshot` | `net_worth_snapshot` | `user_id`, `snapshot_date`, `total_assets`, `total_liabilities`, `net_worth` (Postgres `GENERATED ALWAYS AS (total_assets - total_liabilities) STORED`, `insertable=false, updatable=false` in Java), `currency` | `UNIQUE(user_id, snapshot_date)`; `INDEX(user_id, snapshot_date DESC)` |
| `Asset` | `asset` | `user_id`, `name`, `asset_type` (CHECK CASH/INVESTMENT/REAL_ESTATE/VEHICLE/OTHER), `value`, `currency`, `account_id` | `INDEX(user_id)`; no unique |
| `Liability` | `liability` | `user_id`, `name`, `liability_type` (CHECK MORTGAGE/CAR_LOAN/STUDENT_LOAN/CREDIT_CARD/OTHER), `balance`, `interest_rate`, `currency`, `account_id` | `INDEX(user_id)`; no unique |

Flyway migrations (all in `src/main/resources/db/migration/`, 6 total):
- `V1__create_budget_period.sql` — creates `budget_period` (+ `pgcrypto` extension for `gen_random_uuid()`).
- `V2__create_envelope.sql` — creates `envelope`, FK to `budget_period` cascade-delete.
- `V3__create_savings_goal.sql` — creates `savings_goal`.
- `V4__create_net_worth_snapshot.sql` — creates `net_worth_snapshot` with generated `net_worth` column.
- `V5__create_asset.sql` — creates `asset`.
- `V6__create_liability.sql` — creates `liability`.

No FK exists from `Envelope.category_id`, `Asset.account_id`, or `Liability.account_id` to
any other table — these are cross-service references (categorisation-service,
ingestion-service) held as bare UUIDs with no referential integrity, consistent with
per-service DB isolation (O6 in 01-FINDINGS).

Repositories with custom `@Query`:
- `BudgetRepository.findDistinctUserIdsByBudgetType(BudgetType)` — `SELECT DISTINCT b.userId FROM BudgetPeriod b WHERE b.budgetType = :budgetType` (`BudgetRepository.java:52`). Feeds the rollover scheduler.
- `EnvelopeRepository.sumAllocatedByPeriodId(UUID)` — `SELECT COALESCE(SUM(e.allocated),0) FROM Envelope e WHERE e.period.id = :periodId` (`EnvelopeRepository.java:41`). Used for ZERO_BASED invariant checks.
- `AssetRepository.sumValueByUserId(UUID)` — `SELECT COALESCE(SUM(a.value),0) FROM Asset a WHERE a.userId = :userId` (`AssetRepository.java:33`). **No currency grouping/filter** — see §11 F2.
- `AssetRepository.findDistinctUserIds()` — feeds the daily net-worth snapshot scheduler.
- `LiabilityRepository.sumBalanceByUserId(UUID)` — same shape/defect as above (`LiabilityRepository.java:32`).
- `LiabilityRepository.findDistinctUserIds()` — feeds the same scheduler.

## 5. Config (application.yml, single profile file — no `application-local.yml` etc. in this module)

| Key | Env var | Default | Secret? |
|---|---|---|---|
| `server.port` | — | `8084` | no |
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/trove_budget` | no |
| `spring.datasource.username` | `DB_USER` | `trove` | no (but is a credential) |
| `spring.datasource.password` | `DB_PASSWORD` | `trove` | **yes — weak literal default, no random generation** |
| `spring.jpa.hibernate.ddl-auto` | — | `validate` (hardcoded) | no |
| `spring.flyway.enabled` | — | `true` (hardcoded) | no |
| `spring.flyway.locations` | — | `classpath:db/migration` | no |
| `spring.flyway.baseline-on-migrate` | — | `false` (hardcoded) | no |
| `trove.jwt.secret` | `TROVE_JWT_SECRET` | **none — `${TROVE_JWT_SECRET}` with no `:-default`, startup fails if unset** | yes, but notably this module does *not* ship the `change-me-minimum-32-chars-in-production!!` fallback other services ship (G5); confirm the shared default isn't injected via a parent/compose env file when collapsing. |
| `trove.jwt.expiry-seconds` (bound in `JwtProperties`, not set in this yml) | — | `900` (class default) | no |
| `trove.jwt.refresh-expiry-seconds` (bound, not set here) | — | `86400` (class default) | no |
| `management.endpoints.web.exposure.include` | — | `health,info,prometheus,metrics` | no |
| `management.endpoint.health.show-details` | — | `when-authorized` | no |
| `management.metrics.export.prometheus.enabled` | — | `true` | no |

Only 1 `${VAR:-default}`-style placeholder pattern class exists here for a secret (DB
password, plaintext default `trove`); `TROVE_JWT_SECRET` has **no** default in this
module (good) — flag this specifically when merging: if trove-core's shared config
re-introduces a JWT secret default, it must not silently override this module's stricter
(no-default) behavior.

## 6. Outbound HTTP

**None.** No `RestClient`, `WebClient`, `RestTemplate`, or raw `HttpClient` usage anywhere
in `src/main` (verified by grep); no such dependency in `pom.xml`. This service is a pure
sink: it never calls another Trove service. This is structurally why `Envelope.spent` can
never be written — nothing in this codebase (nor any inbound call path) pushes a
transaction-categorization event into budget-service. `wiremock` is a test-scope-only
dependency and is unused by any test in this module (verified: `grep -rl WireMock
src/test` → no hits) — dead test dependency, can be dropped on collapse.

## 7. Scheduled / async / events

Two `@Scheduled` methods, both active in production (via `SchedulingConfig`'s
`@EnableScheduling`, confirmed present unlike api-gateway per 01-FINDINGS D8):

1. **`RolloverScheduler#processRollovers`** (`rollover/RolloverScheduler.java:31`), cron
   `0 0 1 1 * *` — fires 01:00 UTC on the 1st of each month. Enumerates
   `budgetRepository.findDistinctUserIdsByBudgetType(BudgetType.ROLLOVER)` — every user with
   at least one `ROLLOVER`-type `BudgetPeriod` (any month, not just current) — and calls
   `RolloverService#processUserRollover(userId, prevMonth, currMonth)` for each, in its own
   `REQUIRES_NEW` transaction so one user's failure doesn't block others.
2. **`NetWorthService#dailySnapshotAll`** (`networth/NetWorthService.java:145`), cron
   `0 30 23 * * *` — fires 23:30 UTC daily. Enumerates the union of
   `AssetRepository.findDistinctUserIds()` and `LiabilityRepository.findDistinctUserIds()`
   (any user who owns ≥1 asset or liability), calls
   `NetWorthSnapshotService#takeSnapshot(userId)` for each in its own `REQUIRES_NEW`
   transaction.

No `@Async`. No Spring application events (`ApplicationEventPublisher`) published or
consumed anywhere in this module.

## 8. External dependencies

- **Postgres** — `trove_budget` database, hard dependency (Flyway + JPA `ddl-auto:
  validate`, will not start against a missing/mismatched schema).
- Nothing else. No MinIO, no Redis, no Ollama, no PaddleOCR, no SMTP, no SimpleFIN, no
  Authentik, no LiteLLM, no inter-service HTTP (see §6). The only shared-library
  dependency surface is `libs/common` (security, exception handling, MDC, DTOs). This is
  the cleanest/simplest module in the platform to fold into trove-core.

## 9. Tests

- **Unit tests (Mockito, no Spring context)**: `BudgetServiceTest` (3 tests),
  `EnvelopeServiceTest` (5 tests), `EnvelopeHealthCalculatorTest` (pure function tests, not
  read in detail but present), `GoalServiceTest`, `NetWorthServiceTest` (mocked
  repositories throughout — every assertion on `sumValueByUserId`/`sumBalanceByUserId`
  behavior is against a **mocked** return value, so the real cross-currency-unsafe SQL in
  §11/F2 is never exercised by any test, unit or integration), `NetWorthSnapshotServiceTest`,
  `RolloverServiceTest` (5 tests, mocked repos, exercises the rollover math formula in
  isolation), `RolloverSchedulerTest` (3 tests, mocks `RolloverService` itself so the
  compounding-bug interaction with a real `spent=0` never surfaces here either).
- **Integration tests** (`BaseIT`, real Testcontainers Postgres `postgres:16-alpine`,
  real `MockMvc` + a genuine HS256 JWT minted by `JwtTestHelper` — **not** `@WithMockUser`,
  so these ITs are a better auth-path proof than the platform-wide pattern criticized in
  01-FINDINGS M10.1): `BudgetControllerIT` (6 tests), `NetWorthControllerIT` (6 tests),
  `OpenApiTest` (1 test — asserts `/v3/api-docs.yaml` is reachable *with* a bearer token
  and regenerates `openapi/budget-service.yaml` as a side effect of running; this
  contradicts 01-FINDINGS N3's claim that OpenAPI docs are unreachable service-side — for
  *this* service specifically `/v3/api-docs.yaml` is not on the `permitAll` list, so the
  test correctly attaches a bearer token and succeeds; N3 remains true for anyone trying
  the same URL *without* a token).
- No test in this module ever calls `envelopeRepository.save()` (or the HTTP layer) with a
  nonzero `spent` value coming from anything other than a hand-built test fixture — because
  there is no code path in this service (or any other, per §6) that would ever do so in
  production. **No test asserts the D1 bug as correct** — the tests are honest about the
  math they cover (rollover arithmetic given an assumed `spent`), they simply cannot see
  the missing writer because that writer would live in a different service (ingestion or
  categorisation), and no test — unit or integration — spans that seam. This is the general
  01-FINDINGS M10 pattern (hermetic tests, real boundary never crossed) applying here by
  omission rather than by a false assertion.
- Similarly, no test seeds mixed-currency assets/liabilities and asserts a specific (wrong)
  summed total — the currency-blind SQL aggregate (F2) is untested, not mis-tested.

## 10. Verified defects

| ID | Summary | file:line | Verified how |
|---|---|---|---|
| D1 (confirms 01-FINDINGS) | `Envelope.spent` is never written anywhere in this module or any inbound call path (no outbound/inbound integration exists, §6); `healthPercentage`/`isOverPace` in `EnvelopeHealthCalculator` are always computed against `spent=0`, and `RolloverService.processUserRollover` computes `unspent = allocated + rolloverAmount − spent` every month, so with `spent` pinned at 0 the **full allocation** rolls forward and compounds unbounded month over month for `ROLLOVER`-type budgets. | `envelope/Envelope.java:83` (field, never mutated outside `@Builder`/`@Setter`); `rollover/RolloverService.java:51-54` (the unbounded-compounding computation); confirmed zero call sites via `grep -rn "setSpent\|\.spent(" src/main/java` → no output | Read every `.java` file in `envelope/`, `rollover/`, `budget/`; grepped for any write to the field across the whole module; traced the rollover formula by hand against the entity default. |
| F2 (confirms 01-FINDINGS) | Net-worth aggregation sums `Asset.value` and `Liability.balance` across **all currencies indiscriminately** — no `GROUP BY currency`, no filter, no per-currency snapshot. A user with a CAD chequing account and a USD investment account gets a single blended "net worth" number treating 1 CAD == 1 USD. | `networth/AssetRepository.java:33` (`SELECT COALESCE(SUM(a.value), 0) FROM Asset a WHERE a.userId = :userId`); `networth/LiabilityRepository.java:32` (same shape); called from `networth/NetWorthSnapshotService.java:35-36` | Read the `@Query` annotations directly; confirmed no currency parameter/filter exists in either query or in the calling service; confirmed no test exercises mixed currencies against real aggregation (§9). |
| F1-budget (confirms/refines 01-FINDINGS F1) | Currency hardcoded to the literal `"CAD"` at **12 executable sites** in this module (6 entity `@Builder.Default` fields + 6 service-layer null-coalescing fallbacks) — not the "5 sites" figure in 01-FINDINGS' repo-wide summary, which appears to count distinct classes/files rather than every fallback line. | `budget/BudgetPeriod.java:72`, `budget/BudgetService.java:69`, `envelope/Envelope.java:83`, `envelope/EnvelopeService.java:65`, `goal/SavingsGoal.java:67`, `goal/GoalService.java:58`, `networth/Asset.java:34`, `networth/NetWorthService.java:84`, `networth/Liability.java:37`, `networth/NetWorthService.java:119`, `networth/NetWorthSnapshot.java:38`, `networth/NetWorthSnapshotService.java:45` | `grep -rn '"CAD"' src/main/java`, manually excluded javadoc/comment occurrences, counted only executable defaults/fallbacks. |
| BUD-01 (new) | Unbounded, unpaginated per-user lists on 4 endpoints (`GET /v1/budgets`, `GET /v1/goals`, `GET /v1/networth/assets`, implicitly `getLiabilities` which has no controller route at all — see BUD-02) — no `page`/`size`, no `LIMIT`. Not exploitable today (per-user data volumes are small) but will need pagination before trove-core ships if any user accumulates years of monthly budget periods. | `budget/BudgetController.java:52-53`; `goal/GoalController.java:44-46`; `networth/NetWorthController.java:76-79` | Read every list-returning controller method; none take pagination params; none of the backing repository methods are `Page<T>`-returning. |
| BUD-02 (new) | `NetWorthService.getLiabilities(UUID)` (`networth/NetWorthService.java:107`) is dead code — no controller method calls it. `NetWorthController` has no `GET /v1/networth/liabilities` route at all (verified against the full route table in §3 and the OpenAPI spec, 13 paths, none is a bare liabilities list). Liabilities can be created, updated, and deleted, but never listed via any endpoint. | `networth/NetWorthService.java:107`; absence confirmed against `networth/NetWorthController.java` (full file read, §3) | Read the full `NetWorthController.java`; cross-checked against `openapi/budget-service.yaml`'s path list (13 paths, no `/v1/networth/liabilities` GET). |

## 11. Collapse recommendation (per package in `src/main`)

Target root for kept code: `finance.trove.core.budget.<sub>` (adjust prefix to whatever
`trove-core`'s actual root package ends up being; sub-package names below are chosen to
avoid collision with other services' domain packages during the merge).

| Package | Action | Destination | Reason |
|---|---|---|---|
| `finance.trove.budget` (root — `TroveBudgetApplication`) | DELETE | — | Only `trove-core` keeps one `@SpringBootApplication`; this becomes dead once merged. |
| `finance.trove.budget.config` (`BudgetSecurityConfig`, `SchedulingConfig`) | DELETE (security config) / KEEP-MERGE (scheduling) | `BudgetSecurityConfig` → delete entirely, trove-core has ONE security config, not 10; `SchedulingConfig`'s `@EnableScheduling` → merge into trove-core's single root config (one `@EnableScheduling` for the whole monolith, not one per former service) | `BudgetSecurityConfig` is a byte-for-byte duplicate of the same 5-bean `@Import` pattern every service hand-rolls (O2 in 01-FINDINGS) — this is exactly the duplication the collapse eliminates. `SchedulingConfig`'s isolation pattern (separate class for test-exclusion) is worth preserving as a convention but only one instance is needed monolith-wide. |
| `finance.trove.budget.budget` (`BudgetController`, `BudgetPeriod`, `BudgetRepository`, `BudgetService`, `BudgetType`, `CreateBudgetRequest`, `UpdateBudgetRequest`) | KEEP | `finance.trove.core.budget.period` | Clean, self-contained, no duplication with other services; only change needed is the JWT `sub`→userId pattern already matches what other kept services use. |
| `finance.trove.budget.envelope` (`Envelope`, `EnvelopeHealth`, `EnvelopeHealthCalculator`, `EnvelopeRepository`, `EnvelopeService`, `CreateEnvelopeRequest`, `UpdateEnvelopeRequest`, `EnvelopeWithHealth`) | KEEP + REWRITE the writer gap | `finance.trove.core.budget.envelope` | Keep as-is, but this is the one package in the whole service that needs new code, not migrated code: something in the collapsed transaction-write path (categorisation) must call a new `EnvelopeService.recordSpend(userId, categoryId, periodId, amount)` (or equivalent) — that method does not exist today and is the actual fix for D1. Flag as the #1 cross-package wiring task the collapse must not skip. |
| `finance.trove.budget.goal` (`GoalController`, `GoalRepository`, `GoalService`, `SavingsGoal`, `CreateGoalRequest`, `UpdateGoalRequest`) | KEEP | `finance.trove.core.budget.goal` | Clean, self-contained; note 01-FINDINGS D9 says the gateway has no route for `/v1/goals` today — irrelevant post-collapse since there's no gateway proxy hop inside a monolith, but confirms this package was previously unreachable and thus effectively untested end-to-end in production. |
| `finance.trove.budget.networth` (`Asset`, `AssetRepository`, `CreateAssetRequest`, `UpdateAssetRequest`, `Liability`, `LiabilityRepository`, `CreateLiabilityRequest`, `UpdateLiabilityRequest`, `NetWorthController`, `NetWorthService`, `NetWorthSnapshot`, `NetWorthSnapshotRepository`, `NetWorthSnapshotService`, `NetWorthSeriesResponse`) | KEEP + REWRITE the aggregation query | `finance.trove.core.budget.networth` | Keep the CRUD and scheduling shape; REWRITE `sumValueByUserId`/`sumBalanceByUserId` (and the snapshot they feed) to aggregate per-currency (`GROUP BY currency`) rather than blindly summing — required by the owner's decision that CAD/USD are both first-class (F2 is not cosmetic once two currencies are actually live for the same user). Also add the missing `GET /v1/networth/liabilities` route (BUD-02) or delete `getLiabilities` if intentionally unused. |
| `finance.trove.budget.rollover` (`RolloverScheduler`, `RolloverService`) | KEEP, contingent on the envelope writer fix | `finance.trove.core.budget.rollover` | The math itself (`allocated + rolloverAmount − spent`, floored at 0) is correct; it only produces the D1 compounding bug because `spent` is always 0 upstream. No change needed here once `envelope.recordSpend` exists elsewhere — flag as **do not "fix" this package in isolation**, the fix belongs in the envelope-write path. |

**Classes duplicating `libs/common` or another service** (candidates the collapse should
collapse to ONE copy, not keep 10x): `JwtAuthFilter`, `SecurityConfig`, `JwtProperties`,
`GlobalExceptionHandler`, `RequestIdMdcFilter` — **all five already live in `libs/common`
and budget-service only `@Import`s them** (`BudgetSecurityConfig.java:29`); it does not
hand-roll its own copies. The only budget-service-local duplication risk is
`BudgetSecurityConfig` itself, which is a thin `@Import`-list wrapper repeated verbatim
(same 5 classes, same order) in every one of the other 9 services — confirmed pattern per
01-FINDINGS O2. When collapsing, delete all 10 `*SecurityConfig` wrapper classes and
replace with a single trove-core-wide import (or, better, adopt O2's recommendation:
promote the auto-configuration pattern already proven in the dead `privacy-engine` module
into `libs/common` itself so no service — old or new — needs a manual `@Import` list at
all).

## 12. Merge risks / gotchas

- **Bean-name clashes**: `BudgetSecurityConfig` and `SchedulingConfig` are simple-named
  `@Configuration` classes; at least `SecurityConfig`-named or `SchedulingConfig`-named
  classes likely exist in other services being merged (they follow the same naming
  convention per-service). Delete duplicates first (see §11) rather than relying on
  package-qualified bean names to save you — Spring bean names default to the unqualified
  class name for `@Configuration` classes registered via `@Import`, and MULTIPLE services'
  `SchedulingConfig` all resolve to bean name `schedulingConfig` — only ONE must survive
  the merge or context refresh will fail on duplicate bean definition.
- **Table-name clashes**: `budget_period`, `envelope`, `savings_goal`, `net_worth_snapshot`,
  `asset`, `liability` are all budget-service-specific names; skimmed the other services'
  migration filenames are out of scope for this report, but `asset`/`liability` are
  generic-enough nouns worth a repo-wide `grep -rn "CREATE TABLE asset\b\|CREATE TABLE
  liability\b"` before assuming no collision — not verified in this pass (out of scope:
  this report covers budget-service only).
- **Property-key clashes**: `trove.jwt.secret`, `trove.jwt.expiry-seconds`,
  `trove.jwt.refresh-expiry-seconds` are shared-namespace keys by design (bound once in
  `libs/common`'s `JwtProperties`) — safe, not a clash, but means budget-service's
  `application.yml` `trove.jwt.secret: ${TROVE_JWT_SECRET}` line (no default) must be the
  **one** value that wins when merging 10 services' `application.yml` files; at least one
  other service ships the weak `change-me-minimum-32-chars-in-production!!` default (G5) —
  make sure the merged `application.yml` keeps budget-service's stricter no-default
  behavior, not the weaker one.
- **Static state**: none found (`EnvelopeHealthCalculator` is a stateless static-method
  utility class with no static mutable fields; confirmed by reading the full file).
- **Hardcoded ports/hostnames**: only `server.port: 8084` (application.yml) — irrelevant
  post-collapse (single JVM, no per-service port), delete this key.
- **Hardcoded currency (CAD) sites**: 12 executable sites, fully enumerated in §10/F1-budget
  and §11 — the networth aggregation queries (F2) are the ones that actually break under the
  owner's CAD+USD-both-first-class decision; the entity/DTO defaults are comparatively
  low-risk (they only affect what a client gets when it omits `currency`, and the frontend
  already always sends an explicit currency per 01-FINDINGS F1's mobile/web notes) but
  should still be swapped for a per-user default resolved from the user's onboarding
  currency setting rather than a literal.
- **Anything that reads `X-User-Id`**: nothing in this module (confirmed §2) — budget-
  service is *not* one of the services implicated in G1 (the ingestion-service IDOR via
  client-controlled `X-User-Id`). Safe to merge without special IDOR remediation here,
  though the merged trove-core should still standardize on JWT-`sub`-only identity
  end-to-end and delete `X-User-Id` handling everywhere else, not introduce it here.
- **`/v1/budgets/current`** (special attention route): note it returns a **raw
  `ResponseEntity<ApiResponse<BudgetPeriod>>`** rather than the plain `ApiResponse<T>` every
  other endpoint returns (`BudgetController.java:87-97`) — the 404 branch hand-builds an
  `ApiResponse.error(...)` body rather than letting `GlobalExceptionHandler` handle a thrown
  `TroveException`. Functionally equivalent today, but it's the one endpoint in this module
  that doesn't follow the `orElseThrow(TroveException)` idiom used everywhere else — worth
  normalizing during the merge so error-handling stays uniform across trove-core.
