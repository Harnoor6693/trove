# Inventory: services/report-service

> **Historical record, 2026-09-03.** Inventory of the pre-rebuild code, since removed; the computed-GST export described here was replaced by a categorised CSV/PDF export. Current state: `docs-site/guide/architecture.md`.

Audited against commit state as of 2026-09-03 (working tree; `pom.xml` was observed to be
edited by a concurrent build process mid-audit — a Jetty version property was
parameterized; no dependency behavior changes were made, so findings below are unaffected).

---

## 1. Purpose and LOC

Generates asynchronous financial reports (CSV or PDF) for a user over a date range —
monthly summaries, an annual category rollup, a "tax export" CSV with a computed GST
column, or a free-form custom export. Report requests are persisted immediately
(`PENDING`), dispatched onto a bounded thread pool, and the caller polls
`GET /v1/reports/{id}` until `COMPLETE` (with a MinIO presigned download URL) or `FAILED`.

- LOC main: **1560** (`find services/report-service/src/main -name '*.java' | xargs wc -l | tail -1`)
- LOC test: **1229** (`find services/report-service/src/test -name '*.java' | xargs wc -l | tail -1`)

---

## 2. Spring wiring

- **Application class:** `finance.trove.report.TroveReportApplication`
  (`services/report-service/src/main/java/finance/trove/report/TroveReportApplication.java:18-19`)
  — `@SpringBootApplication @EnableAsync`. No `@EnableScheduling` anywhere in the module
  (grep confirms zero hits) — this service has no `@Scheduled` methods.
- **`@Configuration` / `@Import` classes:**
  - `ReportSecurityConfig` (`config/ReportSecurityConfig.java:22-23`) —
    `@Import({ SecurityConfig.class, JwtAuthFilter.class, JwtProperties.class, GlobalExceptionHandler.class, RequestIdMdcFilter.class })`,
    all from `libs/common`. No service-specific `SecurityFilterChain` bean is declared — the
    shared chain from `libs/common/.../SecurityConfig.java` is used verbatim.
  - `ReportAsyncConfig` (`config/ReportAsyncConfig.java:31-34`) — `@EnableAsync @Configuration`,
    declares the `reportExecutor` `ThreadPoolTaskExecutor` bean (core=2, max=4, queue=50,
    `AbortPolicy`; ReportAsyncConfig.java:46-59) and a shared no-base-URL `RestClient` bean
    (`ReportAsyncConfig.java:68-70`) used by `NarrativeSummaryService`'s AI-gateway calls. Also
    registers a custom `AsyncUncaughtExceptionHandler` (`ReportAsyncConfig.java:80-83`) that
    logs `reportId` when the first `@Async` argument is a `UUID`.
  - `MinioConfig` (`storage/MinioConfig.java:23-44`) — produces the `MinioClient` bean from
    `trove.minio.endpoint/access-key/secret-key`.
  - No auto-configuration `.imports` file in this module; `libs/common` ships none either
    (per dossier O2), so every `@Import` above is hand-listed, same as the other 9 services.
- **Security filter chain** (shared, from `libs/common/src/main/java/finance/trove/common/security/SecurityConfig.java:58-91`):
  - CSRF disabled, `STATELESS` session policy.
  - `permitAll`: `/test/**`, `/actuator/health`, `/actuator/prometheus`, `/api/v1/auth/**`,
    `/api/v1/health`, `/api/health` (`SecurityConfig.java:85-88`). None of these paths are
    actually served by report-service's own controllers — report-service exposes only
    `/v1/reports/**`, so effectively **every real endpoint requires a JWT**.
  - Everything else: `anyRequest().authenticated()`.
  - **Identity source:** `JwtAuthFilter` (`libs/common/.../JwtAuthFilter.java:83-113`) reads
    `Authorization: Bearer <token>`, verifies HS256 with `jwtProperties.getSecret()`, and sets
    a `TroveUserDetails` principal from claim `sub` (parsed as `UUID`). Report-service reads
    this exclusively via `@AuthenticationPrincipal TroveUserDetails user` in
    `ReportController` (`report/ReportController.java:65,90,120,147`) — **there is no
    `X-User-Id` header handling anywhere in this module** (grep confirms zero hits); this
    differs from ingestion-service's header-based model. Good hygiene for this service in
    isolation, but see §12 for the consequence at the seam with ingestion.
  - `trove.jwt.secret` in this service's `application.yml:33` is `${TROVE_JWT_SECRET}` with
    **no fallback default** — startup fails fast if unset (contrast with the weak
    `change-me...` default some other services ship; see `libs/common/.../JwtProperties.java:46`
    for the 32-char validation that default is sized to just clear).
- **JacksonConfig:** not imported by this module (grep: zero hits for `JacksonConfig` under
  `services/report-service`). Response serialization uses Spring Boot 4's default Jackson 3
  (`tools.jackson`) camelCase naming — i.e. this service is on the "7 services emit
  `fieldErrors`" side of the split-contract bug (dossier M11), not the gateway's
  `field_errors` side.
- **`@EnableScheduling` / `@EnableAsync`:** only `@EnableAsync`
  (`TroveReportApplication.java:18`), backing the one `@Async("reportExecutor")` method
  (`generation/ReportGenerationService.java:65`). No `@EnableScheduling`, no `@Scheduled`
  methods in this module.

---

## 3. HTTP routes

All routes are under `@RequestMapping("/v1/reports")` in
`report/ReportController.java`. Identity source for every route: JWT `sub` via
`@AuthenticationPrincipal TroveUserDetails user` (never a header).

| METHOD | Path | Controller#method | Identity source | Request DTO | Response DTO | Notes |
|---|---|---|---|---|---|---|
| POST | `/v1/reports` | `ReportController#request` (ReportController.java:64-67) | JWT sub | `CreateReportRequest` (`@Validated @RequestBody`) | `ApiResponse<Report>` | Returns `202 Accepted`. Persists row `PENDING` synchronously, then dispatches `ReportGenerationService.generateAsync` (fire-and-forget); if the `reportExecutor` queue is full the row is immediately flipped to `FAILED` before the response is sent (`ReportService.java:64-71`). |
| GET | `/v1/reports` | `ReportController#list` (ReportController.java:88-96) | JWT sub | — | `ApiResponse<List<Report>>` with `PaginationMeta` | Query params `page` (default 0) and `size` (default 20), both plain `int` — no upper bound / no validation on `size`, so an unauthenticated-but-JWT-valid caller can request e.g. `size=2000000` (no server-side clamp). Ordered newest-first (`ReportRepository.findAllByUserIdOrderByCreatedAtDesc`). |
| GET | `/v1/reports/{id}` | `ReportController#get` (ReportController.java:118-123) | JWT sub | — (path `id: UUID`) | `ApiResponse<ReportWithUrl>` | 404 if not found or not owned (`ReportRepository.findByIdAndUserId`, ReportRepository.java:38). Includes `downloadUrl` + `downloadUrlExpiresAt` only when `status == COMPLETE`. |
| DELETE | `/v1/reports/{id}` | `ReportController#delete` (ReportController.java:145-150) | JWT sub | — (path `id: UUID`) | 204 No Content | Deletes MinIO object first (best-effort — failures are swallowed in `MinioStorageService.delete`, storage/MinioStorageService.java:139-144), then the DB row. Safe on any status. 404 if not found/not owned. |

No other controllers exist in this module (no admin/internal endpoints, no `/internal/**`
routes, no health/report-type-listing endpoints beyond the shared actuator paths).

---

## 4. Persistence

**Database name:** `trove_report` — `application.yml:9`:
`jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:trove_report}`.

**Entity → table:** `Report` (`report/Report.java:23-97`) → table `report`
(`@Table(name = "report")`).

| Column | Java field | Type/constraint | Notes |
|---|---|---|---|
| `id` | `id` | `UUID PRIMARY KEY DEFAULT gen_random_uuid()` | `@GeneratedValue(strategy = GenerationType.UUID)` — DB default and app both generate; app-generated UUID always wins since Hibernate assigns before insert. |
| `user_id` | `userId` | `UUID NOT NULL` | Not a FK (no `users` table in this schema — cross-service, per-DB isolation). |
| `report_type` | `reportType` | `VARCHAR(20) NOT NULL CHECK IN (MONTHLY_SUMMARY,ANNUAL_SUMMARY,TAX_EXPORT,CUSTOM)` | Enum string. |
| `format` | `format` | `VARCHAR(5) NOT NULL CHECK IN (CSV,PDF)` | Enum string. |
| `status` | `status` | `VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK IN (PENDING,GENERATING,COMPLETE,FAILED)` | |
| `period_start` / `period_end` | `periodStart` / `periodEnd` | `DATE` (nullable at column level; `CreateReportRequest` enforces `@NotNull` at the API boundary) | |
| `file_key` | `fileKey` | `TEXT` | MinIO object key `{userId}/{reportId}/{filename}`. |
| `file_size_bytes` | `fileSizeBytes` | `INTEGER` | |
| `error_message` | `errorMessage` | `TEXT` | Truncated to 500 chars by `ReportStatusUpdater.updateStatus` (generation/ReportStatusUpdater.java:44) before persisting — matches no explicit column length constraint (column is `TEXT`, so the 500-char truncation is a defensive app-level choice, not enforced by the schema). |
| `requested_filters` | `requestedFilters` | `JSONB` via `@JdbcTypeCode(SqlTypes.JSON)` | **Dead column**: nothing in the codebase ever writes it — `Report.builder()` in `ReportService.requestReport` never sets `.requestedFilters(...)`, and `ReportDataFetcher.fetchTransactions` is always called with a literal `null` filters argument (`ReportGenerationService.java:79`, `ReportFilters` is otherwise unused). |
| `created_at` / `updated_at` | `createdAt` / `updatedAt` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | `updated_at` refreshed by `@PreUpdate onUpdate()` (Report.java:96-97). |

**Indexes:**
- `idx_report_user_id ON report(user_id, created_at DESC)` — backs `findAllByUserIdOrderByCreatedAtDesc`.
- `idx_report_status ON report(status) WHERE status IN ('PENDING','GENERATING')` — partial
  index, presumably meant for a future "resume stuck reports" sweep; nothing in the codebase
  queries by bare `status` today (no `@Scheduled`, no repository method uses it).

**Repository:** `ReportRepository extends JpaRepository<Report, UUID>`
(`report/ReportRepository.java:16`), two custom finders:
- `Page<Report> findAllByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable)` — derived
  query, no `@Query` annotation needed.
- `Optional<Report> findByIdAndUserId(UUID id, UUID userId)` — derived query, enforces
  ownership at the DB layer (good pattern to keep).

No custom `@Query` (JPQL/native) anywhere in this module.

**Flyway migrations:** exactly one — `V1__create_report.sql`
(`src/main/resources/db/migration/V1__create_report.sql`): creates the `pgcrypto` extension,
the `report` table with the CHECK constraints above, and the two indexes described above.
No later migrations exist for this service.

---

## 5. Config — every property in `application.yml`

(`services/report-service/src/main/resources/application.yml`, 45 lines, single profile file
— no `application-local.yml`/`application-prod.yml` overlays in this module.)

| Key | Env var | Default | Secret? |
|---|---|---|---|
| `server.port` | — | `8088` | no |
| `spring.application.name` | — | `report-service` | no |
| `spring.profiles.default` | — | `local` | no |
| `spring.datasource.url` | `DB_HOST`, `DB_PORT`, `DB_NAME` | `jdbc:postgresql://localhost:5432/trove_report` | no |
| `spring.datasource.username` | `DB_USER` | `trove` | **weak default credential** |
| `spring.datasource.password` | `DB_PASS` | `trove` | **weak default credential** |
| `spring.jpa.hibernate.ddl-auto` | — | `validate` (hardcoded, not env-driven) | no |
| `spring.jpa.open-in-view` | — | `false` (hardcoded) | no |
| `spring.flyway.enabled` | — | `true` (hardcoded) | no |
| `spring.flyway.baseline-on-migrate` | — | `true` (hardcoded) | no |
| `management.endpoints.web.exposure.include` | — | `health,info,prometheus,metrics` (hardcoded) | no |
| `management.endpoint.health.show-details` | — | `when-authorized` (hardcoded) | no |
| `management.metrics.export.prometheus.enabled` | — | `true` (hardcoded) | no |
| `trove.jwt.secret` | `TROVE_JWT_SECRET` | **none — no `:-` fallback; startup fails if unset** | secret (correctly has no baked-in default here, unlike the `change-me...` default other services ship per dossier G5 — verify at the compose-file layer whether `TROVE_JWT_SECRET` itself is set to a weak value there, which is outside this module) |
| `trove.minio.endpoint` | `MINIO_ENDPOINT` | `http://minio:9000` | no (but see §12 — MinIO is being dropped per the collapse decisions) |
| `trove.minio.access-key` | `MINIO_ACCESS_KEY` | `minioadmin` | **weak default credential** |
| `trove.minio.secret-key` | `MINIO_SECRET_KEY` | `minioadmin` | **weak default credential** |
| `trove.minio.bucket-reports` | — | `trove-reports` (hardcoded) | no |
| `trove.minio.presigned-url-expiry-minutes` | — | `30` (hardcoded) | no |
| `app.ingestion.url` | `INGESTION_SERVICE_URL` | `http://ingestion-service:8081` | no — **this is the O4 hostname bug**: no compose file defines a service/container named `ingestion-service` in the maintained `docker/docker-compose.yml` (container is `ingestion-svc`); only `infra/full-stack/` overrides this env var correctly. DNS resolution fails against the README-documented compose path. |
| `app.ai-gateway.url` | `AI_GATEWAY_URL` | `http://ai-gateway:8085` | no — **dead/fictional target**: no service named `ai-gateway` exists in any compose file, and port 8085 is actually bound to `receipt-processor` (`services/receipt-processor/src/main/resources/application.yml:2`; also `services/api-gateway/src/main/resources/application.yml:68` routes `receipt` to `http://receipt-proc:8085`). This has never resolved to a working AI backend in any environment. |
| `app.service-token` | `SERVICE_TOKEN` | `internal-service-token` | **weak default credential**, and also see §6/§9: this literal string is sent as a `Bearer` token but is not a JWT, so it will fail `JwtAuthFilter` parsing on the receiving service regardless of value. |

`${VAR:-default}`-style placeholders present: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`,
`DB_PASS`, `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`,
`INGESTION_SERVICE_URL`, `AI_GATEWAY_URL`, `SERVICE_TOKEN`. Only `TROVE_JWT_SECRET` has no
default (fails closed, which is correct).

---

## 6. Outbound HTTP

| Target | Path | Headers set | Timeouts? | file:line |
|---|---|---|---|---|
| ingestion-service (`app.ingestion.url`) | `GET {url}/v1/transactions?user_id={userId}&date_from={from}[&date_to={to}]&limit=200&page={page}` | `Authorization: Bearer {app.service-token}` only | **No timeout configured** — the `RestClient` bean is `RestClient.create()` with defaults (`ReportAsyncConfig.java:68-70`); no `ClientHttpRequestFactory` with connect/read timeout is set anywhere in this module. | `generation/ReportDataFetcher.java:75-90` |
| AI gateway (`app.ai-gateway.url`) | `POST {url}/v1/complete`, body `{prompt, maxTokens:150, temperature:0.3}` | none beyond default `RestClient` headers (no `Authorization`, no service-token) | **No timeout configured** — `RestClient.create(aiGatewayUrl)` (`NarrativeSummaryService.java:262`), same defaults-only client. | `generation/NarrativeSummaryService.java:309-311` (call), `:262` (client construction) |
| MinIO (`trove.minio.endpoint`) | S3-style `PutObject`/`GetPresignedObjectUrl`/`RemoveObject` via the MinIO SDK, not `RestClient` | SDK-managed | SDK default (not explicitly configured in `MinioConfig`) | `storage/MinioStorageService.java:97-101` (upload), `:121-124` (presign), `:141` (delete) |

**Contract breaks against the real ingestion-service** (all independently verified against
`services/ingestion-service/src/main/java/finance/trove/ingestion/transaction/TransactionController.java`
in this audit):
1. **Identity mismatch.** Ingestion's `GET /v1/transactions` requires header
   `X-User-Id: <uuid>` (`TransactionController.java:73`, `required = true`) and does **not**
   read a `user_id` query parameter at all. `ReportDataFetcher` sends `user_id` as a query
   param and no `X-User-Id` header — ingestion returns 400 (or the request never even gets
   that far, see point 3).
2. **Query-param name mismatch.** Ingestion's date filters are `from`/`to`
   (`TransactionController.java:79,82`); `ReportDataFetcher` sends `date_from`/`date_to`
   (`ReportDataFetcher.java:75-76`). Also sends `page`, which ingestion's controller has no
   parameter for at all — ingestion has no pagination, it returns the full filtered list in
   one response.
3. **Auth breaks first.** `app.service-token` (`internal-service-token` by default) is sent
   as `Authorization: Bearer internal-service-token`. Ingestion-service imports the same
   shared `SecurityConfig`/`JwtAuthFilter` as report-service
   (`services/ingestion-service/src/main/java/finance/trove/ingestion/config/IngestionSecurityConfig.java`
   imports `SecurityConfig.class`), and that filter will fail to parse a non-JWT bearer
   string as a signed JWT, producing a 401 written by `JwtAuthFilter` itself — the request
   never reaches `TransactionController`, so points 1–2 above are moot in the default
   deployment; they would only matter if a real service-to-service JWT auth path were added.
4. **Envelope shape mismatch.** Ingestion's `getTransactions` returns a raw
   `List<Transaction>` (`TransactionController.java:70-86`, no `ApiResponse` wrapper). Even
   if auth were fixed, `ReportDataFetcher` expects `{"data":[...],"meta":{"total":...}}`
   (`ReportDataFetcher.java:94-95`: `response.path("meta").path("total")`,
   `response.path("data")`). Against a bare JSON array, `.path("meta")` and `.path("data")`
   both resolve to a `MissingNode`, so `data.isArray()` is false, `pageSize` stays `0`, and
   the fetch loop breaks after the very first page (`ReportDataFetcher.java:107-109`) —
   **every report is generated over zero transactions, silently, regardless of what
   ingestion actually has stored.** This chain is fully verified against source on both
   sides and is not conjecture; it corroborates dossier M7 exactly and the pattern is
   reproduced verbatim by the tests (see §9 — every WireMock stub in this module encodes the
   caller's imagined `{"data":[...],"meta":{...}}` shape, never the real one).

---

## 7. Scheduled/async, events

- No `@Scheduled` methods anywhere in this module (grep: zero hits).
- `@Async("reportExecutor")` — exactly one: `ReportGenerationService.generateAsync(UUID reportId, UUID userId)`
  (`generation/ReportGenerationService.java:65-117`). Not a per-user or per-row enumeration —
  it's invoked once per API request from `ReportService.requestReport`
  (`report/ReportService.java:65`), immediately after the `PENDING` row commits.
- No Spring application events (`ApplicationEventPublisher`, `@EventListener`) anywhere in
  this module.
- Async pool: core=2, max=4, queue=50, `AbortPolicy` (not `CallerRunsPolicy` — deliberate,
  documented rationale in `ReportAsyncConfig.java:52-56`: avoids blocking the HTTP thread).
  On rejection, `ReportService.requestReport` catches the `RejectedExecutionException` and
  immediately marks the already-persisted row `FAILED` (`ReportService.java:66-70`), so the
  caller always gets back a queryable report id — a genuinely well-designed failure path.

---

## 8. External dependencies

- **Postgres** — own database `trove_report` (own schema; no cross-service DB access; see §4).
- **MinIO** — sole object store for generated report files (`storage/MinioConfig.java`,
  `storage/MinioStorageService.java`). **Being dropped per the collapse decisions** (local
  disk volume replaces it) — see §11/§12 for the concrete rewrite this implies.
- **ingestion-service** — HTTP dependency for transaction data (§6); currently non-functional
  end-to-end (four independent contract breaks).
- **"ai-gateway"** — HTTP dependency for the optional PDF narrative
  (`NarrativeSummaryService`); the configured default URL points at a service that does not
  exist in any compose file (§5/§6). Under the collapse decisions this becomes a direct call
  to the user-configured OpenAI-compatible endpoint (off until configured), not a separate
  internal "ai-gateway" hop.
- No Redis, no SMTP, no SimpleFIN, no Authentik, no LiteLLM, no PaddleOCR dependency in this
  module.

---

## 9. Tests

**Counts by file** (`void .*Test method` count via grep, one class = one `@Test`-bearing file
unless noted):

| File | Test count | Type |
|---|---|---|
| `generation/CsvReportBuilderTest.java` | 6 | unit |
| `generation/NarrativeSummaryServiceTest.java` | 3 | unit (Mockito, fluent `RestClient` mock chain) |
| `generation/PdfReportBuilderTest.java` | 3 | unit |
| `generation/ReportDataFetcherTest.java` | 3 | unit (WireMock, standalone `WireMockServer`) |
| `generation/ReportGenerationServiceTest.java` | 5 | unit (Mockito `@InjectMocks`) |
| `generation/ReportStatusUpdaterTest.java` | 3 | unit (Mockito) |
| `report/ReportServiceTest.java` | 9 | unit (Mockito) |
| `OpenApiTest.java` | 1 | integration (`BaseIT`, Testcontainers + real MinIO container) — also **writes** `openapi/report-service.yaml` as a side effect of running (`OpenApiTest.java:30-32`), i.e. the checked-in OpenAPI spec is test-output, not hand-authored. |
| `ReportControllerIT.java` | 6 | integration (`BaseIT`) |

Total: **39 test methods** across 9 files. `BaseIT`/`JwtTestHelper` are shared support, not
test classes themselves.

**What they mock/inject:**
- Unit tests around `ReportGenerationService`, `ReportService`, `ReportStatusUpdater` use
  plain Mockito `@Mock`/`@InjectMocks` — no Spring context.
- `ReportDataFetcherTest` spins up a real standalone `WireMockServer` (not the
  `wiremock-spring-boot` annotation) and stubs `/v1/transactions` with the **fetcher's own
  imagined response shape** (`{"data":[...],"meta":{"total":...}}`) — this is the
  self-fulfilling-contract pattern dossier M10 describes: the stub matches the caller's code,
  not ingestion-service's actual controller (see §6 point 4). None of the four contract
  breaks in §6 are exercised or caught by this suite.
- `NarrativeSummaryServiceTest` mocks the fluent `RestClient` chain directly via
  `Mockito.mock(RestClient.class)` plus `ReflectionTestUtils.setField` to inject it, bypassing
  `@PostConstruct initClient()` entirely.
- `ReportControllerIT`/`OpenApiTest` extend `BaseIT`
  (`support/BaseIT.java`), which brings up **real Testcontainers Postgres and MinIO**
  (`BaseIT.java:23-36`) and a standalone WireMock instance wired as `app.ingestion.url`
  (`BaseIT.java:38,48`). **Positive exception to the platform-wide pattern in dossier M10**:
  these ITs mint real signed JWTs via `JwtTestHelper.tokenFor(userId)`
  (`support/JwtTestHelper.java:19-29`) and send them as real `Authorization: Bearer` headers
  through the actual `SecurityFilterChain`/`JwtAuthFilter` — unlike most other services'
  suites, this one does **not** use `@WithMockUser` / directly-injected `Authentication`, so
  it genuinely exercises the JWT verification path end-to-end. It still shares the same
  WireMock self-fulfilling-contract weakness for the ingestion-service boundary
  (`ReportControllerIT.java:34-36,69-71,107-109,140-142,165-168,202-208` — every stub uses
  `{"data":[...],"meta":{...}}`, `success` sometimes included, sometimes not, none matching
  the real bare-array response).

**Tests that assert a bug as correct:**
- `CsvReportBuilderTest.taxExport_includesGstAmountColumn` —
  `services/report-service/src/test/java/finance/trove/report/generation/CsvReportBuilderTest.java:47-56`
  asserts `records.get(0).get("gst_amount")).isEqualTo("10.00")` for a $200.00 transaction.
  `CsvReportBuilder.java:69` computes `tx.amount().multiply(GST_RATE)` where `GST_RATE =
  0.05` (`CsvReportBuilder.java:31`) — i.e. GST is computed as 5% **on top of** a
  tax-inclusive bank amount. The mathematically correct GST component of a $200.00
  GST-inclusive amount is `200 × 5/105 = 9.52`, not `10.00`. The test locks in the 5%
  overstatement as expected behavior. **Verified directly against source, not just the
  test** — this is a genuine arithmetic defect in `CsvReportBuilder`, independently
  confirmed (matches dossier M7).
- No test directly exercises `ANNUAL_SUMMARY` with two transactions in the **same**
  month+category (the one `build_annualSummary_hasMonthHeader` test at
  `CsvReportBuilderTest.java:69-83` uses two different categories, so it cannot reveal the
  missing-aggregation bug — it isn't a false-positive assertion of the bug, just a gap that
  happens not to exercise it). The bug itself is directly visible in source:
  `CsvReportBuilder.java:71-72` — `case ANNUAL_SUMMARY -> new Object[]{ YearMonth.from(tx.date()).toString(), tx.categoryName(), tx.amount(), 1}` emits one row per **raw transaction** with the count hardcoded to the literal `1` and `tx.amount()` un-summed, contradicting the type's own javadoc ("category-level spending rollup ... grouped by month and category", `ReportType.java:20-23`).

---

## 10. Verified defects

| id | Summary | file:line | Verified how |
|---|---|---|---|
| RS-1 | GST computed as `amount × 0.05` on a tax-inclusive amount instead of `amount × 5/105`; a 5% overstatement on every `TAX_EXPORT` row. Test locks it in as correct. | `generation/CsvReportBuilder.java:31,69`; asserted at `test/.../CsvReportBuilderTest.java:47-56` | Read source arithmetic directly; confirmed the test's expected value (`10.00`) matches the buggy formula, not the tax-inclusive-correct one (`9.52`). |
| RS-2 | `ANNUAL_SUMMARY` CSV does not aggregate — one row per raw transaction, `transaction_count` hardcoded to `1`, `total_spend` = the single transaction's amount, contradicting the type's own javadoc. | `generation/CsvReportBuilder.java:71-72`; `report/ReportType.java:20-23` (javadoc) | Read source directly; row-builder logic has no grouping/reduce step at all. |
| RS-3 | Report generation always operates on zero transactions: (a) `X-User-Id` header never sent (ingestion requires it), (b) query params `date_from`/`date_to`/`user_id`/`page` don't match ingestion's `from`/`to` (no pagination on ingestion's side, no `user_id`/`page` params at all), (c) `app.service-token`'s default value is not a valid JWT so ingestion's shared `JwtAuthFilter` 401s the request before any of the above matters, (d) even past auth, the expected `{"data":[...],"meta":{"total":...}}` envelope doesn't match ingestion's real bare `List<Transaction>` response, so the fetch loop silently returns an empty list. | `generation/ReportDataFetcher.java:75-95,107-109`; `services/ingestion-service/.../TransactionController.java:70-86` (real contract); `services/ingestion-service/.../IngestionSecurityConfig.java` (imports the same JwtAuthFilter) | Read both sides of the HTTP boundary directly; traced the JSON-path fallback behavior (`JsonNode.path()` on a mismatched shape returns `MissingNode`, `asInt(0)` defaults to 0, `isArray()` is false) to confirm silent-empty rather than an error. |
| RS-4 | `AI_GATEWAY_URL` default (`http://ai-gateway:8085`) points at a service name that exists in no compose file; port 8085 is actually bound to `receipt-processor`. The narrative feature has never had a reachable target in any documented environment. | `application.yml:44`; `services/receipt-processor/src/main/resources/application.yml:2`; `services/api-gateway/src/main/resources/application.yml:68` (routes `receipt` → `receipt-proc:8085`) | Grepped every compose file and every service's `application.yml` for `ai-gateway`; confirmed no such service/container name exists and that port 8085 is claimed by a different service. |
| RS-5 | `INGESTION_SERVICE_URL` default (`http://ingestion-service:8081`) resolves to a container name (`ingestion-service`) that the maintained `docker/docker-compose.yml` does not define — that compose file's ingestion container is named `ingestion-svc`. Only `infra/full-stack/` overrides this correctly. | `application.yml:42` | Confirmed as O4 in the prior dossier; independently re-checked the property still reads the unqualified default in this working tree. |
| RS-6 | User's private free-text `notes` are exported under the CSV header `description` in `TAX_EXPORT`, with no distinct "notes" column — private annotations become indistinguishable from a receipt/merchant description in an exported tax file. | `generation/CsvReportBuilder.java:58` (header `"description"` at index 2), `:67` (`tx.notes()` written to that column) | Read source directly; header array and row-builder array are positionally aligned — index 2 in both is confirmed by counting. |
| RS-7 | Presigned MinIO download URLs are generated from `trove.minio.endpoint` (default `http://minio:9000`, internal Docker DNS), so any URL returned to a browser via `GET /v1/reports/{id}` is unreachable from outside the Docker network — no public-endpoint override property exists in this module. | `storage/MinioStorageService.java:119-124`; `storage/MinioConfig.java:35-39`; `application.yml:35` | Confirmed there is exactly one `trove.minio.endpoint` property (no separate `public-endpoint`/`external-endpoint` key) used for both the SDK client and presigned-URL generation. |
| RS-8 | `report.requested_filters` JSONB column and the entire `ReportFilters` record are dead — nothing ever populates or passes real values into them; `ReportGenerationService` always calls `fetchTransactions(..., null)`. | `generation/ReportGenerationService.java:79`; `report/ReportService.java:58-63` (builder never sets `.requestedFilters`); `generation/ReportFilters.java` (javadoc admits "reserved for future use") | Grepped for `.requestedFilters(` and for non-null uses of `ReportFilters` — zero call sites set the field or construct a non-null-field instance. |
| RS-9 | `GET /v1/reports` pagination params `page`/`size` have no upper bound or validation (plain `int` with defaults, no `@Max`), allowing an authenticated caller to request an arbitrarily large page size. | `report/ReportController.java:91-93` | Read source; no `@Min`/`@Max`/`@Positive` annotation on either parameter, and `ReportService.listReports` passes them straight into `PageRequest.of(page, size)` with no clamping. |

---

## 11. Collapse recommendation

Target package root for kept code: `finance.trove.<domain>.<sub>` under the new `trove-core`
module (report generation becomes a domain package, not a service).

| Source package/class | Disposition | Destination / reason |
|---|---|---|
| `finance.trove.report.TroveReportApplication` | **DELETE** | Only `trove-core`'s single `@SpringBootApplication` survives the collapse; this class is redundant with 9 others. |
| `finance.trove.report.config.ReportAsyncConfig` | **KEEP (merge)** → `finance.trove.core.report.config` | The `reportExecutor` thread-pool bean and its rationale/rejection-policy comment are worth keeping verbatim; the bare `RestClient` bean should be **deleted** and replaced by whatever shared HTTP-client bean `trove-core` standardizes on for the user-configured AI endpoint (per the collapse decision — AI is a first-class per-user setting, not a fixed internal "ai-gateway"). |
| `finance.trove.report.config.ReportSecurityConfig` | **DELETE** | Trivial `@Import` wrapper around shared `libs/common` security classes; in a single-module monolith the security chain is declared once, not per-former-service. |
| `finance.trove.report.generation.CsvReportBuilder` | **KEEP with REWRITE** → `finance.trove.core.report.generation` | The CSV-column-layout mechanism is sound and directly reusable for the tax-time CSV export the owner wants to keep. Must fix RS-1 (GST math — and per the collapse decisions, GST/HST is Canada-only; a US-mode export must drop or replace this column entirely, since the tax engine and its jurisdiction logic are cut), RS-2 (implement real month+category aggregation for `ANNUAL_SUMMARY`, or rename/scope the type honestly), RS-6 (separate `notes` from `description`, or drop private notes from any tax export by default), and hardcoded `"CAD"` at `CsvReportBuilder.java:68,75` (see §12 — must become the user's per-user currency setting). |
| `finance.trove.report.generation.NarrativeSummaryService` | **KEEP with REWRITE** → `finance.trove.core.ai.report` (or similar) | Concept (best-effort AI narrative, empty-string fallback on any failure) is good; the fixed `app.ai-gateway.url` target must be replaced with the user-configured OpenAI-compatible endpoint config, off until configured (per the collapse decision), including proper timeouts (currently none) and the vision/chat client the monolith standardizes on elsewhere (avoid a second bespoke AI client). |
| `finance.trove.report.generation.PdfReportBuilder` | **KEEP** → `finance.trove.core.report.generation` | Self-contained iText builder, no external-service coupling, no identified defects; directly reusable for a PDF tax-time export if the owner wants a PDF option alongside CSV. |
| `finance.trove.report.generation.ReportDataFetcher` | **REWRITE** → `finance.trove.core.report.generation` | In a modular monolith this becomes a direct in-process repository/service call into the transactions domain, not an HTTP round-trip — this eliminates RS-3 entirely by construction (no header/query-param/envelope contract to drift). Delete the paginated-HTTP-fetch machinery outright. |
| `finance.trove.report.generation.ReportFilters` | **KEEP (trim)** → `finance.trove.core.report.generation` | Concept is fine (account/category scoping) but currently 100% dead (RS-8); either wire it up as part of the tax-export rewrite or delete it — don't carry forward unused surface. |
| `finance.trove.report.generation.ReportGenerationService` | **KEEP with REWRITE** → `finance.trove.core.report.generation` | The async pipeline shape (status transitions in `REQUIRES_NEW`, exceptions caught and mapped to `FAILED`, atomic complete-write) is genuinely well designed and worth preserving. Must swap `MinioStorageService` for the new local-disk storage service (per the collapse decision dropping MinIO) and swap `ReportDataFetcher`'s HTTP call for the in-process call above. |
| `finance.trove.report.generation.ReportStatusUpdater` | **KEEP** → `finance.trove.core.report.generation` | Small, correct, well-tested `REQUIRES_NEW`-transaction helper; no changes needed beyond the package move. |
| `finance.trove.report.generation.TransactionDto` | **DELETE** | Purely an HTTP-boundary DTO for deserializing ingestion-service's JSON. In-process, report generation should consume the real transaction domain entity/projection directly — no DTO translation layer needed. |
| `finance.trove.report.report.CreateReportRequest` | **KEEP** → `finance.trove.core.report` | Validated request record, no defects found. |
| `finance.trove.report.report.Report` (entity) | **KEEP** → `finance.trove.core.report` | Schema and entity are sound; see §12 for the table-name collision to check during merge. |
| `finance.trove.report.report.ReportController` | **KEEP with REWRITE** → `finance.trove.core.report` | Route shape (`POST` create, `GET` list/get, `DELETE`) and ownership-scoping pattern (`findByIdAndUserId`) are good and directly reusable. Fix RS-9 (bound `page`/`size`). Identity extraction (`@AuthenticationPrincipal TroveUserDetails`) is exactly the pattern the rest of the monolith should standardize on — this controller is a **good reference implementation** to generalize from, not the IDOR-prone `X-User-Id`-header pattern used elsewhere in the platform (dossier G1). |
| `finance.trove.report.report.ReportFormat` / `ReportStatus` / `ReportType` | **KEEP** → `finance.trove.core.report` | Plain enums, no defects. `ReportType.TAX_EXPORT` semantics need reconsidering given the tax-engine cut — likely renamed/repurposed as the tax-time export type described in the owner's decisions. |
| `finance.trove.report.report.ReportRepository` | **KEEP** → `finance.trove.core.report` | Two derived-query methods, both correct and user-scoped. |
| `finance.trove.report.report.ReportService` | **KEEP with REWRITE** → `finance.trove.core.report` | Deliberate non-`@Transactional` `requestReport` design (documented, correct rationale) is worth preserving as-is; swap `MinioStorageService` dependency for the new local-disk storage abstraction. |
| `finance.trove.report.report.ReportWithUrl` | **KEEP (rename)** → `finance.trove.core.report` | Concept survives; `downloadUrl`/`downloadUrlExpiresAt` naming can stay even after MinIO is replaced with local-disk-served files — worth revisiting the URL-signing mechanism, not just the storage backend, once MinIO's presign primitive is gone (see RS-7 and §12). |
| `finance.trove.report.storage.MinioConfig` | **DELETE** | MinIO is dropped per the collapse decisions; this is the config bean for the client being removed. |
| `finance.trove.report.storage.MinioStorageService` | **DELETE (duplicate)** | One of several near-identical `MinioStorageService` implementations across the ten services. Its upload/presign/delete responsibilities should be re-implemented once, in `trove-core`, as a local-disk-backed storage service (path-based, not S3-object-based) — not ported forward as-is. Note this copy already correctly imports `io.minio.Http.Method` (`storage/MinioStorageService.java:4`) — unlike receipt-processor's separate, still-broken `MinioStorageService` (dossier A1) — so it is not itself the compile-break instance, just still slated for deletion. |

**Summary counts:** KEEP as-is or with minor rename: 8 classes/records (`ReportAsyncConfig`
merge, `PdfReportBuilder`, `ReportFilters` trim, `ReportStatusUpdater`, `CreateReportRequest`,
`Report` entity, `ReportFormat`/`ReportStatus`/`ReportType`, `ReportRepository`,
`ReportWithUrl` rename). KEEP with REWRITE (real behavioral changes needed, not just a
package move): 6 (`CsvReportBuilder`, `NarrativeSummaryService`, `ReportDataFetcher`,
`ReportGenerationService`, `ReportService`, `ReportController`). DELETE: 5
(`TroveReportApplication`, `ReportSecurityConfig`, `TransactionDto`, `MinioConfig`,
`MinioStorageService`).

---

## 12. Merge risks / gotchas

- **`MinioStorageService` name collision.** This module's `finance.trove.report.storage.MinioStorageService`
  is one of at least two identically-named classes across the ten services (report-service and
  receipt-processor both have one; likely others). Both must be deleted in favor of a single
  new local-disk storage service in `trove-core` — do not let both survive under different
  packages, and do not silently pick one over the other without checking receipt-processor's
  usage patterns too (receipt image storage vs. generated-report storage may need slightly
  different semantics — content-addressed vs. `{userId}/{reportId}/{filename}`).
- **Bean-name / `@Import` clash risk.** `ReportSecurityConfig`'s `@Import({ SecurityConfig.class,
  JwtAuthFilter.class, JwtProperties.class, GlobalExceptionHandler.class,
  RequestIdMdcFilter.class })` is duplicated near-verbatim by 6+ other services' own
  `*SecurityConfig` classes (per dossier O2/M11 — this is the exact pattern that let
  model-manager silently ship with zero auth by not doing this import at all). In the
  collapse, this becomes **one** `@Import` in **one** place; every per-service
  `*SecurityConfig` class (this one included) must be deleted, not merged — keeping more than
  one produces duplicate bean definitions for `SecurityFilterChain`,
  `FilterRegistrationBean<RequestIdMdcFilter>`, etc., which Spring will refuse to start with.
- **Table-name collision risk.** The table is simply `report` (`report/Report.java:24`) — a
  very generic name. Cross-check the other 9 services' Flyway migrations for another table
  literally named `report` before merging schemas into one Postgres instance/schema (the
  "11 Postgres catalogs → one instance" consolidation, dossier O6, makes this a real
  collision surface, not a hypothetical one). Same caution applies to the two indexes
  `idx_report_user_id` / `idx_report_status` — generic enough names to collide.
- **Hardcoded `CAD` currency.** `CsvReportBuilder.java:68` and `:75` hardcode the literal
  string `"CAD"` into the `currency` column of every non-`ANNUAL_SUMMARY` CSV row, regardless
  of the transaction's actual currency or the user's onboarding currency setting. This is
  exactly the F1-class defect the dossier documents elsewhere (budget-service, subscription-
  detector, etc.) and must be fixed as part of the per-user CAD/USD currency work mandated by
  the collapse decisions — currently there is no `currency` field on `TransactionDto` at all
  (`generation/TransactionDto.java:31-40`) for this builder to read from even if it wanted to;
  the upstream transaction domain type will need to actually carry currency for this fix to
  be possible.
- **No `X-User-Id` reads anywhere in this module** — confirmed by direct grep. This is the
  one service in the platform that does NOT need remediation for the header-based IDOR
  pattern (dossier G1); it already derives identity from the verified JWT principal via
  `@AuthenticationPrincipal`. Treat `ReportController`'s identity pattern as the reference to
  standardize the rest of the monolith on, not something requiring a fix itself.
- **Hardcoded hostnames/ports to purge during merge**: `application.yml:44`
  (`http://ai-gateway:8085` — service doesn't exist), `application.yml:42`
  (`http://ingestion-service:8081` — wrong container name for the compose file the README
  points at), `application.yml:35` (`http://minio:9000` — being deleted entirely). None of
  these should carry forward as defaults into `trove-core`'s config — the monolith has no
  internal service-to-service hostnames to resolve at all once everything is one JVM process;
  only the AI endpoint (and, until replaced, any interim external dependency) needs a URL
  property, and it should be user-configured with no default, matching how `TROVE_JWT_SECRET`
  is already handled correctly in this file.
- **No static/global mutable state** found in this module (no `static` mutable fields beyond
  `BaseIT`'s test-only shared `WireMockServer wireMock` and Testcontainers instances, which do
  not ship to production).
- **Weak default credentials to eliminate, not carry forward**: `DB_USER`/`DB_PASS` default
  to `trove`/`trove` (`application.yml:10-11`); `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` default
  to `minioadmin`/`minioadmin` (`application.yml:36-37`, moot once MinIO is dropped);
  `SERVICE_TOKEN` defaults to the literal `internal-service-token`
  (`application.yml:45`, moot once the ingestion-service HTTP call becomes an in-process call
  per §11). Per the collapse decision to generate secrets rather than ship defaults, none of
  these three should survive into `trove-core`'s config in any form.
