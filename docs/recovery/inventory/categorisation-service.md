# Inventory: services/categorisation-service

Verified against source on 2026-09-03. Cross-referenced against `docs/recovery/01-FINDINGS.md`
(section O supersedes earlier sections). Existing defect IDs are cited, not re-numbered; new
observations specific to this module are marked NEW.

## 1. Purpose and LOC

Classifies bank transactions into a category (Groceries, Coffee Shops, …) by merchant name,
via a two-tier pipeline: deterministic rules (user override → exact DB match → prefix DB
match → in-memory community registry) then an LLM fallback, with a self-learning write-back
of confident AI results into the rules table. Also owns the `Category` CRUD API.

- LOC main: **1201** (`find services/categorisation-service/src/main -name '*.java' | xargs wc -l | tail -1`)
- LOC test: **673** (`find services/categorisation-service/src/test -name '*.java' | xargs wc -l | tail -1`)
- 9 main classes across 3 packages (`categorise`, `category`, `config`) + application class.

Note: `services/categorisation-service/README.md` is fiction (per `docs/recovery/01-FINDINGS.md`
§N1) — it names classes that don't exist in this module at all: `CategorizationService`
(real: `CategorisationService`), `TransactionCategory` enum (doesn't exist), `CategoryRule`
(doesn't exist), `AiCategoryClassifier` (real: `AiCategorisationService`),
`CategoryMappingRepository` (real: `MerchantCategoryMapRepository` /
`CategoryOverrideRepository`), `CategorizationResult` (real: `CategorisationResult`). Do not
use the README for anything.

## 2. Spring wiring

- Application class: `finance.trove.categorisation.TroveCategorisationApplication`
  (`@SpringBootApplication`, plain `SpringApplication.run`, no custom component scan —
  base package is `finance.trove.categorisation`).
- `@Configuration` / `@Import`: `config/CategorisationSecurityConfig.java:12` —
  `@Import({ SecurityConfig.class, JwtAuthFilter.class, JwtProperties.class,
  GlobalExceptionHandler.class, RequestIdMdcFilter.class })`, all four from `libs/common`.
  This module does **not** hand-roll its own copies of these classes (unlike the pattern
  flagged in O2/M1 for other services) — it correctly reuses the shared ones. But see §12:
  this is still one of 10 near-identical wrapper classes doing the same `@Import`.
- Auto-configuration actually active on the classpath:
  - `finance.trove.ai.gateway.AiGatewayAutoConfiguration` (from `ai-gateway-client`,
    `@AutoConfiguration`, registers `ModelRoutingService`, `LiteLlmRestClient`,
    `AiGatewayClient`; requires `trove.ai.service-token`).
  - `finance.trove.privacy.PrivacyAutoConfiguration` arrives transitively via
    `ai-gateway-client` (per O1) and registers `PrivacyEnforcementAspect` as a live bean
    that advises nothing (`@PrivacyEnforced` has zero usages) — dead weight, not a bug
    specific to this module.
  - Flyway auto-configuration (`spring.flyway.enabled: true`), Spring Data JPA, springdoc
    OpenAPI, Actuator.
  - **`finance.trove.common.config.JacksonConfig` (libs/common) is NOT active in this
    service.** It is a plain `@Configuration` (not an auto-configuration — libs/common ships
    no `META-INF/spring/...AutoConfiguration.imports`, confirmed per O2) living in package
    `finance.trove.common.config`, outside `TroveCategorisationApplication`'s scanned base
    package `finance.trove.categorisation`, and `CategorisationSecurityConfig`'s `@Import`
    list does not include it. Repo-wide grep confirms **no service** imports it; the one
    service that needs snake_case (`ingestion-service`) defines its own separate local
    `JacksonConfig` instead. Net effect: JSON in this service uses the Jackson/Spring Boot
    default (camelCase), **not** the snake_case naming strategy the shared class implies —
    NEW finding, specific instance of O2's general pattern. See §12 merge risk.
- Security filter chain (`libs/common` `SecurityConfig.filterChain`,
  `libs/common/src/main/java/finance/trove/common/security/SecurityConfig.java:58-91`):
  CSRF disabled, STATELESS sessions, security headers (CSP, X-Content-Type-Options, frame
  deny, HSTS 1yr+subdomains, referrer-policy, permissions-policy). `permitAll`:
  `/test/**`, `/actuator/health`, `/actuator/prometheus`, `/api/v1/auth/**`,
  `/api/v1/health`, `/api/health` (this module has none of the auth/health controller paths
  itself — those permitAll entries are inert here, just inherited boilerplate). Everything
  else `.anyRequest().authenticated()`.
- Identity derivation: `JwtAuthFilter` (`libs/common/.../JwtAuthFilter.java:83-126`) parses
  the `Authorization: Bearer` JWT (HS256), sets `SecurityContextHolder` from claim `sub` →
  `TroveUserDetails.userId`. **However, no controller in this module ever reads
  `SecurityContextHolder`.** `CategorisationController` and `CategoryController` never call
  it. Instead, `userId`/`transactionId` for categorisation and override calls come straight
  from the client-supplied JSON body
  (`CategorisationController.java:157,166` — `CategoriseRequest.userId()`,
  `OverrideRequest.userId()`). So: a valid JWT for *any* user is sufficient to categorise or
  set the override for *any other* `userId`/`transactionId` pair — the endpoint only checks
  "is this caller authenticated," never "is this caller who they claim in the body." This is
  NOT the `X-User-Id`-header IDOR pattern documented as G1 (that's a header trusted instead
  of the JWT); this is the JWT being validated but then ignored in favour of a
  body-supplied identity. NEW finding, cite as a merge risk in §12 and as a defect in §10.
  This module has no `X-User-Id` reads anywhere (`grep -rn "X-User-Id"` on
  `services/categorisation-service/src/main` returns nothing).
- `@EnableScheduling` / `@EnableAsync` / `@Scheduled` / `@Async` / application events: **none**
  anywhere in this module (verified by grep — zero hits).

## 3. HTTP routes

All controllers mounted at `/v1/...`; the api-gateway's `ProxyController` only forwards
`/api/v1/categories` → `categorisation` (`services/api-gateway/src/main/java/finance/trove/gateway/routing/ProxyController.java:67`). No gateway route entry exists for `/api/v1/categorise*` at all — confirms D4's "not routed by the gateway."

| METHOD | Path | Controller#method | Identity source | Request DTO | Response DTO | Notes |
|---|---|---|---|---|---|---|
| POST | `/v1/categorise` | `CategorisationController#categorise` (`CategorisationController.java:71`) | JWT required (any user) but `userId`/`transactionId` taken from body, unchecked against JWT `sub` — see §2 | `CategoriseRequest(userId, transactionId, merchantName)` (`:157`) | `Category` (full JPA entity) | 400 if `merchantName` null/blank; not gateway-routed |
| POST | `/v1/categorise/batch` | `CategorisationController#categoriseBatch` (`:107`) | same as above, per-item | `List<CategoriseRequest>` | `List<Category>` | Max 100 items (`MAX_BATCH_SIZE`, `:32`); 422 if `>100`; 400 if any blank `merchantName`; no pagination — synchronous full-batch processing; not gateway-routed |
| PUT | `/v1/categorise/override` | `CategorisationController#storeOverride` (`:143`) | same as above | `OverrideRequest(userId, transactionId, categoryId)` (`:166`) | 200, empty body | Upsert on `(userId, transactionId)`; 400 if `categoryId` unknown (via `GlobalExceptionHandler` mapping `IllegalArgumentException`→400); not gateway-routed |
| GET | `/v1/categories` | `CategoryController#getAllCategories` (`CategoryController.java:69`) | JWT required only | none | `List<Category>` | Returns **200 with empty list**, not an error, on any repository exception (`:73-76`) — swallows DB outages silently; no paging/filtering, returns entire table every call; gateway-routed |
| POST | `/v1/categories` | `CategoryController#createCategory` (`:100`) | JWT required only | `Category` (the **JPA entity itself** as `@RequestBody`) | `Category` | `system` flag forced `false` server-side, but `id` is attacker-controlled in the body → posting an existing system category's id performs a JPA merge, overwriting it (this is G3, mass assignment); no `spring-boot-starter-validation` dependency in `pom.xml` (G10) so no bean validation is possible even if `@Valid` were added; gateway-routed |
| DELETE | `/v1/categories/{id}` | `CategoryController#deleteCategory` (`:128`) | JWT required only | path var `id` (UUID) | 204/400/404 | 404 if missing, 400 if `isSystem()`; two separate `findById` calls (`:130,133`) instead of one — not a correctness bug, just wasteful; gateway-routed |

## 4. Persistence

Database (from `application.yml:9`): **`trove_categorisation`** (one catalog in the shared
Postgres instance — see O6, "11 databases" is one Postgres process with 11 catalogs).

### Entities / tables

- **`category`** (`Category.java`, table `category`) — `V1__category.sql:1-8`
  - `id UUID PK` (gen_random_uuid), `name VARCHAR(100) NOT NULL UNIQUE`,
    `parent_id UUID FK → category(id)` (self-referential, app-level cycle guard in
    `Category.setParent`/`wouldCreateCycle`, `Category.java:97-117` — no DB-level cycle
    constraint), `icon VARCHAR(50)`, `is_system BOOLEAN NOT NULL DEFAULT true`,
    `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
  - Seeded with **51 system rows** in `V1__category.sql:10-60` (Food & Dining … Uncategorised).
    `Uncategorised` is the mandatory pipeline fallback; if this row is ever deleted,
    `CategorisationService.categorise` throws `IllegalStateException`
    (`CategorisationService.java:177-179`).
- **`merchant_category_map`** (`MerchantCategoryMap.java`, table `merchant_category_map`) —
  `V2__merchant_category_map.sql:1-9`
  - `id UUID PK`, `merchant VARCHAR(255) NOT NULL`,
    `match_type VARCHAR(10) NOT NULL CHECK (match_type IN ('EXACT','PREFIX'))`,
    `category_id UUID NOT NULL FK → category(id)`,
    `confidence DECIMAL(4,3) NOT NULL DEFAULT 0.90`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
  - Index: `idx_merchant_map_merchant` on `(merchant)` only — not a composite index on
    `(merchant, match_type)`, so both repository queries do an index scan on `merchant` then
    filter `match_type` and `confidence` in the executor; fine at this data volume, not
    exhaustively assessed.
  - **Ships with zero rows.** No migration seeds it (confirmed: `V2` has no `INSERT`). The
    "community merchant registry" that IS seeded (20 merchants — Tim Hortons, Starbucks,
    McDonald's, …) lives only in `resources/community-merchant-registry.json` and is loaded
    into an **in-memory `HashMap`** by `CategorisationService.loadCommunityRegistry`
    (`@PostConstruct`, `CategorisationService.java:82-105`) — it is never written to this
    table. The table only ever gets rows from the AI self-learning write-back path, which is
    itself broken (D4, see below). So in practice this table is permanently empty in
    production.
- **`category_override`** (`CategoryOverride.java`, table `category_override`) —
  `V3__category_override.sql:1-8`
  - `id UUID PK`, `user_id UUID NOT NULL`, `transaction_id UUID NOT NULL`,
    `category_id UUID NOT NULL FK → category(id)`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`,
    `UNIQUE (user_id, transaction_id)`.
  - No FK to any `transaction` table (transactions live in ingestion-service's own catalog —
    cross-catalog FK is impossible under the current one-database-per-service split; O6).

### Repositories with custom queries

- `CategoryRepository.findByName(String)` — derived query, case-sensitive per DB collation
  (`CategoryRepository.java:26`).
- `MerchantCategoryMapRepository.findFirstByMerchantAndMatchTypeAndConfidenceGreaterThanEqualOrderByConfidenceDesc`
  (`MerchantCategoryMapRepository.java:30-31`) — correct EXACT-match lookup.
- `MerchantCategoryMapRepository.findByMerchantStartingWithAndMatchTypeAndConfidenceGreaterThanEqualOrderByConfidenceDesc`
  (`MerchantCategoryMapRepository.java:43-44`) — **INVERTED prefix query (D4).** Spring Data's
  `StartingWith` keyword generates `WHERE merchant LIKE CONCAT(?1, '%')`, i.e. it requires the
  **stored** `merchant` column value to start with the supplied argument. The pipeline calls
  it with `prefix = cleaned` = the *full* normalised merchant name (e.g. `"APPLE STORE"`,
  `CategorisationService.java:150-152`), while stored PREFIX rows are meant to hold *short*
  prefixes (e.g. `"APPLE"`, per the entity's own javadoc, `MerchantCategoryMap.java:22-25`, and
  the repository's own javadoc, `MerchantCategoryMapRepository.java:33-42`, which says the
  intent is "stored merchant value is a leading substring of the given normalised merchant
  name"). The actual generated SQL requires the reverse relationship (column starts with
  parameter), so a stored `"APPLE"` row will **never** match a lookup for `"APPLE STORE"` — the
  query only matches when the stored value is *longer than or equal to* the lookup string and
  literally starts with it, which for a genuine "prefix" table essentially never occurs. PREFIX
  matching is dead code in practice. Verified by reading the Spring Data `StartingWith`
  keyword semantics against the argument order at the call site (`CategorisationService.java:150-152`) and the javadoc's stated intent. `CategorisationServiceTest.prefixDbMatchReturnsCategoryWithoutCallingAi`
  (`CategorisationServiceTest.java:145-169`) does **not** catch this because it mocks the
  repository method directly and asserts on the mocked return value — it never exercises the
  real derived-query SQL, so it cannot detect the direction inversion. This is the closest
  thing in this module's test suite to "a test that would pass either way" — see §9.

## 5. Config (`application.yml` + `.env.example`)

| Key | Env var | Default | Secret? |
|---|---|---|---|
| `server.port` | — | `8082` | no |
| `spring.application.name` | — | `categorisation-service` | no |
| `spring.profiles.default` | — | `local` | no — but note: no `application-local.yml` or `application-prod.yml` exists in this module, so this key and the Docker entrypoint's `-Dspring.profiles.active=prod` currently select no profile-specific overrides at all (single `application.yml` used regardless) |
| `spring.datasource.url` | `CATEGORISATION_DB_URL` | `jdbc:postgresql://localhost:5434/trove_categorisation` (`application.yml:9`) | no, but **inconsistent with `.env.example:6`**, which documents `jdbc:postgresql://localhost:5432/trove_categorisation` — port 5434 vs 5432, NEW finding, flag in §12 |
| `spring.datasource.username` | `CATEGORISATION_DB_USER` | `trove` | not secret but a shared cross-service default |
| `spring.datasource.password` | `CATEGORISATION_DB_PASS` | `trove` | **YES — default secret**, `.env.example:8` marks it `# CHANGE IN PRODUCTION` but ships it as the wired default |
| `spring.jpa.hibernate.ddl-auto` | — | `validate` | no |
| `spring.flyway.enabled` | — | `true` | no |
| `management.endpoints.web.exposure.include` | — | `health,info,prometheus,metrics` | no |
| `management.endpoint.health.show-details` | — | `when-authorized` | no |
| `management.metrics.export.prometheus.enabled` | — | `true` | no |
| `trove.jwt.secret` (bound via relaxed env binding, not present in this module's `application.yml`; class default in `libs/common` `JwtProperties.java:38` is `null`, `@PostConstruct` rejects blank/<32 chars) | `TROVE_JWT_SECRET` | `.env.example:10` ships `change-me-minimum-32-chars-in-production!!` | **YES — default secret**, already flagged repo-wide as G5 |
| `trove.jwt.expiry-seconds` | — (class default) | `900` | no |
| `trove.jwt.refresh-expiry-seconds` | — (class default) | `86400` | no |
| `trove.ai.model-manager-url` | — (class default, `AiGatewayProperties.java:35`) | `http://model-manager:8089` | no |
| `trove.ai.timeout-seconds` | — (class default) | `30` | no |
| `trove.ai.retry-attempts` | — (class default) | `2` | no |
| `trove.ai.retry-delay-ms` | — (class default) | `500` | no |
| `trove.ai.local-fast-model` | — (class default) | `llama3.2:3b` | no |
| `trove.ai.local-quality-model` | — (class default) | `llama3.1:8b` | no |
| `trove.ai.hybrid-model` | — (class default) | `llama3.1:8b` | no |
| `trove.ai.cloud-model` | — (class default) | `gpt-4o-mini` | no |
| `trove.ai.service-token` | `TROVE_AI_SERVICE_TOKEN` | `.env.example:11` ships `dev-ai-service-token` | **YES — default secret**, and NEW finding: this token is validated non-blank at startup (`AiGatewayProperties.validate()`) but is **never actually sent anywhere** — grepped `libs/ai-gateway-client` for `serviceToken`/`Authorization` usage in outbound calls: it is read and stored but never attached as a header to the `model-manager` call (`LiteLlmRestClient.complete`, headers set are only `X-Request-Id`/`X-Privacy-Tier`/`Content-Type`). Dead config, mandatory at startup for no functional purpose. |
| `JAVA_TOOL_OPTIONS` | — | `.env.example:3` `-Xmx300m` (dev only) | no — conflicts with Dockerfile prod entrypoint's `-XX:MaxRAMPercentage=75.0` with no container memory limit (O5) |

No `application-local.yml`/`application-prod.yml`/`application-test.yml` files exist in this
module; `BaseIT` (`support/BaseIT.java:27-36`) overrides `spring.datasource.*`,
`trove.jwt.secret`, and `trove.ai.service-token` directly via `@DynamicPropertySource` rather
than a profile file.

## 6. Outbound HTTP

Only one outbound call chain in this module, through `libs/ai-gateway-client`:

- `AiCategorisationService.categorise` (`AiCategorisationService.java:76-112`) →
  `AiGatewayClient.complete` → `LiteLlmRestClient.complete`
  (`libs/ai-gateway-client/src/main/java/finance/trove/ai/gateway/LiteLlmRestClient.java:60-77`)
  - Target: `POST {trove.ai.model-manager-url}/v1/chat/completions`, default host
    `http://model-manager:8089`.
  - Headers set: `X-Request-Id` (a fresh `UUID.randomUUID()` generated per-call in
    `AiCategorisationService.java:85`, **not** the inbound request's correlation id from
    `RequestIdMdcFilter`), `X-Privacy-Tier: HIGH`, `Content-Type: application/json`. **No
    `Authorization` header, no `X-User-Id` header** — despite `trove.ai.service-token` being
    mandatory config, it is never sent (see §5).
  - Timeouts: **none configured.** `LiteLlmRestClient`'s constructor
    (`LiteLlmRestClient.java:52-56`) builds `RestClient.builder().baseUrl(...).build()` with no
    `ClientHttpRequestFactory`/connect-or-read timeout set, so it relies on the JDK's default
    HTTP client behavior (effectively unbounded read timeout). `trove.ai.timeout-seconds`
    (default 30) exists as a property but is never wired into the `RestClient` — it appears
    unused by `LiteLlmRestClient` itself (retry delay uses `retryDelayMs`, not
    `timeoutSeconds`). Not fully re-verified beyond this file — flagged for the ai-gateway-client
    inventory to confirm.
  - Retries: up to `retryAttempts` (default 2) with `retryDelayMs` (default 500ms) between
    attempts on transient errors only (`LiteLlmRestClient.java` — 4xx and JSON-parse failures
    return immediately without retry, per its own javadoc).

## 7. Scheduled/async

None. No `@Scheduled`, `@EnableScheduling`, `@Async`, `@EnableAsync`, or application
events/listeners anywhere in `services/categorisation-service/src/main` (verified by grep,
zero hits).

## 8. External dependencies

- **Postgres** — `trove_categorisation` catalog (own schema: `category`,
  `merchant_category_map`, `category_override`).
- **model-manager / LiteLLM** — via `ai-gateway-client`, for the AI categorisation fallback
  (off the deterministic fast path). Not required for the rule-based tiers to function.
- No MinIO, no Redis, no SMTP, no SimpleFIN, no Authentik, no PaddleOCR, no direct LiteLLM
  call (goes through model-manager as a proxy per `AiGatewayProperties` javadoc, not called
  directly).

## 9. Tests

26 test methods across 8 classes:

- `OpenApiTest` (1 test) — full `@SpringBootTest` + Testcontainers Postgres
  (`support/BaseIT`), asserts `/v3/api-docs.yaml` is served and writes it to
  `openapi/categorisation-service.yaml`. Only genuinely end-to-end test in the module (still
  mocks nothing but doesn't exercise the categorisation pipeline).
- `CategorisationControllerTest` (4 tests) — `@WebMvcTest`, `CategorisationService` mocked via
  `@MockitoBean`. Covers 200 single, 200 batch@100, 422 batch@101, 200 override. Does not test
  the 400 blank-merchantName path or verify the identity-trust issue from §2.
- `CategorisationServiceTest` (6 tests) — pure Mockito unit tests, all four repositories/AI
  service mocked, service instantiated directly (`new CategorisationService(...)`, bypassing
  any Spring AOP proxy). Covers override-short-circuit, exact match, prefix match, community
  registry match, AI fallback + persist, and full fallback to Uncategorised.
  - `noMatchCallsAiAndPersistsToMerchantMap`
    (`CategorisationServiceTest.java:97-123`) asserts `merchantMapRepository.save(...)` is
    called with the right merchant/matchType. **This does not, and cannot, catch D4** (the
    readOnly self-invocation bug) because the test constructs `CategorisationService` with
    `new` rather than through a Spring `ApplicationContext`, so there is no transactional
    proxy in play at all — `persistMerchantMapping`'s `@Transactional` annotation is simply
    never evaluated in either direction, mocked or real. The test's green result gives no
    signal about whether the write survives in production. See §10, D4.
  - `prefixDbMatchReturnsCategoryWithoutCallingAi`
    (`CategorisationServiceTest.java:145-169`) mocks
    `findByMerchantStartingWithAndMatchTypeAndConfidenceGreaterThanEqualOrderByConfidenceDesc`
    to directly return a match — it stubs the *outcome* of the query rather than exercising the
    real derived SQL, so it is structurally incapable of catching the inverted-prefix bug (D4).
    Not "asserting the bug as correct" in the sense of testing wrong behaviour on purpose, but
    it is a false-confidence test for this specific defect.
- `MerchantCategoryMapTest`, `CategoryOverrideTest`, `CategoryTest` (1, 1, 2 tests) — plain
  getter/setter POJO tests, no business logic exercised.
- `AiCategorisationServiceTest` (5 tests) — Mockito unit tests against `AiGatewayClient`;
  covers success parse, AI failure → empty, invalid JSON → empty, missing fields → empty, and
  an `AiResponse.success(null,...)` construction-time `IllegalStateException` (a sanity check
  on the shared DTO, not this service's own logic).
- `CategoryControllerTest` (6 tests) — `@WebMvcTest`, `CategoryRepository` mocked. Covers list,
  list-on-DB-error→200-empty (asserts the swallow-and-200 behaviour as intended, not a
  masked bug — this is deliberate per the controller's own javadoc, just a design choice
  worth flagging separately as an availability/observability smell, not a "bug asserted as
  correct"), create, delete-404, delete-400-system, delete-204-user. `createCategory` test
  posts only `{"name":"New Category"}` — does **not** exercise the mass-assignment path
  (posting an `id`), so G3 is untested here too.

No test in the suite exercises `/v1/categorise*` through the api-gateway (there is no gateway
route to exercise, D4), and no test asserts that `userId` in the request body is checked
against the JWT principal — consistent with that check not existing (§2).

## 10. Verified defects

| id | summary | file:line | verified how |
|---|---|---|---|
| D4 (existing, re-verified) | Self-learning write-back is dead: `categorise` is `@Transactional(readOnly=true)` and calls `persistMerchantMapping` by self-invocation (same-class call, not through the Spring AOP proxy), so the inner `@Transactional` never applies; the insert runs (if it runs at all) inside the outer read-only transaction and fails, and the `catch (Exception e)` downgrades it to a `LOG.warn` — every categorisation of an unknown merchant re-runs the LLM forever. | `CategorisationService.java:127` (`@Transactional(readOnly = true)` on `categorise`), `CategorisationService.java:167-174` (self-invocation call to `persistMerchantMapping`), `CategorisationService.java:220-232` (`persistMerchantMapping`'s own `@Transactional` + swallowed catch) | Read source directly: confirmed `categorise` and `persistMerchantMapping` are both instance methods of the same class, `categorise` calls `persistMerchantMapping` via a bare (unqualified `this`) method call — classic Spring self-invocation AOP bypass. Confirmed the outer method's `readOnly=true` annotation. Confirmed the try/catch swallows the resulting exception into a warn log. |
| D4 (existing, re-verified) | Prefix query direction is inverted vs. its own javadoc: `findByMerchantStartingWithAndMatchTypeAndConfidenceGreaterThanEqualOrderByConfidenceDesc` generates `WHERE merchant LIKE CONCAT(?1,'%')` (stored value must start with the argument), but is called with the *full* normalised merchant name as the argument while stored rows are meant to hold *short* prefixes — so a stored `"APPLE"` prefix row never matches a lookup for `"APPLE STORE"`. | `MerchantCategoryMapRepository.java:43-44` (query method + its own javadoc at `:33-42` describing the intended, opposite, semantics); call site `CategorisationService.java:150-152` | Read the Spring Data `StartingWith` keyword semantics against the derived method name and compared to the call-site argument order and the repository/entity javadocs' stated intent (`MerchantCategoryMap.java:22-25`). |
| D4 (existing, re-verified) | `merchant_category_map` ships with zero rows — no Flyway migration seeds it; only the JSON-backed in-memory community registry (20 entries) is seeded, and it lives entirely outside this table. | `db/migration/V2__merchant_category_map.sql` (no `INSERT` statements, only `CREATE TABLE`/`CREATE INDEX`); `CategorisationService.java:82-105` (`loadCommunityRegistry`, in-memory `HashMap`, classpath resource, never touches the repository) | Read the migration file in full; confirmed no seed rows. Read `loadCommunityRegistry` in full; confirmed it populates a `Map<String,CommunityEntry>` field, never calls `merchantMapRepository`. |
| D4 (existing, re-verified) | `/v1/categorise*` is not routed by the api-gateway at all. | `services/api-gateway/src/main/java/finance/trove/gateway/routing/ProxyController.java:67` (only `/api/v1/categories` → `categorisation` is present in the route map) | Grepped the gateway's route table for `categorise`/`categorisation`; only the `/categories` entry exists. |
| G3 (existing, re-verified) | Mass assignment on `POST /v1/categories`: the JPA entity `Category` is bound directly as `@RequestBody`; only `system` is forced server-side, so a client-supplied `id` matching an existing system category performs a JPA merge over it. | `CategoryController.java:100-104` (`createCategory`, `category.setSystem(false)` then `save(category)`, no id stripping) | Read the controller method; confirmed `Category` (the `@Entity`) is the request body type and only `system` is reset. |
| G10 (existing, re-verified) | Bean Validation is inert: `pom.xml` never depends on `spring-boot-starter-validation`. | `services/categorisation-service/pom.xml:16-100` (dependency list, no validation starter) | Read the full `pom.xml`; confirmed absence. Also confirmed no `@Valid`/`@NotBlank` annotations exist anywhere in this module's DTOs regardless. |
| NEW | Client-supplied `userId`/`transactionId` in the request body are trusted for `/v1/categorise`, `/v1/categorise/batch`, and `/v1/categorise/override`, without ever checking them against the authenticated JWT principal (`SecurityContextHolder`/`TroveUserDetails`). Any authenticated user can set another user's category override or read what category a merchant maps to for an arbitrary `(userId, transactionId)` pair. This is distinct from the header-based IDOR already documented as G1 (ingestion-service's `X-User-Id`): here the JWT is validated correctly, but the controller/service layer never consults it. | `CategorisationController.java:71-78,143-147` (identity read from `CategoriseRequest`/`OverrideRequest` body, not from security context); `CategorisationService.java:127-180,195-207` (`categorise`/`saveOverride` take `userId` as a plain method parameter with no cross-check against the caller) | Read every controller method and confirmed none calls `SecurityContextHolder.getContext()`; read `TroveUserDetails` and confirmed it carries `userId`; grepped the whole module for `SecurityContextHolder`/`Authentication`/`X-User-Id` — zero hits outside the shared filter itself. |
| NEW | `libs/common`'s `JacksonConfig` (snake_case naming strategy) is never applied in this service — it's a bare `@Configuration` outside the scanned base package and not in `CategorisationSecurityConfig`'s `@Import` list. JSON actually serializes/deserializes with default (camelCase) naming. | `libs/common/src/main/java/finance/trove/common/config/JacksonConfig.java:9-17` (the class); `TroveCategorisationApplication.java:39` (`@SpringBootApplication`, base package `finance.trove.categorisation`, no scan of `finance.trove.common.config`); `CategorisationSecurityConfig.java:12` (`@Import` list omits `JacksonConfig`) | Confirmed no `META-INF/spring/...AutoConfiguration.imports` exists under `libs/common` (also independently established by O2). Grepped every service under `services/*/src/main/java` for `JacksonConfig` usage: only `ingestion-service` references a `JacksonConfig`, and it's ingestion's own separate local class, not this one. So this module's Jackson behavior is plain Spring Boot defaults. |
| NEW | `spring.datasource.url` default port is inconsistent between `application.yml` (5434) and `.env.example` (5432). | `application.yml:9` (`localhost:5434`) vs `.env.example:6` (`localhost:5432`) | Read both files; ports differ. Low severity (env var normally overrides both in any real deployment) but will misdirect anyone running this module standalone off the documented `.env.example` without also checking `application.yml`. |
| NEW | `trove.ai.service-token` (`TROVE_AI_SERVICE_TOKEN`) is mandatory at startup (`AiGatewayProperties.validate()`) but never sent on the outbound call to model-manager — dead config that still gates boot. | `libs/ai-gateway-client/src/main/java/finance/trove/ai/gateway/AiGatewayProperties.java:64-69` (validation); `LiteLlmRestClient.java:60-77` (headers actually sent: `X-Request-Id`, `X-Privacy-Tier`, `Content-Type` only) | Grepped `libs/ai-gateway-client/src/main/java` for `serviceToken` and `Authorization`; confirmed the field is read/validated but never referenced in the HTTP call path. |

## 11. Collapse recommendation

| Package | Action | Destination | Reason |
|---|---|---|---|
| `finance.trove.categorisation` (root — `TroveCategorisationApplication`) | DELETE | — (folds into trove-core's single `@SpringBootApplication`) | One JVM, one entry point; 10 of these collapse to 1. |
| `finance.trove.categorisation.categorise` (`AiCategorisationService`, `CategorisationController`, `CategorisationResult`, `CategorisationService`, `CategoryOverride`(+Repository), `MerchantCategoryMap`(+Repository)) | KEEP, but fix D4 during the move, not after | `finance.trove.categorisation.categorise` | Core domain logic is sound in shape (rule tiers, AI fallback, override) — the bug is two small, well-localized defects (self-invocation, inverted prefix query), not a design problem. Fix both while moving: split `categorise`/`persistMerchantMapping` across a proxy boundary (e.g. inject `CategorisationService` into itself via `@Lazy`, or move the write to a separate `@Service`) and drop `readOnly=true` from the outer method, or restructure to write in a second top-level transaction; fix the prefix query to a `@Query` with the arguments the right way round (`:merchant LIKE CONCAT(m.merchant, '%')`) or reverse to `findByMerchant IN (candidate-prefixes)`. |
| `finance.trove.categorisation.category` (`Category`, `CategoryController`, `CategoryRepository`) | KEEP, but fix G3 during the move | `finance.trove.categorisation.category` | Same reasoning — sound shape, but `createCategory` must bind a request DTO (name/parent/icon only), not the `@Entity`, before this reaches the public internet. |
| `finance.trove.categorisation.config` (`CategorisationSecurityConfig`) | DELETE | — | Pure `@Import` wrapper around 4 shared `libs/common` classes; one of 10 near-identical copies repo-wide. In trove-core there is exactly one `SecurityConfig`/`JwtAuthFilter`/`JwtProperties`/`GlobalExceptionHandler`/`RequestIdMdcFilter` set, wired once (ideally via the O2-recommended `libs/common` auto-configuration fix so no per-package `@Import` boilerplate is needed at all). None of `SecurityConfig`, `JwtAuthFilter`, `JwtProperties`, `GlobalExceptionHandler`, or `RequestIdMdcFilter` are duplicated *inside this module* — they're the shared `libs/common` originals, correctly reused. |
| `resources/db/migration/V1__category.sql`, `V2__merchant_category_map.sql`, `V3__category_override.sql` | KEEP, renumber | trove-core's single consolidated Flyway history (renumber alongside the other 9 services' migrations into one sequential set) | Table names (`category`, `merchant_category_map`, `category_override`) are unique across the whole repo — verified no other service's migrations create tables with these names. No renaming needed, only renumbering to avoid Flyway version collisions once all migrations share one schema history. |
| `resources/community-merchant-registry.json` | KEEP | same relative path under trove-core resources | Small, self-contained seed data for the in-memory registry; no changes needed. |
| `resources/application.yml` | REWRITE (merge) | folded into trove-core's single `application.yml` (or a `categorisation:` config block) | Property keys don't clash with anything seen so far (see §12), but `server.port`, `spring.datasource.*`, and `spring.flyway.enabled` all need to become sections of one consolidated config rather than 10 standalone files; fix the 5434/5432 default-port inconsistency while doing so. |
| Test packages (`categorise`, `category`, `support`, root `OpenApiTest`) | KEEP, adapt | mirrored `src/test/java/finance/trove/categorisation/...` under trove-core | Tests are real and mostly meaningful; `BaseIT`'s Testcontainers Postgres setup will need to become one shared base for the whole consolidated test suite (or be deleted in favor of trove-core's single integration-test base) rather than one per former service. Add regression tests for D4 (both the self-invocation bug and the inverted prefix query) and G3 (mass-assignment via a supplied `id`) while fixing them. |

No class in this module duplicates a name already flagged repo-wide as a common-lib
duplicate (`JwtAuthFilter`, `SecurityConfig`, `GlobalExceptionHandler`,
`RequestIdMdcFilter`, `JwtProperties`, `MinioStorageService`) — this module has none of its
own copies of any of them.

## 12. Merge risks / gotchas

- **Identity-trust gap (NEW, §10)**: `/v1/categorise*` trusts a body-supplied `userId` over
  the JWT principal. When collapsed into trove-core alongside ingestion/budget (which have
  their own IDOR issue, G1, via `X-User-Id`), make sure the *fix* for one doesn't get
  half-applied to the other — they're different bugs (header vs. body) requiring the same
  general remedy (derive identity from `SecurityContextHolder`/JWT `sub` only, never from
  client input) but at different call sites.
- **JacksonConfig activation risk**: this module currently runs with default (camelCase)
  JSON naming because `libs/common`'s snake_case `JacksonConfig` is inert here (§2, §10).
  When merged into trove-core with a single component scan / a fixed auto-configuration
  (per O2), `JacksonConfig` may become live for the first time across the whole app — if so,
  every JSON field this service currently emits/accepts in camelCase (`merchantName`,
  `categoryId`, `transactionId`, `createdAt`, `isSystem`, …) would silently become
  snake_case, breaking every existing web/mobile client call to `/v1/categorise*` and
  `/v1/categories*` that isn't itself updated in lockstep. Decide explicitly whether
  `JacksonConfig` ships as part of the consolidated config, and if so, update all
  clients/OpenAPI specs together — do not let it activate as a side-effect of merging.
- **`X-Request-Id` correlation break**: `AiCategorisationService` mints a *fresh*
  `UUID.randomUUID()` per AI call (`AiCategorisationService.java:85`) rather than propagating
  the inbound request's correlation id from `RequestIdMdcFilter`/MDC. Cross-service log
  correlation for AI calls is already broken; worth fixing opportunistically during the
  merge since MDC will be shared process-wide in trove-core.
- **Default-port inconsistency**: `application.yml` (5434) vs `.env.example` (5432) for the
  local Postgres URL — resolve to one number when consolidating configs (§10, NEW).
- **Dead `trove.ai.service-token` requirement**: mandatory at startup, never used on the
  wire (§10, NEW). Decide during the merge whether to actually wire it as an `Authorization`
  header to model-manager (useful once model-manager gets real auth, cf. M1) or drop the
  `@PostConstruct` validation — don't just carry the dead requirement forward unexamined.
- **No hardcoded CAD/currency values anywhere in this module** — `categorise`/`Category` are
  currency-agnostic (they classify by merchant name only, never touch amounts or currency
  codes). Nothing here needs touching for the CAD/USD onboarding-setting work.
- **No hardcoded hostnames/ports beyond the two already listed** (`server.port: 8082`,
  `trove.ai.model-manager-url` default `http://model-manager:8089` — the latter will need to
  become a no-op / same-JVM in-process call once model-manager's logic also collapses into
  trove-core, at which point the whole `RestClient` hop to `/v1/chat/completions` can likely
  become a direct method call — worth flagging to whoever inventories `ai-gateway-client`
  and `model-manager`).
- **Two same-simple-name `PrivacyViolationException` classes** (`finance.trove.ai.gateway.*`
  and `finance.trove.privacy.*`) are both caught explicitly in
  `AiCategorisationService.java:104-107` (fully-qualified, both re-thrown). This is not a
  compile risk (different packages) but is exactly the duplicate O1 already flagged for
  consolidation — when that consolidation happens, this two-catch-block pattern needs to
  collapse to one.
- **Static state**: `CategorisationService.communityRegistry` is instance-level, not static
  (loaded per-`@PostConstruct`), so no static-state clash risk when this becomes one bean
  among many in trove-core — confirmed no `static` mutable fields anywhere in this module.
- **Bean names**: `CategorisationSecurityConfig` is deleted per §11, so no
  `XxxSecurityConfig`-class collision risk from this module specifically survives the merge;
  worth noting for whoever collapses the other 9 that `model-manager`'s own security class is
  literally named `SecurityConfig` (not `ModelManagerSecurityConfig` like every other
  service) — different package, no compile clash, but it's the one service that skipped the
  shared `libs/common` `SecurityConfig` entirely (that's M1, the no-auth bug) rather than
  wrapping it like this module does.
