# Inventory: services/subscription-detector

Verified against source at commit `9ad1b5f` (main), 2026-09-03. All line numbers cited from
`cat -n`. Cross-referenced against `docs/recovery/01-FINDINGS.md` sections D2, D8, F1, O2,
O4 — every cross-reference below was re-verified directly against this module's source, not
copied from the dossier.

---

## 1. Purpose and LOC

Detects recurring "subscription" charges (Netflix, Spotify, etc.) from a user's transaction
history by grouping DEBIT transactions by normalized merchant name, classifying the interval
between charges into a billing cycle, and scoring confidence. Exposes read/cancel endpoints
and a price-change alert feed over the results; a nightly cron job (re)runs detection.

- LOC main: **1205** (`find services/subscription-detector/src/main -name '*.java' | xargs wc -l | tail -1`)
- LOC test: **924** (`find services/subscription-detector/src/test -name '*.java' | xargs wc -l | tail -1`)

## 2. Spring wiring

- **Application class:** `finance.trove.subscription.TroveSubscriptionDetectorApplication`
  (`src/main/java/finance/trove/subscription/TroveSubscriptionDetectorApplication.java:47`),
  plain `@SpringBootApplication`. Its Javadoc (lines 1-45) is itself a fairly accurate
  description of the detection algorithm — verified against `SubscriptionDetectionService`
  below and found correct, unlike most doc-comments in this repo.

- **`@Import` / `@Configuration` (manual, 5-class pattern — see O2):**
  `config/SubscriptionSecurityConfig.java:21-22`:
  ```java
  @Configuration
  @Import({ SecurityConfig.class, JwtAuthFilter.class, JwtProperties.class,
            GlobalExceptionHandler.class, RequestIdMdcFilter.class })
  public class SubscriptionSecurityConfig {}
  ```
  All five classes are `finance.trove.common.security`/`.exception`/`.observability` from
  `libs/common`. **`finance.trove.common.config.JacksonConfig` is NOT imported** — this
  service registers no naming-strategy customizer; DTOs use default Jackson (camelCase)
  field names except where an entity explicitly names a JSON property (see below).

- **Other `@Configuration` classes:**
  - `config/RestClientConfig.java:16-22` — single `RestClient restClient()` bean, no
    timeouts, no interceptors (base URL/auth applied at call site, see §6).
  - `config/SchedulingConfig.java:10-12` — `@Configuration @EnableScheduling`, present
    (this is one of the 4 services D8 says has it).

- **Security filter chain** — inherited unmodified from `libs/common`
  (`libs/common/src/main/java/finance/trove/common/security/SecurityConfig.java:84-89`):
  permitAll on `/test/**`, `/actuator/health`, `/actuator/prometheus`, `/api/v1/auth/**`,
  `/api/v1/health`, `/api/health`; everything else `.anyRequest().authenticated()`.
  `/v1/subscriptions/**` (this service's only routes) is **not** in the permitAll list, so
  every route requires a JWT.

- **Identity source: JWT `sub` claim only, NOT `X-User-Id`.**
  `JwtAuthFilter.doFilterInternal` (`libs/common/.../JwtAuthFilter.java:83-126`) parses
  `Authorization: Bearer <jwt>`, takes `claims.getSubject()` as the userId (parsed as UUID,
  line 102), and puts a `TroveUserDetails` principal in the `SecurityContext`. The
  controller reads it via `@AuthenticationPrincipal TroveUserDetails user` →
  `user.getUserId()` (`SubscriptionController.java:56`, `:85`, `:115`, `:140`, `:164`,
  `:192`). **No code in this module reads an `X-User-Id` header** — grep for `X-User-Id`
  in `src/main` returns zero hits. Identity flows exclusively through the caller's own JWT;
  there is no IDOR surface analogous to the gateway one described elsewhere in the dossier
  (G1), because every query is scoped by `user.getUserId()` from the verified token, not by
  a client-supplied header.

- **`JacksonConfig` / naming strategy:** not imported (see above) — default camelCase in
  effect for this service's own request/response bodies (`ApiResponse<T>`,
  `SubscriptionSummary`, `UpcomingCharge`, `DetectedSubscription`, `SubscriptionAlert`).
  `TransactionDto` (inbound, from ingestion-service) explicitly annotates snake_case fields
  with `@JsonProperty` one at a time (`detection/TransactionDto.java:19,20,24,25`) rather
  than relying on a naming strategy — this only works because those fields are being
  deserialized, and explicit `@JsonProperty` overrides any strategy anyway; it means this
  DTO would behave identically whether or not `JacksonConfig` were imported, but every
  *other* response type in this service (all outbound) is camelCase while the codebase's
  convention (per `JacksonConfig`, used elsewhere) is snake_case. See §12.

- **`@EnableScheduling`:** yes, via `SchedulingConfig` (above). **`@EnableAsync`:** no —
  no `@Async` anywhere in `src/main`. **Application events:** none — no
  `ApplicationEventPublisher` / `@EventListener` anywhere in this module.

## 3. HTTP routes

All routes are under `SubscriptionController` (`subscription/SubscriptionController.java`),
`@RequestMapping("/v1/subscriptions")`. There is no other `@RestController` in the module.

| Method | Path | Controller#method | Identity source | Request DTO | Response DTO | Notes |
|---|---|---|---|---|---|---|
| GET | `/v1/subscriptions` | `SubscriptionController#list` (line 56) | JWT `sub` via `@AuthenticationPrincipal` | none | `ApiResponse<List<DetectedSubscription>>` | Active (non-cancelled) only, ordered by `merchantName` asc. No pagination params. |
| GET | `/v1/subscriptions/{id}` | `#get` (line 81) | JWT `sub` | path `id` (UUID) | `ApiResponse<DetectedSubscription>` | 404 (not 403) if `id` exists but belongs to another user — ownership check in `SubscriptionService.get` (line 35-39). |
| DELETE | `/v1/subscriptions/{id}` | `#cancel` (line 111) | JWT `sub` | path `id` (UUID) | 204, no body | Soft-delete: sets `is_cancelled=true`; does not delete the row. |
| GET | `/v1/subscriptions/summary` | `#summary` (line 139) | JWT `sub` | none | `ApiResponse<SubscriptionSummary>` | `currency` field of the response is **hardcoded `"CAD"`** regardless of caller — see §12/F1. No filter params. |
| GET | `/v1/subscriptions/alerts` | `#alerts` (line 163) | JWT `sub` | none | `ApiResponse<List<SubscriptionAlert>>` | Unread only, `createdAt` desc. No pagination. |
| PATCH | `/v1/subscriptions/alerts/{id}/read` | `#markRead` (line 187) | JWT `sub` | path `id` (UUID) | `ApiResponse<SubscriptionAlert>` | Idempotent-ish: re-marking an already-read alert succeeds (no guard against double mark). |

No create/update endpoint exists — `DetectedSubscription` and `SubscriptionAlert` rows are
written exclusively by the background scheduler/detection pipeline (§7). There is **no
manual "run detection now" endpoint** — consistent with D2 (below): a stuck user has no way
to self-trigger a scan.

## 4. Persistence

**Database:** `trove_subscription` (from `application.yml:7`,
`jdbc:postgresql://.../${DB_NAME:trove_subscription}`), one Postgres catalog dedicated to
this service in the multi-catalog layout (O6).

### Entities → tables

**`DetectedSubscription`** (`subscription/DetectedSubscription.java:39-61`) →
table `detected_subscription`:
| Column | Type/notes |
|---|---|
| `id` | UUID PK, `gen_random_uuid()` |
| `user_id` | UUID NOT NULL |
| `merchant_name` | VARCHAR(200) NOT NULL — normalized name, natural key within a user |
| `category_id` | UUID, nullable |
| `normalized_amount` | NUMERIC(12,2) NOT NULL |
| `currency` | VARCHAR(3) NOT NULL DEFAULT `'CAD'` — **hardcoded default, never overridden anywhere in `src/main`** (grep for `setCurrency`/`.currency(` in main returns zero hits outside the entity default) |
| `billing_cycle` | VARCHAR(12) NOT NULL, CHECK IN (WEEKLY/BIWEEKLY/MONTHLY/QUARTERLY/ANNUAL) |
| `next_charge_date` | DATE, nullable |
| `signal` | VARCHAR(6) NOT NULL DEFAULT `'GREEN'`, CHECK IN (GREEN/AMBER/RED) |
| `price_changed` | BOOLEAN NOT NULL DEFAULT false |
| `previous_amount` | NUMERIC(12,2), nullable |
| `first_seen_date` | DATE NOT NULL |
| `last_seen_date` | DATE NOT NULL |
| `transaction_ids` | `uuid[]` NOT NULL DEFAULT `'{}'` — Postgres array via `@JdbcTypeCode(SqlTypes.ARRAY)` |
| `is_cancelled` | BOOLEAN NOT NULL DEFAULT false (soft-delete flag) |
| `created_at` / `updated_at` | TIMESTAMPTZ NOT NULL, `updated_at` bumped by `@PreUpdate onUpdate()` (line 61) |

No FK. No UNIQUE constraint on `(user_id, merchant_name)` in the SQL (V1 migration has none)
— uniqueness is enforced only in application code via
`findByUserIdAndMerchantName` + upsert (`SubscriptionDetectionService.java:124-130`), which
is a race under concurrent scans of the same user (not currently possible — only one
scheduler instance loops sequentially — but would become one under any horizontal scale-out
of the scan, since nothing prevents two concurrent `detectForUser` calls for the same user
from both missing each other's insert and double-inserting).

Indexes (`V1__create_detected_subscription.sql:25-29`):
- `idx_subscription_user_id (user_id)`
- `idx_subscription_user_active (user_id, is_cancelled) WHERE is_cancelled = false`
- `idx_subscription_next_charge (user_id, next_charge_date) WHERE is_cancelled = false`

**`SubscriptionAlert`** (`alert/SubscriptionAlert.java:20-31`) → table `subscription_alert`:
| Column | Type/notes |
|---|---|
| `id` | UUID PK |
| `user_id` | UUID NOT NULL |
| `subscription_id` | UUID NOT NULL, **FK** → `detected_subscription(id) ON DELETE CASCADE` (only FK in the module — `V2__create_subscription_alert.sql:4`) |
| `alert_type` | VARCHAR(20) NOT NULL, CHECK IN (PRICE_INCREASE/UPCOMING/PAYMENT_FAILED) |
| `alert_message` | TEXT NOT NULL |
| `is_read` | BOOLEAN NOT NULL DEFAULT false |
| `created_at` | TIMESTAMPTZ NOT NULL |

Indexes (`V2:12-14`): `idx_sub_alert_user_id (user_id, created_at DESC)`,
`idx_sub_alert_unread (user_id, is_read) WHERE is_read = false`.

### Repositories with custom queries

- `SubscriptionRepository` (`subscription/SubscriptionRepository.java`):
  - `findAllByUserIdAndCancelledFalseOrderByMerchantNameAsc` (derived, line 19)
  - `findByUserIdAndMerchantName` (derived, line 20)
  - `findDistinctActiveUserIds()` — custom `@Query("SELECT DISTINCT s.userId FROM
    DetectedSubscription s WHERE s.cancelled = false")` (line 21-22) — **this is the D2
    bootstrap-deadlock query**, see §7.
- `AlertRepository` (`alert/AlertRepository.java`):
  - `findAllByUserIdAndReadFalseOrderByCreatedAtDesc` (derived, line 15)

Flyway migrations: `V1__create_detected_subscription.sql` (creates `detected_subscription`
+ 3 indexes, enables `pgcrypto`), `V2__create_subscription_alert.sql` (creates
`subscription_alert` + FK + 2 indexes). `spring.flyway.baseline-on-migrate: true`
(`application.yml:16`).

## 5. Config — every property in `application.yml`

| Key | Env var | Default | Secret? |
|---|---|---|---|
| `spring.application.name` | — | `subscription-detector` | no |
| `spring.profiles.default` | — | `local` | no |
| `spring.datasource.url` | `DB_HOST`, `DB_PORT`, `DB_NAME` | `localhost`, `5432`, `trove_subscription` | no |
| `spring.datasource.username` | `DB_USER` | `trove` | **yes — default DB credential** |
| `spring.datasource.password` | `DB_PASS` | `trove` | **yes — default DB credential, `trove`/`trove`** |
| `spring.jpa.hibernate.ddl-auto` | — | `validate` | no |
| `spring.jpa.open-in-view` | — | `false` | no |
| `spring.flyway.enabled` | — | `true` | no |
| `spring.flyway.baseline-on-migrate` | — | `true` | no |
| `server.port` | — | `8086` | no |
| `management.endpoints.web.exposure.include` | — | `health,info,prometheus,metrics` | no |
| `management.endpoint.health.show-details` | — | `when-authorized` | no |
| `management.metrics.export.prometheus.enabled` | — | `true` | no |
| `trove.jwt.secret` | `TROVE_JWT_SECRET` | **none — `${TROVE_JWT_SECRET}` with no fallback, startup fails if unset** | **yes** |
| `app.ingestion.url` | `INGESTION_SERVICE_URL` | `http://ingestion-service:8081` | no, but **wrong hostname** — see §6/§12 |
| `app.service-token` | `SERVICE_TOKEN` | `internal-service-token` | **yes — default shared-secret placeholder, same literal string presumably reused across services** |

`.env.example` (repo-relative `services/subscription-detector/.env.example`) mirrors
`DB_USER=trove` / `DB_PASS=trove` / `TROVE_JWT_SECRET=change-me-minimum-32-chars-in-
production!!` with `# CHANGE IN PRODUCTION` comments, and notably **does not set
`INGESTION_SERVICE_URL` or `SERVICE_TOKEN` at all** — it only documents the vars present in
`docker/docker-compose.yml`'s `subscription-det` block, which itself does not set those two
either (see §6).

## 6. Outbound HTTP

Exactly one outbound HTTP client: `IngestionClient`
(`detection/IngestionClient.java`), calling ingestion-service.

| Target | Path | Headers | Timeouts | File:line |
|---|---|---|---|---|
| ingestion-service (`app.ingestion.url`) | `GET {url}/v1/transactions?user_id={id}&date_from={date}&limit=200&page={n}` | `Authorization: Bearer {app.service-token}` (static service token, not the caller's JWT) | **None** — `RestClientConfig.java:19` builds `RestClient.create()` with zero customization; Spring's `RestClient` default has no read/connect timeout set at this layer, so a hung ingestion-service call blocks the daily scan thread indefinitely (or until the JVM/container-level default, which is unbounded for the default `SimpleClientHttpRequestFactory`). | `IngestionClient.java:48-52` (request build), `RestClientConfig.java:16-22` (bean) |

**Hostname default bug (confirms O4):** `application.yml:40` defaults
`app.ingestion.url` to `http://ingestion-service:8081`. The actual Docker Compose service/
DNS name is `ingestion-svc` (`docker/docker-compose.yml:154`, container built from
`services/ingestion-service/Dockerfile`). `docker/docker-compose.yml`'s `subscription-det`
block (lines 330-350) sets `JAVA_TOOL_OPTIONS`, `SPRING_PROFILES_ACTIVE`, `DB_HOST/PORT/
NAME/USER/PASS`, and `TROVE_JWT_SECRET` — **it never sets `INGESTION_SERVICE_URL`**, so this
service falls through to the broken `http://ingestion-service:8081` default and cannot
resolve the ingestion host under the compose file the README points at. Only
`infra/full-stack/docker-compose.yml:411` sets `INGESTION_SERVICE_URL:
http://ingestion-svc:8081` (correct) — and that file also doesn't set `SERVICE_TOKEN`
consistently with `docker/docker-compose.yml` (it uses `${TROVE_SERVICE_TOKEN}`, an env var
name that doesn't match this service's own `.env.example`, which has none at all).

Pagination logic (`IngestionClient.java:43-86`): loops `page=0,1,2,...` until either
`all.size() >= meta.total` or a page returns 0 parsed items; per-transaction parse failures
are caught and logged (`:72-77`) rather than aborting the fetch. A 4xx/5xx from
ingestion-service throws `IllegalStateException` (`:54-61`), which propagates up through
`SubscriptionDetectionService.detectForUser` and is caught per-user by the scheduler
(`SubscriptionScanScheduler.java:37`) — one user's ingestion failure does not abort the
nightly batch for other users.

## 7. Scheduled / async / events

- **`@Scheduled(cron = "0 0 2 * * *")`** — `SubscriptionScanScheduler.scanAll()`
  (`scheduler/SubscriptionScanScheduler.java:30-42`). Runs daily at 02:00 (server-local /
  JVM default timezone — no explicit `zone=` on `@Scheduled`, so this is **not** guaranteed
  UTC despite the class Javadoc's "02:00 UTC" claim, contradicted by the actual annotation
  which carries no `zone` attribute).
  - **User set enumerated — this is D2, reverified directly in this module:**
    `subscriptionRepository.findDistinctActiveUserIds()`
    (`SubscriptionRepository.java:21-22`) = `SELECT DISTINCT s.userId FROM
    DetectedSubscription s WHERE s.cancelled = false`. On a fresh install this table is
    empty, so the query returns zero users, so `detectForUser` is called on nobody, so no
    rows are ever inserted, so the next night's query is still empty. **The nightly scan
    can only rescan users who already have at least one active `DetectedSubscription`
    row; a user with zero rows can never get their first one detected by this job**, and
    there is no manual-trigger endpoint (confirmed in §3 — no POST/scan route exists
    anywhere in `SubscriptionController`) and no other code path calls
    `SubscriptionDetectionService.detectForUser` (grep across `src/main` for
    `detectForUser(` returns only the scheduler call site and the method declaration
    itself).
  - Per-user try/catch (`:35-39`) prevents one user's exception from aborting the loop for
    others; failures are logged, not surfaced anywhere queryable.
- **`@Async`:** none present in this module.
- **Application events:** none published or consumed.

## 8. External dependencies

- **Postgres** — dedicated catalog `trove_subscription` (required, hard dependency via
  `spring.jpa.hibernate.ddl-auto=validate` + Flyway).
- **ingestion-service** — required at runtime for the nightly scan to have any data to
  detect against; called only by `IngestionClient` (§6). No fallback if unreachable (the
  per-user try/catch just skips that user for the night).
- No MinIO, no Redis/Valkey (`infra/full-stack/docker-compose.yml:415-417` lists a
  `valkey` `depends_on` for this service in that compose file, but nothing in `src/main`
  references Redis/Valkey/Lettuce/Jedis at all — dead `depends_on`, not a real
  dependency), no Ollama/LLM endpoint, no PaddleOCR, no SMTP, no SimpleFIN/OFX direct
  usage, no Authentik/OIDC, no LiteLLM. This is one of the more self-contained services in
  the repo: Postgres + one internal HTTP call, nothing else.

## 9. Tests

`src/test` totals 924 LOC across 9 classes:
- **Unit (Mockito, no Spring context):** `ConfidenceCalculatorTest` (52 LOC),
  `IntervalClassifierTest` (18 LOC), `MerchantNameNormalizerTest` (14 LOC),
  `SubscriptionDetectionServiceTest` (159 LOC, mocks `IngestionClient`,
  `SubscriptionRepository`, `AlertRepository`, `IntervalClassifier`,
  `ConfidenceCalculator`), `SubscriptionScanSchedulerTest` (65 LOC, mocks
  `SubscriptionDetectionService` + `SubscriptionRepository`), `SubscriptionServiceTest`
  (260 LOC), `AlertServiceTest` (116 LOC) — 7 classes, Mockito `@InjectMocks`/`@Mock`.
- **Integration (`@SpringBootTest` + Testcontainers Postgres + WireMock):**
  `SubscriptionControllerIT` (92 LOC, extends `BaseIT`, exercises all 6 routes end-to-end
  against a real Postgres container and a JWT minted by `JwtTestHelper`), `OpenApiTest`
  (66 LOC, hits `/v3/api-docs.yaml` and writes the result to
  `../../openapi/subscription-detector.yaml` as a side effect of running the test suite).
- **Support:** `BaseIT` (Testcontainers Postgres + WireMock bootstrap, 50 LOC),
  `JwtTestHelper` (HS256 token minting matching `JwtAuthFilter`'s expected claims, 32 LOC).

**No test exists for the D2 bootstrap-deadlock scenario** — `SubscriptionScanSchedulerTest`
covers "no users → detect never called" (line 32-38) and "two users → detect called for
each" (line 40-51) but never asserts anything about *how* the user list is produced or that
a fresh install can never populate it; the gap is a missing test, not an incorrect
assertion.

**Test that asserts a bug as correct behaviour:**
- `SubscriptionServiceTest.java:125` — `assertThat(summary.currency()).isEqualTo("CAD")` —
  locks in the hardcoded `"CAD"` literal from `SubscriptionService.java:59` as expected
  output, for every user regardless of their configured currency (see §12/F1).

## 10. Verified defects

| ID | Summary | File:line | Verified how |
|---|---|---|---|
| SD-1 (= dossier D2) | Nightly scan enumerates only users with an existing active subscription (`findDistinctActiveUserIds`), so a fresh install can never detect a first subscription; no manual-trigger endpoint exists. | `subscription/SubscriptionRepository.java:21-22`; `scheduler/SubscriptionScanScheduler.java:32`; absence confirmed by grep for `detectForUser(` across `src/main` (only 2 hits: declaration + scheduler call) and for any `POST`/scan mapping in `SubscriptionController.java` (none) | Read source directly; traced the only caller of `detectForUser`. |
| SD-2 (= dossier O4) | `app.ingestion.url` defaults to `http://ingestion-service:8081`, a hostname not defined in `docker/docker-compose.yml` (service is `ingestion-svc`); that compose file's `subscription-det` block never overrides `INGESTION_SERVICE_URL`, so the default is what actually loads under the README's compose file. | `src/main/resources/application.yml:40`; `docker/docker-compose.yml:154` (service name), `:330-350` (env block, no `INGESTION_SERVICE_URL` key) | Grepped both compose files for `ingestion` and `subscription-det`/`subscription`; confirmed the env block for this service by reading lines 330-350 directly. |
| SD-3 (= dossier F1, this module's instance) | Currency is hardcoded `"CAD"` in two places: the entity default (`DetectedSubscription.currency`, applies to every detected row regardless of the owning user's configured currency — and nothing in `src/main` ever calls a setter to override it) and the summary DTO (`SubscriptionService.getSummary` always returns literal `"CAD"`). A test pins the second as correct. | `subscription/DetectedSubscription.java:48`; `subscription/SubscriptionService.java:59`; test `src/test/java/finance/trove/subscription/subscription/SubscriptionServiceTest.java:125` | Grepped `src/main` for `setCurrency`/`.currency(` (only the two hardcoded sites exist); read the test assertion directly. |
| SD-4 | `IngestionClient`'s `RestClient` has no connect/read timeout configured anywhere (`RestClientConfig.restClient()` is a bare `RestClient.create()`); a hung ingestion-service response blocks the nightly scan thread for that user indefinitely. | `config/RestClientConfig.java:16-22`; `detection/IngestionClient.java:50-62` | Read the bean definition; confirmed no `ClientHttpRequestFactory`/timeout customizer exists anywhere in the module (grep for `Timeout` in `src/main` returns zero hits). |
| SD-5 | No unique DB constraint on `(user_id, merchant_name)` in `detected_subscription` — uniqueness is enforced only by an application-level `findByUserIdAndMerchantName` read-then-write in `detectForUser`, which is a duplicate-insert race if the scan is ever run concurrently for the same user (e.g. a future manual-trigger endpoint invoked while the nightly job is also running). | `db/migration/V1__create_detected_subscription.sql:3-23` (no UNIQUE); `detection/SubscriptionDetectionService.java:124-138` (check-then-act, no upsert/`ON CONFLICT`) | Read the full V1 migration (no UNIQUE index on those columns, only the three listed in §4) and the upsert logic. |
| SD-6 | Scheduler Javadoc claims "02:00 UTC" but the `@Scheduled` cron has no `zone` attribute, so the actual run time is the JVM's default timezone, not UTC. | `scheduler/SubscriptionScanScheduler.java:12-14` (doc) vs `:30` (`@Scheduled(cron = "0 0 2 * * *")`, no `zone=`) | Read the annotation directly; Spring's `@Scheduled` cron uses the JVM default zone unless `zone` is set. |
| SD-7 | `JacksonConfig` (snake_case naming strategy, used by the rest of the codebase per O2/§2) is not imported by this service's `@Import` list, so all of this service's own outbound JSON (list/get/summary/alerts responses) is camelCase while `TransactionDto`'s inbound fields are explicitly `@JsonProperty`-annotated snake_case — an inconsistency a client integration would need to know about per-service rather than relying on one convention. | `config/SubscriptionSecurityConfig.java:22` (5-class import list, no `JacksonConfig`); compare `libs/common/src/main/java/finance/trove/common/config/JacksonConfig.java:9-17` | Diffed the `@Import` list against the JacksonConfig class; confirmed no other import path brings it in (grep for `JacksonConfig` across the whole module returns only the library definition, zero references in `services/subscription-detector`). |

## 11. Collapse recommendation

Target namespace root: `finance.trove.<domain>.<sub>` under `trove-core`. Proposed:
`finance.trove.subscriptions.*` (top-level domain package, mirroring the service's own
current top-level `finance.trove.subscription`).

| Package (current) | Action | Destination | Reason |
|---|---|---|---|
| `finance.trove.subscription` (application class) | DELETE | — | `TroveSubscriptionDetectorApplication` is a per-service Spring Boot entry point; trove-core has exactly one `@SpringBootApplication` class. Fold the Javadoc's accurate algorithm description into a package-info or the new `SubscriptionDetectionService` class doc — it's worth keeping as documentation even though the class itself goes. |
| `finance.trove.subscription.config.RestClientConfig` | KEEP (merge) | `finance.trove.subscriptions.config` — but **check for a bean-name/type clash**: if another collapsing service also declares a `RestClient restClient()` `@Bean` of the same return type with no qualifier, Spring will only keep one bean of type `RestClient` unless they're named/qualified distinctly. Rename to a qualified bean (e.g. `ingestionRestClient`) during the merge. | Needed for the ingestion HTTP call; trivial, but must be de-duplicated/renamed against every other service's identical `RestClient.create()` bean (this pattern is described in the dossier as common across services — verify each one during the actual merge, not assumed here). |
| `finance.trove.subscription.config.SchedulingConfig` | DELETE (merge semantics only) | — | `@EnableScheduling` is a module-wide switch; trove-core needs exactly one `@EnableScheduling` (or one in a shared core config), not one per collapsed service. Keep the *fact* that this service needs scheduling enabled; delete the redundant class once one `@EnableScheduling` exists at core level. |
| `finance.trove.subscription.config.SubscriptionSecurityConfig` | DELETE | — | This is the O2 hand-duplicated 5-class `@Import` pattern. trove-core should wire `SecurityConfig`/`JwtAuthFilter`/`JwtProperties`/`GlobalExceptionHandler`/`RequestIdMdcFilter`/`JacksonConfig` exactly **once**, centrally. Note this service was *missing* `JacksonConfig` from its own copy of the pattern (SD-7) — a good example of why the duplicated-import approach is fragile; the collapse fixes this by construction. |
| `finance.trove.subscription.detection.*` (`BillingCycle`, `ConfidenceCalculator`, `IntervalClassifier`, `MerchantNameNormalizer`, `SubscriptionDetectionService`, `TransactionDto`, `IngestionClient`) | KEEP, REWRITE `IngestionClient` | `finance.trove.subscriptions.detection` | Core detection algorithm — no dependency on removed features (tax, MinIO, notification listeners, plugins). `IngestionClient` must be REWRITTEN: in a monolith, "fetching transactions from ingestion-service" becomes an **in-process call** (a `TransactionQueryService`/repository call in the merged `finance.trove.ingestion` package) — delete the `RestClient`/pagination/service-token machinery entirely (§6, SD-2, SD-4 all evaporate with it) and replace `fetchTransactions(userId, from)` with a direct repository/service call inside the same JVM and transaction. `TransactionDto`'s `@JsonProperty` snake_case mapping also becomes unnecessary once it's a direct Java-object handoff rather than a deserialized HTTP response — replace with the (or a subset of the) actual `Transaction` entity/projection from the merged ingestion package. |
| `finance.trove.subscription.subscription.*` (`DetectedSubscription`, `SubscriptionController`, `SubscriptionRepository`, `SubscriptionService`, `SubscriptionSignal`, `SubscriptionSummary`, `UpcomingCharge`) | KEEP, fix currency at merge time | `finance.trove.subscriptions.subscription` | Core read/lifecycle API for the feature the owner is keeping. At merge time: (1) add a real `currency` column write path — read the user's onboarding currency setting (CAD/USD per the owner's decision) instead of the two hardcoded `"CAD"` sites (SD-3), and update `DetectedSubscription.currency`'s default and `SubscriptionService.getSummary`'s literal accordingly; (2) delete/rewrite the test at `SubscriptionServiceTest.java:125` that pins `"CAD"` as correct, since it will start failing (correctly) once currency is parameterized. |
| `finance.trove.subscription.alert.*` (`AlertRepository`, `AlertService`, `AlertType`, `SubscriptionAlert`) | KEEP | `finance.trove.subscriptions.alert` | Straightforward, no external dependency, no cut feature involved. |
| `finance.trove.subscription.scheduler.SubscriptionScanScheduler` | KEEP, REWRITE | `finance.trove.subscriptions.scheduler` | The cron job itself is worth keeping, but the enumeration bug (SD-1/D2) must be fixed as part of the move — enumerate **all users with at least one linked account/ingested transaction** (or simply all users), not `findDistinctActiveUserIds()` on this table, otherwise the collapse just relocates a dead feature. This is exactly the kind of fix the owner's plan implies should happen during consolidation, not left for later. Also fix the "02:00 UTC" doc/`zone=` mismatch (SD-6) while touching this class. |

**No class in this module duplicates a same-named class from another service** beyond the
already-known `libs/common` 5-class `@Import` pattern (`SecurityConfig`, `JwtAuthFilter`,
`JwtProperties`, `GlobalExceptionHandler`, `RequestIdMdcFilter`) shared identically by every
other importing service, and the `RestClientConfig`/`restClient()` bean-name pattern flagged
above, which recurs per-service across the codebase (verify concretely against each service's
own inventory during the actual merge — not exhaustively re-verified against all 9 other
services here). This module has **no** `MinioStorageService`, no bank-notification listener,
no plugin/IMAP code, no tax code — nothing here intersects the owner's CUT list at all beyond
the currency default.

## 12. Merge risks / gotchas

- **Bean-name/type clash:** `RestClientConfig.restClient()` — a bare, unqualified
  `RestClient` bean (`config/RestClientConfig.java:18-21`). If any other service being
  collapsed registers an equally-unqualified `RestClient` bean (a common Spring Boot
  starter pattern), Spring will fail to wire ambiguous injections, or one definition will
  silently shadow the other depending on bean-definition overriding settings. Rename/
  qualify (`@Bean("ingestionRestClient")` or delete outright per §11, since the collapse
  turns this into an in-process call and the bean becomes unnecessary).
- **Table-name clash:** none found — `detected_subscription` and `subscription_alert` are
  unique names across the repo (grep confirmed no other `.sql` file creates either).
- **Property-key clash:** `app.ingestion.url` and `app.service-token` are both under the
  generic `app.*` prefix, which is almost certainly reused with different meanings by other
  services (a common `application.yml` root key). When these merge into one
  `application.yml`, `app.ingestion.url` becomes dead entirely (§11 — no more outbound HTTP
  call) so this specific key can simply be deleted rather than renamed; **do not** carry
  `app.service-token` forward as a real secret if no other collapsed service still needs an
  internal service-to-service bearer token once everything is one JVM.
- **Static state:** none — `MerchantNameNormalizer` is a stateless static utility
  (`detection/MerchantNameNormalizer.java`), no static mutable fields anywhere in
  `src/main`.
- **Hardcoded ports/hostnames:** `server.port: 8086` (`application.yml:19`) becomes
  irrelevant once merged into one JVM/one port. `app.ingestion.url` default
  `http://ingestion-service:8081` (SD-2) — delete along with the whole client per §11.
  Dockerfile (`Dockerfile:15`) `EXPOSE 8086` and healthcheck URL
  `http://localhost:8086/actuator/health` — delete, this service's own container image
  goes away entirely under the modular-monolith collapse.
- **Hardcoded currency (CAD) sites:** exactly two, both cited above —
  `DetectedSubscription.java:48` (entity column default) and `SubscriptionService.java:59`
  (summary DTO literal) — plus the one test that pins the second as correct
  (`SubscriptionServiceTest.java:125`). Must be fixed together with the owner's per-user
  currency (CAD/USD) onboarding setting, not left as-is.
- **`X-User-Id` reads:** **none.** This module never reads `X-User-Id` anywhere in
  `src/main` — identity is exclusively the verified JWT `sub` claim via
  `@AuthenticationPrincipal TroveUserDetails`. This is one of the cleaner services in that
  regard (contrast with the gateway-level `X-User-Id` IDOR the dossier's G1 describes for
  other paths) — no special remediation needed here on that axis specifically, but
  confirm during the actual merge that the in-process replacement for `IngestionClient`
  (§11) continues to scope every query by the *caller's own* authenticated userId and never
  accepts a userId as raw request input from anywhere else.
- **Jackson naming-strategy inconsistency (SD-7):** this service's own JSON responses are
  camelCase (no `JacksonConfig` import) while the codebase convention elsewhere (per O2) is
  snake_case via `JacksonConfig`. If trove-core wires `JacksonConfig` globally as intended,
  this module's response *shapes* will silently change from camelCase to snake_case for any
  web/mobile client still expecting the old (buggy) camelCase shape — flag this explicitly
  to whoever owns the web/mobile subscription screens during the merge, since it is a
  wire-format change, not just an internal refactor.
- **Dead `depends_on: valkey`** in `infra/full-stack/docker-compose.yml` for this service
  with zero actual Redis/Valkey usage in `src/main` — drop it; not a merge risk per se, but
  worth noting so nobody spends time provisioning Valkey access for a service that never
  needed it.
