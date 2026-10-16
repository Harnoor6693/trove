# ingestion-service — collapse inventory

> **Historical record, 2026-09-03.** Inventory of the pre-rebuild code, since removed. Current state: `docs-site/guide/architecture.md`.

All paths repo-relative from the repository root. Verified against source on 2026-09-03;
every defect in §10 was re-checked against the file:line cited, not carried over from `01-FINDINGS.md`
without re-verification.

## 1. Purpose and size

Owns the bank-account-linking and transaction-ledger domain: create/list/delete `ConnectedAccount`
rows, exchange a SimpleFIN setup token into accounts, run the sync pipeline (fetch → dedupe →
persist) via `SyncOrchestrator`, and expose read/filter/annotate access to the resulting
`Transaction` ledger plus a same-day/month spending dashboard. Encrypts plugin credentials at rest
with AES-256-GCM (`FieldEncryptionService`).

- LOC main: **2383** (`find services/ingestion-service/src/main -name '*.java' | xargs wc -l | tail -1`)
- LOC test: **1435** (`find services/ingestion-service/src/test -name '*.java' | xargs wc -l | tail -1`)

## 2. Spring wiring

- Application class: `finance.trove.ingestion.TroveIngestionApplication` (`@SpringBootApplication`,
  services/ingestion-service/src/main/java/finance/trove/ingestion/TroveIngestionApplication.java:20`)
  — plain `SpringApplication.run`, no extra auto-config exclusions. Javadoc on the class (lines 15-16)
  claims the service "trusts the X-User-Id header forwarded by the API gateway after JWT
  validation... does not perform JWT verification itself" — **this is stale/wrong**, see below.
- `IngestionSecurityConfig` (services/ingestion-service/src/main/java/finance/trove/ingestion/config/IngestionSecurityConfig.java:12-13):
  `@Configuration @Import({SecurityConfig.class, JwtAuthFilter.class, JwtProperties.class, GlobalExceptionHandler.class, RequestIdMdcFilter.class})`
  — all four imported types are from `libs/common` (`finance.trove.common.security.*`,
  `finance.trove.common.exception.GlobalExceptionHandler`, `finance.trove.common.observability.RequestIdMdcFilter`).
  Nothing service-specific is added; this class exists only to trigger the `@Import`.
- Security filter chain is entirely `libs/common`'s `SecurityConfig`
  (libs/common/src/main/java/finance/trove/common/security/SecurityConfig.java:58-88):
  CSRF disabled, STATELESS sessions, HSTS/CSP/frame-deny/referrer-policy/permissions-policy headers
  set, `JwtAuthFilter` + `RequestIdMdcFilter` inserted before `UsernamePasswordAuthenticationFilter`.
  **permitAll paths**: `/test/**`, `/actuator/health`, `/actuator/prometheus`, `/api/v1/auth/**`,
  `/api/v1/health`, `/api/health` (SecurityConfig.java:84-87). Everything else requires
  `anyRequest().authenticated()`.
  - `JwtAuthFilter` (libs/common/.../JwtAuthFilter.java) validates the `Authorization: Bearer` JWT
    (HS256, `trove.jwt.secret`), and on success populates `SecurityContextHolder` with a
    `TroveUserDetails(userId, email, roles)` principal built from the JWT `sub` claim
    (JwtAuthFilter.java:97-104). Paths under `/internal/` bypass the filter (line 79-81).
  - **Identity actually used by every controller in this module is NOT the JWT-derived
    principal.** Every controller re-derives `userId` from the raw client-supplied `X-User-Id`
    request header and never reads `SecurityContextHolder`/`TroveUserDetails`:
    - AccountController: services/ingestion-service/src/main/java/finance/trove/ingestion/account/AccountController.java:59,89,121
    - ExchangeSetupTokenController: services/ingestion-service/src/main/java/finance/trove/ingestion/account/ExchangeSetupTokenController.java:66
    - SyncController: services/ingestion-service/src/main/java/finance/trove/ingestion/sync/SyncController.java:63
    - TransactionController: services/ingestion-service/src/main/java/finance/trove/ingestion/transaction/TransactionController.java:73,118
    - DashboardController: services/ingestion-service/src/main/java/finance/trove/ingestion/dashboard/DashboardController.java:93 (optional header; missing → zeroed response, no 401)
    So the JWT filter only gates "is this request authenticated at all" (any valid Bearer token,
    for any user, passes); it never ties the acting identity to the token subject. This is the G1
    IDOR — see §10 D1.
- `JacksonConfig` (services/ingestion-service/src/main/java/finance/trove/ingestion/config/JacksonConfig.java) is an **empty**
  `@Configuration` class — no naming strategy, no module registration, nothing. Whatever Jackson
  naming policy is in effect is Spring Boot's default (camelCase, as declared on the DTOs/entities)
  — note this module uses `tools.jackson.databind.ObjectMapper` (Jackson 3 / "jackson-jr" style
  package, see `AccountService.java:2`, `SyncOrchestrator.java:2`), not the classic
  `com.fasterxml.jackson.databind.ObjectMapper` the `pom.xml` also pulls in
  (`com.fasterxml.jackson.core:jackson-databind`, pom.xml:64-67) — two Jackson major-version APIs
  are both on the classpath (see §12).
- `@EnableScheduling` / `@EnableAsync` / `@Scheduled` / `@Async`: **none found** anywhere in
  `services/ingestion-service/src/main` (`grep -rn "@Scheduled\|@EnableScheduling\|@EnableAsync\|@Async"` → no results).
  There is no automatic/periodic sync. The only ways a sync runs are: (a) `POST /v1/sync/{accountId}`
  called explicitly, or (b) once per created account inside `ExchangeSetupTokenService.exchange`
  right after account creation. See §7.

## 3. HTTP routes

| Method | Path | Controller#method | Identity source | Request DTO | Response DTO | Notes |
|---|---|---|---|---|---|---|
| GET | `/v1/accounts` | `AccountController#getAccounts` | `X-User-Id` header (required, raw UUID string) | — | `List<ConnectedAccount>` | Entity returned directly, incl. `configEncrypted` field unless Jackson `@JsonIgnore`'s it — **it does not** (see §10 D2). |
| POST | `/v1/accounts` | `AccountController#createAccount` | `X-User-Id` header (required) | `AccountService.CreateAccountRequest{sourcePlugin, displayName, institution, accountType, currency, config:Map<String,String>}` | `ConnectedAccount` (201) | `config` map is encrypted before storage but request DTO has no `@Valid`/bean-validation annotations (validation is inert anyway, see §11/G10 in prior audit — `spring-boot-starter-validation` is not a dependency of this module, pom.xml has no such artifact). |
| DELETE | `/v1/accounts/{id}` | `AccountController#deleteAccount` | `X-User-Id` header (required) + path `id` | — | 204 | 403 if account belongs to another user, 404 if absent — both go through `ResponseStatusException` (see §10 D1b, G11 masking). |
| POST | `/v1/accounts/exchange-setup-token` | `ExchangeSetupTokenController#exchange` | `X-User-Id` header (required) | `ExchangeSetupTokenRequest{pluginId, setupToken}` (`@Valid`, but no validation constraints observed on the record and no validation starter present) | `ExchangeSetupTokenResponse{accountIds:List<UUID>, syncLog:SyncLog}` (201) | Controller-local `@ExceptionHandler(SetupTokenExchangeException.class)` maps `SETUP_TOKEN_INVALID`/`PLUGIN_NOT_SUPPORTED`→400, `SETUP_TOKEN_ALREADY_EXCHANGED`→409, `BRIDGE_UNREACHABLE`→502 (ExchangeSetupTokenController.java:78-87), so this one path is NOT affected by the G11 500-masking (it never reaches `GlobalExceptionHandler`). |
| POST | `/v1/sync/{accountId}` | `SyncController#triggerSync` | `X-User-Id` header (required) + path `accountId` | — | `SyncLog` (200, even on `PARTIAL_FAILURE`) | Synchronous/blocking; runs the whole fetch+dedupe pipeline inline in the request thread. |
| GET | `/v1/transactions` | `TransactionController#getTransactions` | `X-User-Id` header (required) | query params: `limit` (1-500, optional), `sort` (`field,direction`, default `date,desc`), `from`/`to` (ISO date, optional), `type` (`TransactionType` enum, optional) | `List<Transaction>` | `sort` field name is passed straight into `Sort.by(...)` / JPA `Specification` — not validated against a field allowlist (would 500 on an unknown field via JPA metamodel error, not 400). Pagination is "first N" via `PageRequest.of(0, limit, sort)`, not true offset pagination — no `page`/`offset` param exists. |
| PATCH | `/v1/transactions/{id}` | `TransactionController#patchTransaction` | `X-User-Id` header (required) + path `id` | `TransactionService.PatchRequest{tagsJson, notes}` | `Transaction` | Only `tagsJson` and `notes` are patchable — no split/manual-entry endpoint exists anywhere in this module (see §12). Not-owned → 404 (not 403), intentionally, to avoid existence disclosure — but see G11 in §10, the actual HTTP status delivered is 500. |
| GET | `/v1/dashboard/today` | `DashboardController#getToday` | `X-User-Id` header (**optional** — missing/blank → zero-value response, no 401/400) | — | `DashboardController.DashboardResponse{date, spentToday, transactionsToday, spentThisMonth, incomeThisMonth, netThisMonth}` | Sums `BigDecimal` amounts **across all currencies without conversion** (doc comment admits this, DashboardController.java:47-48) — a user with both CAD and USD accounts gets a meaningless combined total. Also: if `X-User-Id` is present but not a valid UUID, `UUID.fromString` throws unguarded (no try/catch, unlike every other controller) — see §10 D5. |

Also present via `spring-boot-starter-actuator` + springdoc: `/actuator/health`, `/actuator/prometheus`,
`/actuator/metrics`, `/actuator/info` (management.endpoints.web.exposure.include, application.yml:20),
and springdoc's own `/v3/api-docs`, `/v3/api-docs.yaml`, `/swagger-ui/**` (no explicit config in
application.yml — defaults). All are behind the JWT filter except the two `permitAll` health paths
listed in §2 (`/v3/api-docs*` and `/swagger-ui/**` are **not** permitAll, confirmed by `OpenApiTest`
authenticating explicitly before hitting `/v3/api-docs.yaml`).

## 4. Persistence

Database name (from `application.yml`): `${INGESTION_DB_URL:jdbc:postgresql://localhost:5433/trove_ingestion}`
→ **`trove_ingestion`**. (Note: `.env.example` sets the same var to port `5432`, not `5433` — a
same-file inconsistency, not necessarily a bug since `.env.example` overrides the yml default; see §12.)

### Entities / tables

**`connected_account`** (`ConnectedAccount.java`, migration `V1__connected_account.sql`)
- `id UUID PK` (app-generated, `GenerationType.UUID`)
- `user_id UUID NOT NULL` — owner; only app-level filtering, no FK to a `users` table in this module
- `source_plugin VARCHAR(50) NOT NULL`
- `display_name VARCHAR(200) NOT NULL`
- `institution VARCHAR(200)` nullable
- `account_type VARCHAR(20) NOT NULL CHECK (... IN ('CHECKING','SAVINGS','CREDIT','INVESTMENT','OTHER'))`
  — **CHECK constraint omits `CREDIT_CARD`, the actual Java enum value** (`AccountType.java:9`
  declares `CHECKING, SAVINGS, CREDIT_CARD, INVESTMENT, OTHER`). See §10 D3.
- `currency VARCHAR(3) NOT NULL DEFAULT 'CAD'`
- `config_encrypted TEXT NOT NULL` — AES-256-GCM ciphertext, never returned/logged per doc contract
  (ConnectedAccount.java:70-88) but **is** returned in `GET /v1/accounts` responses since the whole
  entity is serialized with no `@JsonIgnore` — see §10 D2.
- `last_synced_at TIMESTAMPTZ` nullable
- `sync_status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (... IN ('PENDING','OK','ERROR'))` —
  entity enum `SyncStatus` also has `SYNCING` (SyncStatus.java:15, "reserved for future async use")
  which is never set by any code path, so the CHECK gap around `SYNCING` is currently latent/unused.
- `error_message TEXT` nullable
- `created_at`/`updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- Indexes: `idx_connected_account_user_id (user_id)`, `idx_connected_account_user_status (user_id, sync_status)`
- No unique index on `(user_id, source_plugin)` or similar — a user can link the same SimpleFIN
  bridge/account twice with no dedup at the account level (only transactions are deduped).

**`transaction`** (`Transaction.java`, migration `V2__transaction.sql`)
- `id UUID PK`
- `account_id UUID NOT NULL REFERENCES connected_account(id) ON DELETE CASCADE`
- `user_id UUID NOT NULL` — denormalised for user-scoped queries without a join
- `source_id VARCHAR(255)` nullable — plugin's external reference; **see M0/D6 in §10, all SimpleFIN
  rows currently get the literal constant `"simplefin"` here, not a per-transaction id**
- `amount DECIMAL(19,4) NOT NULL`
- `currency VARCHAR(3) NOT NULL DEFAULT 'CAD'`
- `type VARCHAR(10) NOT NULL CHECK (type IN ('DEBIT','CREDIT'))` — matches `TransactionType` enum
  values used in this module (no mismatch here, unlike `account_type`)
- `description TEXT`, `merchant_name VARCHAR(255)`
- `date DATE NOT NULL`, `posted_at TIMESTAMPTZ`, `pending BOOLEAN NOT NULL DEFAULT false`
- `category_id UUID` nullable — no FK (categorisation lives in another service)
- `tags_json TEXT`, `notes TEXT` — user enrichment, no schema validation on `tags_json` shape
- `created_at`/`updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- Indexes: `idx_transaction_account_id (account_id)`, `idx_transaction_user_id (user_id)`,
  `idx_transaction_date (date)`
- **Unique index**: `idx_transaction_source ON transaction(account_id, source_id) WHERE source_id IS NOT NULL`
  — this is the index that makes M0 catastrophic: once the first SimpleFIN transaction inserts
  `source_id = 'simplefin'` for an account, every subsequent transaction for that account collides
  on this unique index at the dedup-lookup level (`findByAccountIdAndSourceId`) before even
  reaching the DB constraint.

**`sync_log`** (`SyncLog.java`, migrations `V3__sync_log.sql` + `V4__sync_log_add_failed.sql`)
- `id UUID PK`
- `account_id UUID NOT NULL REFERENCES connected_account(id) ON DELETE CASCADE`
- `user_id UUID NOT NULL`
- `plugin_id VARCHAR(50)` nullable
- `status VARCHAR(10) NOT NULL DEFAULT 'RUNNING'` — **too short for `'PARTIAL_FAILURE'` (15 chars)**,
  see §10 D4 (a real, verified defect: `SyncOrchestrator` sets exactly this string and persists it
  unconditionally at the end of every `sync()` call).
- `transactions_fetched/new/updated/skipped INT NOT NULL DEFAULT 0` (V3)
- `transactions_failed INT NOT NULL DEFAULT 0` (added by V4, `ADD COLUMN IF NOT EXISTS`)
- `error_message TEXT` nullable
- `started_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `completed_at TIMESTAMPTZ` nullable
- Index: `idx_sync_log_account_id (account_id)`. No index on `user_id` despite `SyncLogRepository`
  javadoc claiming logs are "read by external tooling directly from PostgreSQL" — any such query
  filtering by user would seq-scan.

### Repositories with custom `@Query`
- `TransactionRepository.findFuzzyMatch` (TransactionRepository.java:73-77): JPQL,
  `WHERE t.accountId = :accountId AND t.pending = true AND t.date BETWEEN :dateFrom AND :dateTo
  AND t.amount BETWEEN :amountMin AND :amountMax` — the fuzzy-dedup lookup, parameterised (no
  injection risk).
- All other repository methods (`findByUserId`, `findByAccountIdAndSourceId`,
  `findByUserIdAndDateBetween` on `TransactionRepository`; `findByUserId` on `AccountRepository`)
  are derived Spring Data query methods, no custom SQL/JPQL.
- `TransactionRepository` also extends `JpaSpecificationExecutor<Transaction>` for the dynamic
  filter/sort/limit path used by `GET /v1/transactions`.

## 5. Config (application.yml + .env.example)

| Key | Env var | Default | Secret? |
|---|---|---|---|
| `server.port` | — | `8081` | no |
| `spring.datasource.url` | `INGESTION_DB_URL` | `jdbc:postgresql://localhost:5433/trove_ingestion` | no (but see .env.example inconsistency, port 5432 there) |
| `spring.datasource.username` | `INGESTION_DB_USER` | `trove` | **yes — default DB credential ships in code** |
| `spring.datasource.password` | `INGESTION_DB_PASS` | `trove` | **yes — default DB credential ships in code**, `.env.example` marks it `# CHANGE IN PRODUCTION` but the fallback is still live if unset |
| `spring.jpa.hibernate.ddl-auto` | — | `validate` | no |
| `spring.flyway.enabled` | — | `true` | no |
| `management.endpoints.web.exposure.include` | — | `health,info,prometheus,metrics` | no |
| `management.endpoint.health.show-details` | — | `when-authorized` | no |
| `management.metrics.export.prometheus.enabled` | — | `true` | no |
| `trove.jwt.secret` (bound in `libs/common` `JwtProperties`, consumed here via `@Import`) | `TROVE_JWT_SECRET` | none in this module's yml; `.env.example` ships `change-me-minimum-32-chars-in-production!!` | **yes — default secret, exactly 32 chars, satisfies the length check and is publicly known (matches prior-audit G5)** |
| `TROVE_ENCRYPTION_KEY` (read directly via `System.getenv`/`System.getProperty` in `FieldEncryptionService`, not a Spring `@ConfigurationProperties`) | `TROVE_ENCRYPTION_KEY` | none in application.yml; `.env.example` ships the literal placeholder `replace-with-32-byte-aes-key-hex-encoded` (not valid 64-hex-char, would fail `parseAndValidateKey` at startup if used verbatim — so this one at least fails closed rather than shipping a working weak key) | **yes — placeholder secret in `.env.example`** |
| `JAVA_TOOL_OPTIONS` (.env.example only, not read by app code) | `JAVA_TOOL_OPTIONS` | `-Xmx300m` | no |
| `SPRING_PROFILES_ACTIVE` (.env.example only) | `SPRING_PROFILES_ACTIVE` | `local` | no |

No `${VAR:-default}` shell-style placeholders in this module (that syntax is docker-compose's, not
Spring's `${VAR:default}`) — the two flagged secrets above are Spring `${VAR:default}` defaults
(`INGESTION_DB_USER`/`PASS`) plus the `.env.example`-shipped `TROVE_JWT_SECRET` value that
`libs/common`'s `JwtProperties`/`SecurityConfig` would pick up unless overridden — the *same*
default is documented in the prior audit's G5 as being repo-wide, and it is reachable from this
module via the shared `@Import`.

## 6. Outbound HTTP

Two outbound calls, both in the account-linking path; nothing else in this module makes an outbound
HTTP call.

| Target | Path | Headers set | Timeouts? | file:line |
|---|---|---|---|---|
| SimpleFIN bridge (setup URL, decoded from the user-supplied Base64 setup token — fully dynamic/attacker-influenced host, see G2 in prior audit) | `POST <decodedSetupUrl>` | `Content-Length: 0` only; no `Authorization` | **No** — `RestClient.builder().build()` with zero timeout configuration (uses JDK `HttpClient` defaults, effectively unbounded connect/read) | `services/ingestion-service/src/main/java/finance/trove/ingestion/account/HttpSetupTokenExchanger.java:28` |
| SimpleFIN bridge (access URL returned by the exchange above) | `GET <accessUrl>/accounts` (inside `plugins/bank-sources/simplefin`'s `SimplefinClient`, not in this module — out of scope for this file's grep but invoked from `ExchangeSetupTokenService.java:98` via `simplefinClient.fetchAccountSet(accessUrl)`) | not inspected here (belongs to `plugins/bank-sources/simplefin`) | not inspected here | `services/ingestion-service/src/main/java/finance/trove/ingestion/account/ExchangeSetupTokenService.java:98` (call site only) |

No call anywhere in this module sets `X-User-Id` or `Authorization` outbound — it only ever
*receives* `X-User-Id` inbound and never forwards identity to a downstream service (it has no
downstream service calls other than the SimpleFIN bridge above, which is not a Trove service).

## 7. Scheduled/async

- **No `@Scheduled` methods** — confirmed by repo-wide grep of `services/ingestion-service/src/main`
  for `@Scheduled|@EnableScheduling|@EnableAsync|@Async` (zero hits). There is no background sync
  scheduler in this module at all; "sync" only happens on-demand:
  1. `POST /v1/sync/{accountId}` (`SyncController.java:63` → `SyncOrchestrator.sync`)
  2. Once per newly-created SimpleFIN account, synchronously inline, inside
     `ExchangeSetupTokenService.exchange` (`ExchangeSetupTokenService.java:107-113`), which loops
     over every account SimpleFIN returns and calls `syncOrchestrator.sync(account.getId(), userId)`
     for each — meaning a setup-token exchange for a bridge with N accounts makes the HTTP request
     block for N sequential fetch+dedupe passes.
- No `@Async`, no `ApplicationEventPublisher`/`@EventListener` usage anywhere in this module.
- If an autonomous nightly/periodic sync is expected in the collapsed product, it does not exist
  today in this module and must be added new (not "found and moved").

## 8. External dependencies

- **Postgres** — database `trove_ingestion` (own catalog in the shared Postgres instance per O6 in
  the prior audit), via Flyway-managed schema (`ddl-auto: validate`).
- **SimpleFIN bridge** — external HTTP dependency for the `simplefin` plugin (setup-token exchange +
  account/transaction fetch). Config/credentials for it are per-`ConnectedAccount`
  (`config_encrypted`), not a service-wide setting.
- No MinIO, no Redis, no Ollama/LiteLLM, no PaddleOCR, no SMTP, no Authentik/OIDC client, no IMAP
  reference anywhere in this module's `src/main` or `pom.xml`. The module depends on exactly one
  plugin artifact: `finance.trove:plugin-bank-simplefin` (pom.xml:20-23) — `plugin-bank-ofx` and
  `plugin-bank-imap` are **not** on this service's classpath even though `PluginRegistry` is
  written generically to accept any number of `BankSourcePlugin` beans (PluginRegistry.java:22-25).

## 9. Tests

50 `@Test` methods total across 15 test classes (1435 LOC). Breakdown:
- Pure unit tests (Mockito, controller/service constructed directly, no Spring context): `AccountControllerTest` (3), `AccountServiceTest` (3), `DashboardControllerTest` (2), `DeduplicationServiceTest` (7), `ExchangeSetupTokenServiceTest` (4), `FieldEncryptionServiceTest` (6), `PluginRegistryTest` (2), `SyncControllerTest` (1), `SyncOrchestratorTest` (5), `TransactionControllerTest` (2), `TransactionServiceTest` (3) — **36 tests, none of which boot Spring MVC or the security/exception-handling chain.**
- `@WebMvcTest`: `SyncControllerWebMvcTest` (2) — loads only `SyncController` + its slice, explicitly
  excluding the security auto-configuration (`SyncControllerWebMvcTest.java:15`), so it exercises
  MockMvc argument-binding 400s but not `GlobalExceptionHandler` or `JwtAuthFilter`.
- Full `@SpringBootTest` + Testcontainers Postgres (`BaseIT`): `ExchangeSetupTokenControllerIT` (4),
  `OpenApiTest` (1), `TransactionControllerIT` (5) — **10 tests**, the only ones that exercise the
  real security filter chain, real Flyway migrations, and `GlobalExceptionHandler`.

What's mocked/injected:
- `SyncOrchestratorTest` mocks all six collaborators (`AccountRepository`, `SyncLogRepository`,
  `PluginRegistry`, `DeduplicationService`, `FieldEncryptionService`, real `ObjectMapper`).
- `DeduplicationServiceTest` mocks only `TransactionRepository`.
- `ExchangeSetupTokenControllerIT` mocks `SetupTokenExchanger` and `SimplefinClient` via
  `@MockitoBean` but runs against a real Testcontainers Postgres for persistence.
- `FieldEncryptionServiceTest` uses the package-private `FieldEncryptionService(String hexKey)`
  constructor directly — no Spring context.

Tests that mask/don't catch a real bug (verified):
- `SyncOrchestratorTest.deduplicationExceptionIncrementsFailedCountAndSetsPartialFailureStatus`
  (services/ingestion-service/src/test/java/finance/trove/ingestion/sync/SyncOrchestratorTest.java:159-176)
  asserts `finalLog.getStatus()).isEqualTo("PARTIAL_FAILURE")` succeeds — but `syncLogRepository`
  is a Mockito mock (`when(syncLogRepository.save(...)).thenAnswer(...)`), so the real Postgres
  `VARCHAR(10)` truncation for `"PARTIAL_FAILURE"` (15 chars, see §10 D4) is invisible to this test.
  No IT in this module ever drives a real dedup failure through `SyncOrchestrator` against the real
  Testcontainers Postgres, so D4 has zero test coverage anywhere in the suite.
- No test in this module (unit, WebMvcTest, or IT) ever sends a request where `X-User-Id` differs
  from the JWT's `sub` claim — every IT sets both to the same value (e.g.
  `TransactionControllerIT.java:91-93`, `ExchangeSetupTokenControllerIT.java:70-72`). The G1 IDOR
  path is therefore unverified-by-omission in this module's own suite (the prior audit's
  `TransactionControllerIT:144-149` citation does not match current line numbers in this file — the
  current file has 157 lines and no test crosses `X-User-Id`/JWT subject; treat that specific prior
  citation as stale and rely on the source-level confirmation in §10 D1 instead).
- No test creates a `ConnectedAccount` with `accountType = CREDIT_CARD` through a real (Testcontainers)
  repository save — `AccountServiceTest` only ever uses `"CHECKING"` (AccountServiceTest.java:50) and
  mocks `AccountRepository`, so the CHECK-constraint mismatch (§10 D3) is untested.
- `OpenApiTest` has a side effect beyond assertions: it writes the captured OpenAPI YAML to
  `../../openapi/ingestion-service.yaml` (OpenApiTest.java:33-34) — this is why that file shows as
  modified in git status; it is test-run output, not hand-maintained spec content.

## 10. Verified defects

| id | summary | file:line | verified how |
|---|---|---|---|
| D1 | **IDOR (= prior audit G1).** Every controller derives the acting `userId` from the client-supplied `X-User-Id` request header; the JWT filter (`libs/common` `JwtAuthFilter`) validates the token and populates `SecurityContextHolder` but nothing in this module ever reads that principal. Any request bearing *any* valid JWT plus an arbitrary `X-User-Id` UUID reads/mutates that other user's accounts, transactions, and triggers syncs on their accounts. | AccountController.java:59,89,121; TransactionController.java:73,118; SyncController.java:63; DashboardController.java:93; JwtAuthFilter.java:97-104 (sets principal, never consulted) | Read every controller's parameter list (`@RequestHeader("X-User-Id")`, never `@AuthenticationPrincipal`/`SecurityContextHolder`); read `JwtAuthFilter` to confirm it does populate a principal that goes unused; confirmed no IT in this module tests header/JWT mismatch (§9). |
| D2 | `GET /v1/accounts` returns the JPA entity `ConnectedAccount` directly as the response body, including `configEncrypted` (AES-256-GCM ciphertext of the user's bank credentials/API tokens). The class-level javadoc explicitly states this field "must not appear in any REST endpoint response... would be a CRITICAL SECURITY BREACH" (ConnectedAccount.java:75-78), but there is no `@JsonIgnore`, no response DTO, and no Jackson view applied — `AccountController.getAccounts` (`List<ConnectedAccount>`) and `createAccount` (`ConnectedAccount`) both serialize the full entity. | ConnectedAccount.java:70-96 (field + doc contract); AccountController.java:61-65 (`getAccounts` returns `List<ConnectedAccount>` from `accountService.getAccounts`, no mapping step); AccountController.java:99 (`createAccount` returns `ConnectedAccount` directly) | Read the entity for the field and its own documented invariant, then read both controller methods end-to-end and confirmed no DTO/mapping/`@JsonIgnore` exists anywhere between repository and HTTP response. Ciphertext, not plaintext, is exposed — still a defect against the class's own documented contract and unnecessary attack surface (offline brute force / key-compromise blast radius). |
| D3 | `connected_account.account_type` CHECK constraint allows `'CREDIT'` but the Java `AccountType` enum has `CREDIT_CARD` (no `CREDIT` value). Any attempt to persist an account with `accountType = CREDIT_CARD` fails the DB CHECK constraint (`DataIntegrityViolationException`) since Hibernate stores the enum name string `"CREDIT_CARD"`, which is not in the allowed set. | V1__connected_account.sql:7 (`CHECK (account_type IN ('CHECKING','SAVINGS','CREDIT','INVESTMENT','OTHER'))`) vs. AccountType.java:9 (`CHECKING, SAVINGS, CREDIT_CARD, INVESTMENT, OTHER`) | Directly diffed the migration's CHECK list against the enum's constant names; grepped the whole module + tests for any other reference to `CREDIT`/`CREDIT_CARD` and found no code path that reconciles them and no test that exercises `CREDIT_CARD` against a real Postgres constraint. |
| D4 | `sync_log.status` column is `VARCHAR(10)`, but `SyncOrchestrator.sync()` unconditionally sets and persists `log.setStatus("PARTIAL_FAILURE")` (15 characters) whenever any individual transaction fails during dedup/persist. The final `syncLogRepository.save(log)` call is **outside** the method's try/catch, so this insert/update, when it overflows the column, throws unhandled — surfacing to the caller (`SyncController`/`ExchangeSetupTokenService`) as an unhandled `DataIntegrityViolationException` rather than the documented HTTP 200 + `PARTIAL_FAILURE` body. | V3__sync_log.sql:6 (`status VARCHAR(10)`); SyncLog.java:55-56 (`@Column(nullable = false, length = 10)`); SyncOrchestrator.java (status set to `"PARTIAL_FAILURE"` when `failedCount > 0`, then `return syncLogRepository.save(log);` at the very end of `sync()`, unguarded) | Counted `"PARTIAL_FAILURE"` = 15 chars against the column's declared 10-char limit in both the Flyway migration and the JPA `@Column(length = 10)` annotation (Hibernate would also reject this at the ORM level independent of the DB). Confirmed the only test that exercises this path (`SyncOrchestratorTest.deduplicationExceptionIncrementsFailedCountAndSetsPartialFailureStatus`) mocks `SyncLogRepository` and so never touches a real column-length constraint (§9). |
| D5 | `DashboardController.getToday` parses `X-User-Id` with a bare `UUID.fromString(userIdHeader)` (no try/catch), unlike every other controller in this module which wraps the same parse in a private `parseUserId` helper that converts `IllegalArgumentException` into a `ResponseStatusException(BAD_REQUEST, ...)`. An invalid-but-non-blank `X-User-Id` here throws unguarded `IllegalArgumentException`, which `GlobalExceptionHandler.handleIllegalArgument` *does* map to 400 (so the end-user-visible behavior is arguably still 400) — but the controller's own `@ApiResponses` doc (`"400 X-User-Id header is present but not a valid UUID"`, DashboardController.java:81) is met only by accident of a shared library handler, not by this controller's own logic, and it is inconsistent with every sibling controller's explicit handling. | DashboardController.java:93-101 (no try/catch around `UUID.fromString`) vs. AccountController.java:132-139 / TransactionController.java:129-136 / SyncController.java:70-77 (all have a `parseUserId` try/catch) | Read all five controllers' identity-parsing code side by side; confirmed `DashboardController` is the sole outlier. |
| D6 | (Cross-reference of prior audit M0/O7 — re-verified from this module's side.) `DeduplicationService.deduplicate` performs its exact-match dedup key lookup on `incoming.sourceId()` (`DeduplicationService.java:63,111`), and the only plugin wired into this service, `SimplefinNormalizer` (`plugins/bank-sources/simplefin/src/main/java/finance/trove/plugin/simplefin/SimplefinNormalizer.java:53`), constructs every `RawTransaction` with the **constant literal `"simplefin"`** in the `sourceId` slot (2nd constructor arg) and the actual per-transaction SimpleFIN id (`tx.id()`) in the unrelated, never-read `id` slot (1st arg). Given the unique index `idx_transaction_source(account_id, source_id) WHERE source_id IS NOT NULL` (V2__transaction.sql:24), the first synced transaction per account inserts; every subsequent transaction from that account exact-matches `(accountId, "simplefin")` and is treated as a duplicate (SKIPPED, or UPDATED if pending→posted) — **at most one distinct transaction can ever exist per account** via this module's only wired plugin. | DeduplicationService.java:63-77 (exact-match step, keyed on `incoming.sourceId()`); SimplefinNormalizer.java:51-67 (`new RawTransaction(tx.id(), "simplefin", account.id(), ...)`); V2__transaction.sql:24 (unique index enforcing the collision) | Read `RawTransaction`'s field order (`id, sourceId, accountId, ...`) in `libs/common`, then read `SimplefinNormalizer.normalize` and confirmed the second constructor argument is the string literal `"simplefin"`, not `tx.id()`. Grepped this module for any other read of `RawTransaction.id()` — none exists in `dedup/` or `sync/`. This is the same defect as prior audit's M0, verified independently here from the ingestion-service + simplefin-plugin side (the module this service actually depends on — `plugin-bank-ofx`/`plugin-bank-imap` are not on this service's classpath, see §8, so only the SimpleFIN normalizer is in scope for this module's runtime behavior). |
| D7 | `HttpSetupTokenExchanger.exchange` builds `RestClient.builder().build()` with **no connect or read timeout configured**, for a POST to a URL that is fully attacker/user-controlled (decoded from the client-supplied Base64 setup token, see prior-audit G2 SSRF). A slow-loris or non-responding target host on this call blocks the request thread indefinitely (bounded only by JDK `HttpClient` platform defaults, which have no default read timeout). | HttpSetupTokenExchanger.java:28 (`RestClient client = RestClient.builder().build();`) | Read the full method; confirmed no `.requestFactory(...)`, no `ClientHttpRequestFactorySettings`, no `Duration` anywhere in the file or its imports. |
| D8 (minor/doc) | Class-level javadoc on `TroveIngestionApplication` states the service "trusts the X-User-Id header forwarded by the API gateway after JWT validation. It does not perform JWT verification itself" — this is factually wrong for the current wiring: `IngestionSecurityConfig` does `@Import(..., JwtAuthFilter.class, ...)` and the shared `SecurityConfig` bean does enforce JWT verification on every non-permitAll path (§2). The javadoc predates (or never matched) the current `@Import`-based wiring. | TroveIngestionApplication.java:15-16 vs. IngestionSecurityConfig.java:12-13 and SecurityConfig.java (JWT enforcement) | Directly contradicted by reading `IngestionSecurityConfig`'s `@Import` list and `SecurityConfig`'s filter chain, which does add `JwtAuthFilter` ahead of `UsernamePasswordAuthenticationFilter` and requires `anyRequest().authenticated()`. Flagging as a doc-accuracy defect (relevant to N-series findings in the prior audit) rather than a security defect — the actual security defect is D1, which is the *opposite* problem (JWT is checked for validity, but its subject is ignored). |

## 11. Collapse recommendation (package-by-package)

Target root for the monolith: `finance.trove.core.*`. Below, `finance.trove.core.ingestion.<sub>`
unless noted otherwise (transactions/accounts are core-domain enough that they likely want their own
top-level package rather than nesting under "ingestion" — recommendation given per package).

| Source package | Action | Destination | Reason |
|---|---|---|---|
| `finance.trove.ingestion` (`TroveIngestionApplication`) | DELETE | — | `@SpringBootApplication` entry point disappears; trove-core has exactly one. Fix the stale javadoc (D8) if any of its prose is reused in a package-info. |
| `finance.trove.ingestion.account` | KEEP (fix D1/D2/D3 first) | `finance.trove.core.accounts` | Core domain: `ConnectedAccount`, its repository/service, SimpleFIN setup-token exchange. Must be re-pointed to derive identity from the authenticated principal, not `X-User-Id` (D1), and `ConnectedAccount` must stop serializing `configEncrypted` (D2) — add a response DTO. `HttpSetupTokenExchanger` needs a timeout (D7). |
| `finance.trove.ingestion.transaction` | KEEP | `finance.trove.core.transactions` | Core domain: `Transaction` entity, filtered/paginated reads, tag/note patch. No split/manual-entry endpoint exists here to move (see §12 — must be built new if the collapsed product wants it). |
| `finance.trove.ingestion.sync` | KEEP (fix D6 upstream in the plugin, D4 in this package) | `finance.trove.core.sync` | `SyncOrchestrator`, `PluginRegistry`, `SyncLog`/`SyncLogRepository`, `SyncController`. `PluginRegistry`'s generic `List<BankSourcePlugin>` injection pattern is exactly the right shape for folding simplefin+ofx into core packages per the owner's plugin-architecture decision — keep the registry mechanism as-is, just widen the `pom.xml`/classpath to include the ofx plugin package once it's folded in. Widen `sync_log.status` column past 10 chars first (D4). |
| `finance.trove.ingestion.dedup` | KEEP (fix D6 at the normalizer, not here) | `finance.trove.core.sync.dedup` (or alongside `transactions`) | `DeduplicationService`'s two-pass exact+fuzzy logic is sound; the bug is upstream in `SimplefinNormalizer` passing a constant into `sourceId`. Recommend renaming `RawTransaction.sourceId` → `providerTransactionId` repo-wide as part of the fix (per prior audit O7) so the ambiguity can't recur when ofx is folded in next to simplefin. |
| `finance.trove.ingestion.dashboard` | KEEP (fix D5, and decide currency-summing semantics) | `finance.trove.core.dashboard` | Small, single-endpoint aggregation. Multi-currency summing-without-conversion (§3 GET /v1/dashboard/today notes) needs a product decision now that CAD/USD are both first-class per-user settings — likely: sum only in the user's chosen settlement currency, or return per-currency subtotals instead of one blended number. |
| `finance.trove.ingestion.encryption` (`FieldEncryptionService`) | KEEP, but **check for a duplicate** before merging | `finance.trove.core.crypto` (shared, not accounts-scoped) | AES-256-GCM field encryption is a cross-cutting concern other collapsing services (e.g. anything else that stores plugin/API credentials) likely also implement — grep the other 9 services for a same-named or same-purpose class before merging (`FieldEncryptionService` here reads `TROVE_ENCRYPTION_KEY` directly via `System.getenv`, bypassing Spring config binding entirely — any duplicate elsewhere may bind it differently, worth reconciling into one bean read once via `@ConfigurationProperties`). |
| `finance.trove.ingestion.config` (`IngestionSecurityConfig`, `JacksonConfig`) | DELETE both | — | `IngestionSecurityConfig` is a pure `@Import` shim around `libs/common` types that trove-core will import once, directly, at the application root — every one of the 10 services almost certainly has an identical shim (`XxxSecurityConfig` doing the same `@Import({SecurityConfig.class, JwtAuthFilter.class, JwtProperties.class, GlobalExceptionHandler.class, RequestIdMdcFilter.class})`); collapsing 10 of these into 1 top-level `@Import` is mechanical and required to avoid 10 duplicate `SecurityFilterChain` bean definitions colliding. `JacksonConfig` here is empty — delete unless another service's `JacksonConfig` actually configures something, in which case keep *that one* and drop this empty duplicate. |

**Classes that are themselves the shared/common pieces (imported, not owned, by this module)** —
confirm during merge that trove-core ends up with exactly one copy of each, sourced from
`libs/common`, not reintroduced by another service's local copy:
- `finance.trove.common.security.SecurityConfig` (the actual `SecurityFilterChain` bean)
- `finance.trove.common.security.JwtAuthFilter`
- `finance.trove.common.security.JwtProperties`
- `finance.trove.common.exception.GlobalExceptionHandler`
- `finance.trove.common.observability.RequestIdMdcFilter`

This module does **not** duplicate any of these itself (unlike the pattern the task description
warns about) — it only re-imports them via `IngestionSecurityConfig`. The risk is other services in
the collapse *do* carry local copies; that reconciliation happens at the trove-core root, not here.

**Tax engine**: no tax-related code exists anywhere in this module (no `Sa100PdfExporter`, no
calculators) — nothing to cut here.

## 12. Merge risks / gotchas

- **Bean-name / config clashes**: `IngestionSecurityConfig` and `JacksonConfig` are two of what are
  almost certainly ten near-identical `@Configuration` classes (one per service) importing the same
  five `libs/common` types. Naively including all ten in one Spring context will register the
  `SecurityConfig`/`JwtAuthFilter`/etc. beans ten times (or fail with duplicate-bean errors,
  depending on how each service's version differs) — this must collapse to a single `@Import` at the
  trove-core root, and each service's local `Xxx SecurityConfig`/`JacksonConfig` shim gets deleted
  (see §11).
- **Table-name clashes**: `connected_account`, `transaction`, `sync_log` are generic names likely to
  collide with tables of the same purpose-but-different-shape in other services once all 10
  databases become one Postgres catalog under trove-core (o6 in the prior audit already notes it's
  one Postgres process with 11 catalogs today — collapsing to one catalog is a new step). Check for
  a `transaction`/`transactions` table already owned by budget-service or categorisation before
  merging schemas; Flyway migration numbering (`V1`-`V4` here) will need a global renumbering
  scheme across all ten services' migration histories.
- **Currency hardcoded to `"CAD"`** (4 sites, all found and cited in §10's D6 table search plus
  here for completeness):
  - `ConnectedAccount.currency` field default: `ConnectedAccount.java:59`
  - `Transaction.currency` field default: `Transaction.java:63`
  - `ExchangeSetupTokenService.exchange`, SimpleFIN account-creation fallback when `sfAcct.currency()`
    is null: `ExchangeSetupTokenService.java:102`
  - `DeduplicationService.deduplicate`, new-transaction fallback when `incoming.currency()` is null:
    `DeduplicationService.java:111`
  - Plus the DB-level defaults: `V1__connected_account.sql:7` (`currency VARCHAR(3) NOT NULL DEFAULT 'CAD'`)
    and `V2__transaction.sql:8` (`currency VARCHAR(3) NOT NULL DEFAULT 'CAD'`).
  All six need to become "the user's chosen currency at onboarding" (CAD or USD) rather than a
  literal `'CAD'`, per the owner's fixed decision. None of these currently read any per-user setting
  — there is no user-settings lookup anywhere in this module.
- **`X-User-Id` reads**: every controller in this module reads it (§3 table, and D1 in §10). When
  folding into trove-core, this entire identity-derivation pattern must be replaced with reading the
  authenticated principal (`SecurityContextHolder`/`TroveUserDetails`, already populated correctly
  by `JwtAuthFilter` and simply never consulted) — this is not a "leave as-is" merge, it is a
  required fix to close D1, and it's mechanical (same change, six call sites, all in this module).
- **Static state**: none found — `FieldEncryptionService.keyBytes` is instance state read once at
  construction from the environment (not a static/singleton pattern beyond normal Spring
  singleton-scope), and `@PreDestroy` zeroes it. No other static mutable state in this module.
- **Hardcoded ports/hostnames**: `server.port: 8081` (application.yml:2) — irrelevant once folded
  into one JVM (trove-core picks one port). `.env.example`'s `INGESTION_DB_URL` default
  (`localhost:5432`) disagrees with `application.yml`'s default (`localhost:5433`) — neither
  matters once the datasource is unified in trove-core, but worth noting so nobody "fixes" one
  without checking the other assumed a docker-compose port-remap that no longer exists post-collapse.
- **Two Jackson major versions on the classpath**: this module's own code imports
  `tools.jackson.databind.ObjectMapper` (Jackson 3-style package, e.g. `AccountService.java:2`,
  `SyncOrchestrator.java:2`) while `pom.xml` also explicitly depends on
  `com.fasterxml.jackson.core:jackson-databind` (classic Jackson 2, pom.xml:64-67). Confirm which
  one trove-core's `spring-boot-starter-web` actually brings in transitively and drop the redundant
  explicit dependency during the merge — mixing both across the ten services being folded together
  is a likely source of `ClassNotFoundException`/serialization surprises if any two services picked
  different Jackson major versions independently.
- **No true pagination**: `GET /v1/transactions?limit=N` is "first N after sort/filter", not
  offset/cursor pagination (`TransactionService.getTransactions`, `PageRequest.of(0, limit, sort)` —
  page index is hardcoded `0`). If the collapsed product wants real pagination for large ledgers,
  this needs a new `page`/`cursor` parameter, not a lift-and-shift.
- **No split/manual-entry endpoint** exists anywhere in this module — the task's special-attention
  list asks to document it "if present"; it is not present. Only `tagsJson`/`notes` patching exists
  on `Transaction`. Any split-transaction or manual-entry feature in the collapsed product is new
  work, not a merge.
