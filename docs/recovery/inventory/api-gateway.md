# Inventory — services/api-gateway

> **Historical record, 2026-09-03.** Inventory of the pre-rebuild code, since removed; kept for the rebuild trail. Current state: `docs-site/guide/architecture.md`.

All paths repo-relative. Line numbers verified against source on 2026-09-03. Prior
verified findings referenced as B1-B9, D7, D9, G4/G5/G7/G8/G9 come from
`docs/recovery/01-FINDINGS.md` (section O supersedes earlier sections); each is
re-verified against this module's actual source below, not merely cited.

## 1. Purpose and LOC

The api-gateway is the sole public-internet entry point for Trove: it terminates
OIDC login (Authentik, via Spring `oauth2Login` BFF pattern), mints/validates the
single Trove-issued JWT, enforces per-user/per-IP rate limits and a CSRF header
guard, writes an audit-log row for every request, and reverse-proxies everything
under `/api/v1/**` to one of 9 downstream services by longest-prefix match,
injecting `X-Trove-User-Id` / `X-Trove-User-Email` / `X-Trove-Roles` headers.

- LOC main: **3054** (`find services/api-gateway/src/main -name '*.java' | xargs wc -l`)
- LOC test: **4088** (`find services/api-gateway/src/test -name '*.java' | xargs wc -l`)
- 34 main classes across 5 packages (`audit`, `auth` (+`auth.oidc`), `config`,
  `preferences`, `ratelimit`, `routing`) + application class.
- 34 test files: 13 `*IT.java` integration tests (Testcontainers Postgres +
  Valkey, several with WireMock) + 21 unit `*Test.java` files; see §9 for the
  full breakdown.

## 2. Spring wiring

**Application class:** `TroveApiGatewayApplication`
(`src/main/java/finance/trove/gateway/TroveApiGatewayApplication.java:48-58`)
- `@SpringBootApplication`
- `@ComponentScan(basePackages="finance.trove", excludeFilters = {SecurityConfig.class, JwtAuthFilter.class})`
  (:49-55) — deliberately excludes `libs/common`'s shared `SecurityConfig` and
  `JwtAuthFilter` because the gateway runs its own cookie+bearer chain
  (`CookieJwtAuthFilter`) instead of the header-only common filter. Documented
  rationale at :44-46: running both filters left `MeController` reading `null` off
  `auth.getDetails()`.
- `@EnableConfigurationProperties(AuthentikProperties.class)` (:56)
- `@EnableAsync` (:57) — backs `AuditLogPersistenceService.saveAsync` (§7).
- **No `@EnableScheduling`** anywhere in this module (confirms prior finding D8:
  the gateway is the one service with an unbacked `@Scheduled` —
  `RateLimitService.reloadConfig()`, ratelimit/RateLimitService.java:73 — so the
  300s rate-limit-config reload never actually fires; the config loaded once at
  `@PostConstruct` is what's enforced for the process lifetime).

**@Configuration classes** (all in `config/`):
- `GatewaySecurityConfig` (`config/GatewaySecurityConfig.java:110`) — `@EnableWebSecurity @EnableMethodSecurity`, builds the `SecurityFilterChain` bean and the PKCE `OAuth2AuthorizationRequestResolver` bean; `@Import(RequestIdMdcFilter.class)` from libs/common (:114).
- `CorsConfig` (`config/CorsConfig.java:27`) — produces `corsConfigurationSource` bean.
- `RestClientConfig` (`config/RestClientConfig.java:29`) — `authentikRestClient` (Apache HttpClient5, timeouts from `AuthentikProperties`) and a bare `restClientBuilder` used by `ProxyController`/`HealthAggregator`.
- `ValKeyConfig` (`config/ValKeyConfig.java:22`) — Lettuce `RedisClient` + `StatefulRedisConnection` beans, separate from Spring Boot's own Redis auto-config which independently wires the `StringRedisTemplate` used by `RefreshTokenStore`. Two different Redis client stacks (Lettuce native + Spring Data Redis) talk to the same Valkey instance.

**Security filter chain** (`GatewaySecurityConfig.securityFilterChain`, :137-168):
- CORS enabled via `corsConfigurationSource`; CSRF **disabled** (Spring's built-in token model — replaced by the custom `CsrfRequiresHeaderFilter`).
- `SessionCreationPolicy.IF_REQUIRED` — only the OAuth2 login dance uses a server session (for PKCE verifier/state/nonce); every other endpoint is stateless.
- Filter order (all relative to `UsernamePasswordAuthenticationFilter`): `RequestIdMdcFilter` (before) → `CookieJwtAuthFilter` (before) → `CsrfRequiresHeaderFilter` (after) → `RateLimitFilter` (after) → `AuditLogFilter` (after).
- `permitAll` paths (verbatim, :158-164):
  `/api/v1/auth/token`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`,
  `/api/v1/auth/start`, `/api/v1/health`, `/actuator/health`,
  `/actuator/prometheus`, `/v3/api-docs/**`, `/swagger-ui/**`,
  `/oauth2/authorization/**`, `/login/oauth2/code/**`.
  **`/api/v1/health` is permitAll'd but has no controller** — see D7/§3.
- `/api/v1/audit` → `.hasRole("ADMIN")` (:165). **Also has no controller** — same D7 defect.
- `anyRequest().authenticated()` (:166) — everything else, including all
  `ProxyController`-routed traffic, requires a valid Trove JWT.
- 401 entry point: `HttpStatusEntryPoint(401)` scoped to `/api/**` (:150-152) so
  XHR/JSON callers get a status code instead of following a 302 into Authentik;
  non-`/api/**` top-level navigations still get the default OAuth2 login redirect.
- Identity derivation: **cookie or Bearer header**, never a client-supplied
  `X-User-Id`. `CookieJwtAuthFilter` (auth/CookieJwtAuthFilter.java:45-75) reads
  `Authorization: Bearer` first, falls back to the `__Host-trove_access` cookie
  only if no header was present (header wins), verifies via
  `JwtAuthenticationProvider.authenticate` (HS256, `sub` claim → `userId` UUID),
  and sets a `TroveUserDetails` principal. There is no code path anywhere in this
  module that reads or trusts an inbound `X-User-Id`/`X-Trove-User-Id` header as
  identity — those headers are only ever *written* by `ProxyController` for
  downstream consumption (see §12).

**Jackson / naming:** No `JacksonConfig` exists in this module. `UserClaims`
(auth/UserClaims.java:24) is individually annotated
`@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)` for its own
ValKey JSON serialization via `tools.jackson.databind.ObjectMapper` (note: this is
**Jackson 3's** `tools.jackson` package, not the classic `com.fasterxml.jackson` —
`RefreshTokenStore.java:3-4` and `RateLimitFilter.java:3` import `tools.jackson.*`,
while `UserClaims.java:3-4` and `UserPreferencesDto.java:3` import classic
`com.fasterxml.jackson.*` annotations — **two different Jackson major versions are
mixed in this one module**; flag for the merge.

**@EnableScheduling/@EnableAsync:** `@EnableAsync` only (TroveApiGatewayApplication:57); no `@EnableScheduling` (see above — D8).

## 3. HTTP routes

Three route sources: (a) dedicated `@RestController`s, (b) Spring Security's own
OAuth2 endpoints (no controller class), (c) `ProxyController`'s catch-all proxy
table.

| Method | Path | Controller#method | Identity source | Request DTO | Response DTO | Notes |
|---|---|---|---|---|---|---|
| POST | /api/v1/auth/token | AuthController#exchangeToken (AuthController.java:63-67) | none (permitAll) | TokenRequest{idToken} | TokenResponse{accessToken,refreshToken,expiresIn,tokenType} | Exchanges Authentik OIDC access_token for Trove JWT pair; mobile/CLI path only |
| POST | /api/v1/auth/refresh | AuthController#refreshToken (:78-96) | none (permitAll); reads `__Host-trove_refresh` cookie or JSON body | RefreshRequest{refreshToken} (optional if cookie present) | TokenResponse | Cookie wins over body; re-sets both cookies only if source was cookie; 401 if neither present |
| POST | /api/v1/auth/logout | AuthController#logout (:104-123) | none (permitAll); cookie or body | RefreshRequest (optional) | 204 No Content | Idempotent; always clears both cookies; sets `X-Authentik-End-Session` response header |
| GET | /api/v1/auth/start | AuthController#startLogin (:141-149) | none (permitAll) | — | 302 redirect | Redirects to `{gatewayBase}/oauth2/authorization/authentik`, absolute URL pinned to gateway origin |
| GET | /api/v1/auth/me | MeController#me (MeController.java:34-43) | CookieJwtAuthFilter → TroveUserDetails | — | MeResponse{userId,email,roles} | 401 if principal null |
| GET | /api/v1/me/preferences | UserPreferencesController#get (:52-54) | TroveUserDetails (required) | — | ApiResponse\<UserPreferencesDto\> | Returns safe defaults (`onboardingCompleted=false`, other fields null) for a first-time user |
| PUT | /api/v1/me/preferences | UserPreferencesController#put (:68-72) | TroveUserDetails (required) | UserPreferencesDto{deployment,aiMode,onboardingCompleted} (partial patch, null=no-change) | ApiResponse\<UserPreferencesDto\> | 400 via GlobalExceptionHandler on invalid enum value |
| GET | /oauth2/authorization/authentik | Spring Security `OAuth2AuthorizationRequestRedirectFilter` (no controller) | none (permitAll) | — | 302 to Authentik | PKCE S256 via custom `authorizationRequestResolver` bean |
| GET | /login/oauth2/code/authentik | Spring Security `OAuth2LoginAuthenticationFilter` (no controller) | none (permitAll) | — | 302 (success→`TroveOidcSuccessHandler`, failure→`TroveOidcFailureHandler`) | Success handler mints Trove JWT cookies and redirects to `{webBaseUrl}/`; failure redirects to `{webBaseUrl}/login?error=<code>` |
| ANY | /api/v1/** (fallback proxy) | ProxyController#proxy (routing/ProxyController.java:153-200) | TroveUserDetails if authenticated (`@AuthenticationPrincipal`, nullable) | raw byte[] body | raw byte[] body | See PREFIX_TO_SERVICE table below; 404 if no prefix matches; query string is **dropped** (B3); body forwarded with **no Content-Type** header (B4); `Authorization` header is **not forwarded** (B1) |
| GET | /api/v1/health | **none — no controller** | n/a | — | — | permitAll'd in security config (GatewaySecurityConfig.java:161) and referenced in OpenAPI (`HealthApi` generated), but no class implements it and `HealthAggregator` is never invoked from an HTTP endpoint. Falls through to `ProxyController`, which finds no PREFIX_TO_SERVICE match → 404. Confirms prior finding D7. |
| GET | /api/v1/audit | **none — no controller** | n/a | — | — | `.hasRole("ADMIN")`'d (GatewaySecurityConfig.java:165), `AuditApi`/`AuditEntry`/`GetAuditLog200Response`/`PaginationMeta` generated from OpenAPI, `AuditLogRepository.findByUserIdOrderByCreatedAtDesc` exists and is paginated — but nothing wires them together. Same D7 defect; the entire audit-read surface is inert even though the audit-write path (AuditLogFilter) works. |
| GET | /actuator/health, /actuator/prometheus | Spring Boot Actuator (management port **9090**, separate from server port 8080) | none (permitAll) | — | — | `management.endpoints.web.exposure.include: health,info,prometheus,metrics` (application.yml:83-85) |
| — | /v3/api-docs/**, /swagger-ui/** | springdoc | none (permitAll) | — | — | OpenAPI UI |

### ProxyController.PREFIX_TO_SERVICE (verbatim, routing/ProxyController.java:63-77)

```
/api/v1/transactions  -> ingestion
/api/v1/accounts      -> ingestion
/api/v1/dashboard     -> ingestion
/api/v1/categories    -> categorisation
/api/v1/tax           -> tax
/api/v1/budgets       -> budget
/api/v1/networth      -> budget
/api/v1/receipts      -> receipt
/api/v1/subscriptions -> subscription
/api/v1/notifications -> notification
/api/v1/reports       -> report
/api/v1/models        -> model-manager
/api/v1/chat          -> model-manager
```
`resolveService` (:202-208) does longest-matching-prefix `startsWith`, not exact
segment match, and it's a plain linear scan of 13 entries (no ambiguity today, but
note it's `startsWith` not path-segment aware — `/api/v1/transactionsFOO` would
also match `ingestion`).

**No entry exists for `/api/v1/goals` or `/api/v1/webhooks`** (confirms prior
finding D9) — any budget-service `GoalController` or notification
`WebhookController` endpoints are unreachable through the gateway regardless of
whether those downstream controllers exist. No entry for `/api/v1/categorise*`,
`/api/v1/auth/*` (handled directly, correctly excluded from the map on purpose),
or `/api/v1/coach/*` even though V4's rate-limit seed (§5) has a row for
`/api/v1/coach/stream` — that pattern currently matches nothing routable.

**Pagination/filter params:** None are gateway-native — the gateway is a pure
byte-passthrough proxy for query parameters *in principle*, but B3 means no query
string ever reaches downstream at all (`request.getRequestURI()` only;
`getQueryString()` is never called anywhere in `src/main`, confirmed by
`grep -rn getQueryString src/main` returning zero hits). `AuditLogRepository`'s own
`findByUserIdOrderByCreatedAtDesc(UUID, Pageable)` (audit/AuditLogRepository.java:26)
is a normal Spring Data `Pageable` method but is never called from any controller
(dead code, per the D7 finding above).

## 4. Persistence

**Database:** `trove_gateway` (from `docker/docker-compose.yml:114` and
`infra/full-stack/docker-compose.yml:211`: `GATEWAY_POSTGRES_URL=jdbc:postgresql://postgresql:5432/trove_gateway`). One shared Postgres process, separate catalog per prior finding O6.

### JPA entities

| Entity | Table | Key columns | FKs | Unique / other indexes |
|---|---|---|---|---|
| `AuditLog` (audit/AuditLog.java:39) | `audit_log` | `id` UUID PK (gen_random_uuid via app-side `GenerationType.UUID`), `user_id` UUID nullable, `method` varchar(10), `path` varchar(500), `status` int, `duration_ms` bigint, `ip_address` varchar(45) (was INET, altered V3), `user_agent` varchar(500), `request_id` UUID not null, `created_at` timestamptz | none | `idx_audit_log_user_id(user_id)`, `idx_audit_log_created_at(created_at DESC)` (V1) |
| `RateLimitConfig` (ratelimit/RateLimitConfig.java:35) | `rate_limit_config` | `id` UUID PK, `endpoint_pattern` varchar(200) **unique**, `requests_per_min` int, `burst_capacity` int, `updated_at` timestamptz | none | unique on `endpoint_pattern` (V2) |
| `UserPreferences` (preferences/UserPreferences.java:37) | `user_preferences` | `user_id` UUID **PK** (not a surrogate — user_id itself is the primary key), `deployment` varchar(32), `ai_mode` varchar(32), `onboarding_completed` bool not null default false, `created_at`/`updated_at` timestamptz | none (no FK to a `users` table — none exists in this schema; gateway has no user-account table of its own, identity is entirely JWT/Authentik-sourced) | non-unique `user_preferences_user_id_idx(user_id)` on the PK itself — **redundant index**, the PK already has an implicit unique index (V5) |

### Repositories with custom queries
- `AuditLogRepository.findByUserIdOrderByCreatedAtDesc(UUID, Pageable)` (audit/AuditLogRepository.java:26) — derived query, forces `createdAt DESC` regardless of any sort in `Pageable`. Dead (§3).
- `RateLimitConfigRepository` — no custom queries, plain `JpaRepository<RateLimitConfig, UUID>`; only `findAll()` used, by `RateLimitService.reloadConfig()`.
- `UserPreferencesRepository.findByUserId(UUID)` (preferences/UserPreferencesRepository.java:21) — derived query, alias for `findById` since `userId` is the PK.

### Flyway migrations (`src/main/resources/db/migration/`)
| File | Summary | Tables/indexes |
|---|---|---|
| V1__create_audit_log.sql | Creates `pgcrypto` extension + `audit_log` table | `audit_log`; `idx_audit_log_user_id`, `idx_audit_log_created_at` |
| V2__create_rate_limit_config.sql | Creates `rate_limit_config`; seeds 3 default rows (`/api/v1/chat/**` 10/15, `/api/v1/auth/**` 5/8, `/api/**` 100/120) | `rate_limit_config` |
| V3__alter_audit_log_ip_address.sql | Changes `audit_log.ip_address` from `INET` to `VARCHAR(45)` for Hibernate String compatibility | alters `audit_log` |
| V4__seed_rate_limit_phase8.sql | `DELETE FROM rate_limit_config` then re-seeds 5 rows: `/api/v1/auth/**` 600/1200, `/api/v1/coach/stream` 120/300, `/api/v1/receipts/**` 300/600, `/api/v1/ingestion/sync` 60/180, `/api/**` 1200/2400 | data-only on `rate_limit_config` — **note this silently drops V2's `/api/v1/chat/**` throttle** (confirms prior finding G8: the LLM-endpoint-specific rate limit was deleted and never replaced; `/api/v1/chat/**` now falls under the generic `/api/**` 1200/2400 bucket) |
| V5__user_preferences.sql | Creates `user_preferences` table + its (redundant) index on the PK | `user_preferences`; `user_preferences_user_id_idx` |

Also confirms prior finding G8's second half: `/api/v1/auth/**` is rate-limited at
600 req/min burst 1200 (V4) — essentially unthrottled for brute-force purposes.

## 5. Config — every property in application*.yml

`src/main/resources/application.yml` (96 lines) + `src/test/resources/application-test.yml` (test profile overrides).

| Key | Env var | Default | Secret? |
|---|---|---|---|
| server.port | — | 8080 | no |
| server.forward-headers-strategy | — | framework | no |
| spring.application.name | — | api-gateway | no |
| spring.profiles.default | — | local | no |
| spring.datasource.url | GATEWAY_POSTGRES_URL | **none — hard fail if unset** | no |
| spring.datasource.username | GATEWAY_POSTGRES_USER | `trove` | no |
| spring.datasource.password | GATEWAY_POSTGRES_PASSWORD | `trove` | **yes — default secret** |
| spring.datasource.hikari.maximum-pool-size | — | 10 | no |
| spring.jpa.hibernate.ddl-auto | — | validate | no |
| spring.jpa.open-in-view | — | false | no |
| spring.flyway.enabled | — | true | no |
| spring.flyway.locations | — | classpath:db/migration | no |
| spring.flyway.validate-on-migrate | — | true | no |
| spring.flyway.out-of-order | — | false | no |
| spring.data.redis.url | VALKEY_URL | **none — hard fail if unset** | no (but connection string may embed creds depending on deployment) |
| spring.security.oauth2.client.registration.authentik.client-id | AUTHENTIK_CLIENT_ID | **none — hard fail if unset** | yes (OIDC client id) |
| spring.security.oauth2.client.registration.authentik.client-secret | AUTHENTIK_CLIENT_SECRET | **none — hard fail if unset** | **yes** |
| spring.security.oauth2.client.registration.authentik.authorization-grant-type | — | authorization_code | no |
| spring.security.oauth2.client.registration.authentik.client-authentication-method | — | client_secret_post | no |
| spring.security.oauth2.client.registration.authentik.scope | — | openid,profile,email | no |
| spring.security.oauth2.client.registration.authentik.redirect-uri | — | `{baseUrl}/login/oauth2/code/{registrationId}` | no |
| spring.security.oauth2.client.provider.authentik.authorization-uri | — | `${trove.authentik.url}/application/o/authorize/` | no |
| spring.security.oauth2.client.provider.authentik.token-uri | — | `${trove.authentik.url}/application/o/token/` | no |
| spring.security.oauth2.client.provider.authentik.user-info-uri | — | `${trove.authentik.url}/application/o/userinfo/` | no |
| spring.security.oauth2.client.provider.authentik.jwk-set-uri | — | `${trove.authentik.url}/application/o/trove/jwks/` | no |
| spring.security.oauth2.client.provider.authentik.user-name-attribute | — | sub | no |
| trove.jwt.secret | TROVE_JWT_SECRET | **none in application.yml** — but `.env.example:11` ships `change-me-minimum-32-chars-in-production!!` | **yes — weak, well-known default that passes the ≥32-char validation gate (JwtProperties.java:44-47)** |
| trove.jwt.expiry-seconds | — | 900 | no |
| trove.jwt.refresh-expiry-seconds | — | 86400 | no |
| trove.web.base-url | TROVE_DOMAIN | `http://localhost:5173` | no |
| trove.authentik.url | AUTHENTIK_URL | **none — hard fail if unset** (`@NotBlank`, AuthentikProperties.java:37-38) | no (URL, not credential) |
| trove.authentik.timeout-seconds | — | 10 | no |
| trove.authentik.jwks-cache-ttl-seconds | — | 300 | no (note: bound but **never actually used** to configure any JWKS cache — Spring's own `jwk-set-uri` handles OIDC JWKS internally; this property looks vestigial/dead) |
| trove.services.ingestion | INGESTION_URL | `http://ingestion-svc:8081` | no |
| trove.services.categorisation | CATEGORISATION_URL | `http://categorisation:8082` | no |
| trove.services.tax | TAX_URL | `http://tax-engine:8083` | no — **this whole property + downstream will be deleted with the tax engine cut** |
| trove.services.budget | BUDGET_URL | `http://budget-svc:8084` | no |
| trove.services.receipt | RECEIPT_URL | `http://receipt-proc:8085` | no |
| trove.services.subscription | SUBSCRIPTION_URL | `http://subscription-det:8086` | no |
| trove.services.notification | NOTIFICATION_URL | `http://notification-svc:8087` | no |
| trove.services.report | REPORT_URL | `http://report-svc:8088` | no |
| trove.services.model-manager | MODEL_MANAGER_URL | `http://model-manager:8089` | no |
| trove.rate-limit.enabled | — | true | no (**dead flag** — nothing in `RateLimitFilter`/`RateLimitService` reads `trove.rate-limit.enabled`; grep confirms zero references outside `application.yml`/`application-test.yml` themselves — the filter always runs) |
| trove.rate-limit.refresh-interval-seconds | — | 300 | no (moot — no `@EnableScheduling`, see §2/D8) |
| trove.cors.allowed-origins | TROVE_DOMAIN | `http://localhost:5173` | no |
| management.server.port | — | 9090 | no |
| management.endpoints.web.exposure.include | — | health,info,prometheus,metrics | no |
| resilience4j.circuitbreaker.instances.authentik.* | — | slidingWindowSize=10, failureRateThreshold=50, waitDurationInOpenState=30s, permittedNumberOfCallsInHalfOpenState=3, ignoreExceptions=[UnauthorizedException] | no |

**`${VAR:-default}`-style placeholders present:** `GATEWAY_POSTGRES_USER:trove`,
`GATEWAY_POSTGRES_PASSWORD:trove`, `TROVE_DOMAIN:http://localhost:5173` (×2, web
base-url and CORS), `trove.rate-limit.refresh-interval-seconds:300` (used inside a
`fixedRateString`). Hard-fail (no default) vars: `GATEWAY_POSTGRES_URL`,
`VALKEY_URL`, `AUTHENTIK_CLIENT_ID`, `AUTHENTIK_CLIENT_SECRET`, `AUTHENTIK_URL`,
`TROVE_JWT_SECRET` (the last three confirm prior finding H1(c): api-gateway is one
of the three independent hard blockers to the README quick-start).

`.env.example` (`services/api-gateway/.env.example`) additionally ships
`JAVA_TOOL_OPTIONS=-Xmx300m` and `SPRING_PROFILES_ACTIVE=local`, and repeats the
weak `TROVE_JWT_SECRET` and `GATEWAY_POSTGRES_PASSWORD` defaults flagged above with
`# CHANGE IN PRODUCTION` comments that nothing enforces.

## 6. Outbound HTTP

| Target | Path | Headers set | Timeouts | file:line |
|---|---|---|---|---|
| Authentik | `GET {trove.authentik.url}/application/o/userinfo/` | `Authorization: Bearer <token>` | Yes — connect + response both `AuthentikProperties.timeoutSeconds` (default 10s), Apache HttpClient5 (`RestClientConfig.java:44-58`) | `auth/AuthentikClient.java:56-68` |
| Downstream microservice (dynamic, from `trove.services.*`) | `{PREFIX_TO_SERVICE-stripped path}` (no query string — B3) | `X-Trove-User-Id`, `X-Trove-User-Email`, `X-Trove-Roles` (only if authenticated), `X-Request-Id`, `X-Forwarded-For` — **`Authorization` explicitly NOT forwarded (B1)**, **`Content-Type` never set (B4)** | **No** — `restClientBuilder.baseUrl(baseUrl).build()` (RestClientConfig.java:66-69) is a bare, unconfigured `RestClient.Builder` with no `HttpComponentsClientHttpRequestFactory`/timeout wired for the proxy path (unlike the Authentik client) — a hung downstream service blocks the proxying thread indefinitely | `routing/ProxyController.java:184-199` |
| Downstream microservice `/actuator/health` (all registered services) | `GET /actuator/health` | none | **No** — same unconfigured builder as above | `routing/HealthAggregator.java:56-58` |

Both outbound calls to arbitrary downstream services (proxy + health probe) share
the same timeout-less `RestClient.Builder` bean (`RestClientConfig.restClientBuilder`,
:66-69) — worth fixing in the monolith merge even though the proxy itself is being
collapsed away (the health-probe pattern, if kept as an internal health-check
utility, should get timeouts).

## 7. Scheduled/async, application events

- `@Scheduled(fixedRateString = "${trove.rate-limit.refresh-interval-seconds:300}000")`
  on `RateLimitService.reloadConfig()` (ratelimit/RateLimitService.java:73-83) —
  reloads all `RateLimitConfig` rows from Postgres into a `volatile` in-memory
  cache. **Never fires**: no `@EnableScheduling` anywhere in this module (§2, D8).
  Only the `@PostConstruct init()` (:59-62) call runs once at startup, so
  rate-limit rule changes in the DB after boot are never picked up without a
  restart, contrary to the class's own javadoc claim of "operators can update
  rate-limit rules in the database without restarting the gateway"
  (RateLimitConfig.java:20-21 / RateLimitService.java:67-69).
- `@Async` on `AuditLogPersistenceService.saveAsync(AuditRecord)`
  (audit/AuditLogPersistenceService.java:59-76) — this one **does** run, since
  `@EnableAsync` is present on the application class. Uses Spring's default
  `SimpleAsyncTaskExecutor` (no custom `TaskExecutor` bean defined anywhere in
  this module) — unbounded thread-per-task, no queue/pool limit, under a request
  storm this can spawn unbounded audit-write threads.
- No application events (`ApplicationEventPublisher`/`@EventListener`) anywhere in
  this module.
- No other `@Scheduled`/`@Async` in the module (confirmed by
  `grep -rn "@Scheduled\|@Async" src/main` → exactly the two hits above).

## 8. External dependencies

- **Postgres** — database `trove_gateway`, own schema (`audit_log`,
  `rate_limit_config`, `user_preferences`), Flyway-managed.
- **Valkey/Redis** — two independent client stacks against the same instance:
  Lettuce native (`ValKeyConfig`, used by `RateLimitService`'s Bucket4j proxy
  manager) and Spring Data `StringRedisTemplate` (auto-configured, used by
  `RefreshTokenStore`).
- **Authentik** — OIDC identity provider; both the `oauth2Login` BFF flow
  (browser) and the direct `AuthentikClient.fetchUserInfo` call (mobile/CLI
  token-exchange path) depend on it being reachable; no default URL (hard
  startup failure without `AUTHENTIK_URL`/`AUTHENTIK_CLIENT_ID`/`AUTHENTIK_CLIENT_SECRET`).
- **9 downstream Trove microservices** via `trove.services.*` (ingestion,
  categorisation, tax, budget, receipt, subscription, notification, report,
  model-manager) — proxied and health-probed only; the gateway has no other
  awareness of what each one does.
- No MinIO, no SMTP, no SimpleFIN, no Ollama/LiteLLM, no PaddleOCR references
  anywhere in this module — those are downstream-service concerns only.

## 9. Tests

- **21 unit tests** (`*Test.java`, Mockito-based, no Spring context except where
  noted) + **13 integration tests** (`*IT.java`, `@SpringBootTest` +
  Testcontainers Postgres/Valkey, several with WireMock for downstream service
  stubs): `AuditLogIT`, `CookieAuthIT`, `CsrfIT`, `OidcLoginIT`, `AuthControllerIT`,
  `UserPreferencesControllerIT`, `UserPreferencesRepositoryIT`, `ProxyControllerIT`,
  `RateLimitConfigSeedIT`, `RateLimitServiceIT`, `RateLimitFilterIT`,
  `RefreshTokenIT`, `AccessControlIT` (**@Disabled**).
- `AccessControlIT` (security/AccessControlIT.java:37) is `@Disabled` — annotation
  text: *"Re-enable after Phase 26 C2 auth blocker resolved (gateway register
  endpoint or pre-seeded test users)"*. It targets `POST /api/v1/auth/register`
  and `POST /api/transactions`, neither of which exist in this codebase — the test
  was written against a hypothetical password-registration API that Trove never
  built (OIDC-only). This is the repo's only cross-user IDOR/access-control test
  and it has never run. **Confirms prior finding B5's second half.**
- `ProxyControllerIT.requestForwardedWithXTroveUserIdHeader`
  (ProxyControllerIT.java:64-79) and `ProxyControllerTest` (unit, 5 cases) assert
  the gateway sends `X-Trove-User-Id` — internally self-consistent with the
  gateway's own code, but since the WireMock stub is the only "downstream", **no
  test in this module ever exercises what a real downstream service (which reads
  `X-User-Id`, not `X-Trove-User-Id`, per the cross-service B2 finding) would do
  with that header** — the mismatch is invisible from inside this module's test
  suite by construction. **Confirms prior finding B5's first half.**
- No test in `ProxyControllerTest`/`ProxyControllerIT` ever calls a URL with a
  query string, or asserts on the outbound `Content-Type` header — both gaps line
  up exactly with the unfixed B3/B4 defects; the test suite was written against
  the buggy behavior rather than catching it.
- `JwtAlgorithmTest` (security/JwtAlgorithmTest.java) explicitly documents and
  asserts `EXPECTED_ALG = "HS256"` (:42, :66) with commentary that the original
  plan called for RS256 — corroborates prior finding G4 (shared symmetric secret
  across all 10 services) directly from this module's own test suite, not just
  from javadoc.

## 10. Verified defects

| id | summary | file:line | verified how |
|---|---|---|---|
| B1 | Proxy builds a fresh `HttpHeaders` and never forwards the client's `Authorization` header to downstream services | routing/ProxyController.java:173-179 | Read `proxy()`: `headers` is `new HttpHeaders()`; only `X-Trove-*`/`X-Request-Id`/`X-Forwarded-For` are `.set()`; the original request's headers are never copied |
| B2 | Gateway injects `X-Trove-User-Id` as the identity header for downstream services | routing/ProxyController.java:175 | Read source; cross-referenced against `ProxyControllerIT.java:68,83` which asserts the same header name from the gateway side |
| B3 | Query string is dropped — only `request.getRequestURI()` is used, `getQueryString()` is never called | routing/ProxyController.java:158 | `grep -rn getQueryString src/main` → 0 hits; confirmed `path`/`downstreamPath` derive solely from `getRequestURI()` |
| B4 | Proxied body is forwarded as raw `byte[]` with no `Content-Type` header set (client's own `Content-Type` is discarded along with all other original headers per B1) | routing/ProxyController.java:173-189 | Read `headers` construction (no Content-Type key ever set) and `.body(body != null ? body : new byte[0])` with no content-type argument |
| B5 | No test in this module catches B1-B4: downstream boundary is WireMocked (so header-name self-consistency, not correctness, is what's tested), and the one cross-user access-control test (`AccessControlIT`) is `@Disabled` | test/.../ProxyControllerIT.java:64-79, test/.../security/AccessControlIT.java:37 | Read both files directly (§9) |
| D7 | `GET /api/v1/health` (permitAll) and `GET /api/v1/audit` (ROLE_ADMIN) are declared in the security config and have full OpenAPI-generated `HealthApi`/`AuditApi` interfaces + supporting models, but **no class in `src/main` implements either interface** | config/GatewaySecurityConfig.java:161,165; confirmed absent via `grep -rn "implements AuditApi\|implements HealthApi" src/main` → 0 hits | Grep for implementing classes returned nothing; `HealthAggregator` and `AuditLogRepository.findByUserIdOrderByCreatedAtDesc` are fully implemented but never invoked from any `@RequestMapping` |
| D8 | `RateLimitService.reloadConfig()` is `@Scheduled` but the module has no `@EnableScheduling` anywhere, so it only ever runs once (via `@PostConstruct init()`) | ratelimit/RateLimitService.java:59-62,73; `grep -rn EnableScheduling src/main` → 0 hits | Grep confirms no `@EnableScheduling` in `TroveApiGatewayApplication` or any `@Configuration` class in this module |
| D9 | No `PREFIX_TO_SERVICE` entry for `/api/v1/goals` or `/api/v1/webhooks` | routing/ProxyController.java:63-77 | Enumerated the literal `Map.ofEntries(...)` — 13 entries, neither prefix present |
| G8(new detail) | V4 migration's `DELETE FROM rate_limit_config` silently removes V2's `/api/v1/chat/**` 10/15 throttle and replaces it with the generic `/api/**` 1200/2400 bucket; `/api/v1/auth/**` becomes 600/1200 (essentially unthrottled for auth brute force) | db/migration/V2__create_rate_limit_config.sql:10-13, V4__seed_rate_limit_phase8.sql:4-11 | Diffed the two migrations directly |
| NEW-1 | `trove.rate-limit.enabled` property is bound nowhere — `RateLimitFilter`/`RateLimitService` never read it; the filter always runs regardless of its value | application.yml:74; `grep -rn "rate-limit.enabled\|rateLimitEnabled" src/main` → 0 hits outside the yml files | Grep for any Java reference to the property; none found |
| NEW-2 | `user_preferences_user_id_idx` is a redundant non-unique index on `user_preferences.user_id`, which is already the table's PRIMARY KEY (and therefore already uniquely indexed) | db/migration/V5__user_preferences.sql:1-10 | Read migration: `user_id UUID PRIMARY KEY` (line 2) followed by `CREATE INDEX ... ON user_preferences(user_id)` (line 10) |
| NEW-3 | Two different Jackson major versions are imported side-by-side in this one module: `tools.jackson.*` (Jackson 3) in `RefreshTokenStore`/`RateLimitFilter`, and classic `com.fasterxml.jackson.*` in `UserClaims`/`UserPreferencesDto` | auth/RefreshTokenStore.java:3-4, ratelimit/RateLimitFilter.java:3, auth/UserClaims.java:3-4, preferences/UserPreferencesDto.java:3 | Read import lists directly in all four files |
| NEW-4 | Outbound `RestClient.Builder` used for both the reverse-proxy path and the health-aggregator probe path has no connect/response timeout configured (unlike the Authentik client, which does) | config/RestClientConfig.java:66-69 vs :44-58 | Compared the two bean definitions in the same file — `authentikRestClient` wires `HttpComponentsClientHttpRequestFactory` with explicit `Timeout`; `restClientBuilder` is bare `RestClient.builder()` |
| NEW-5 | `trove.authentik.jwks-cache-ttl-seconds` is bound (with `@Min(1)` validation and a default of 300) but never consumed anywhere in `src/main` — Spring's own `jwk-set-uri`-based OIDC token validation does its own JWKS handling independent of this property | auth/AuthentikProperties.java:44,563-564; `grep -rn jwksCacheTtl src/main` → only the field declaration itself | Grep for all usages of `getJwksCacheTtlSeconds()`; zero call sites found |

## 11. Collapse recommendation — every package in src/main

Target namespace convention: `finance.trove.core.<domain>.<sub>` (per the
`finance.trove.<domain>.<sub>` instruction, using `core` as the module's own domain
root since `trove-core` is itself the single module).

| Package | Verdict | Destination | Reason |
|---|---|---|---|
| `finance.trove.gateway.audit` (`AuditLog`, `AuditLogFilter`, `AuditLogPersistenceService`, `AuditLogRepository`) | **KEEP** | `finance.trove.core.audit` | Genuinely useful compliance/debugging feature; the write path works. Fix on merge: either implement the missing read endpoint (D7) or delete the dead `AuditApi`/`GetAuditLog200Response` OpenAPI surface — don't carry the D7 gap forward silently. `audit_log` table name is currently unique in the schema; verify no other service also has an `audit_log` table before merging catalogs (merge risk, §12). |
| `finance.trove.gateway.auth` (`AuthController`, `AuthService`, `AuthentikClient`, `AuthentikProperties`, `AuthentikUserInfo`, `CookieJwtAuthFilter`, `CsrfRequiresHeaderFilter`, `JwtAuthenticationProvider`, `JwtIssuerService`, `MeController`, `RefreshTokenStore`, `TroveRoles`, `UserClaims`) | **KEEP (mostly), REWRITE (Authentik-specific parts)** | `finance.trove.core.auth` | This is the auth/session module the orchestrator asked to preserve. `JwtIssuerService`/`JwtAuthenticationProvider`/`CookieJwtAuthFilter`/`RefreshTokenStore`/`CsrfRequiresHeaderFilter`/`MeController`/`TroveRoles`/`UserClaims` are IdP-agnostic and survive as-is (cookie/JWT session core). `AuthentikClient`, `AuthentikProperties`, `AuthentikUserInfo`, and the `AuthController.exchangeToken`/mobile-CLI id-token-exchange path are Authentik-specific — per the owner's decision (Authentik dropped as a hard dependency, OIDC optional, built-in local accounts primary) these need a **REWRITE**: add a local-accounts path (argon2 password hash + optional TOTP, new `AuthController` endpoints for register/login) and keep the existing OIDC/`oauth2Login` machinery (`TroveOidcSuccessHandler`, `TroveOidcFailureHandler`, PKCE resolver in `GatewaySecurityConfig`) as an **optional**, config-gated provider rather than the only login path. |
| `finance.trove.gateway.auth.oidc` (`AuthCookies`, `TroveOidcFailureHandler`, `TroveOidcSuccessHandler`) | **KEEP, make optional** | `finance.trove.core.auth.oidc` | `AuthCookies` (the `__Host-trove_access`/`__Host-trove_refresh` cookie contract) is IdP-agnostic and becomes the cookie layer for both local-account and optional-OIDC login. The two handlers stay but must be wired conditionally (`@ConditionalOnProperty` or similar) once OIDC becomes optional rather than mandatory. |
| `finance.trove.gateway.config` (`CorsConfig`, `GatewaySecurityConfig`, `RestClientConfig`, `ValKeyConfig`) | **KEEP, REWRITE security chain** | `finance.trove.core.config` | `CorsConfig`/`ValKeyConfig` survive close to as-is. `GatewaySecurityConfig` needs a REWRITE: it currently `excludeFilters` out `libs/common`'s `SecurityConfig`/`JwtAuthFilter` because the gateway ran a bespoke chain — in the monolith there's only one security chain, so this becomes THE security config, needs to add local-account authentication (`DaoAuthenticationProvider` + argon2 `PasswordEncoder`) alongside the existing JWT/cookie/OIDC pieces, and needs the CSRF/rate-limit/HSTS/security-headers hardening called for by the public-internet deployment decision (this module currently has no HSTS header — that was presumably meant to live at the Traefik layer, confirm it's covered there before deleting `RateLimitFilter`'s "belt" without the Traefik "suspenders"). `RestClientConfig`'s `authentikRestClient` bean becomes conditional/optional along with Authentik itself; its unconfigured `restClientBuilder` (NEW-4) should either gain timeouts or be deleted along with the proxy it served. |
| `finance.trove.gateway.preferences` (`UserPreferences`, `UserPreferencesController`, `UserPreferencesDto`, `UserPreferencesRepository`, `UserPreferencesService`) | **KEEP** | `finance.trove.core.preferences` | Clean, small, self-contained, no external dependencies beyond Postgres. `deployment`/`aiMode` enum values (`cloud`/`self-hosted`/`local`, `sonnet`/`haiku`/`local-llm` — `UserPreferencesDto.java:27-28`) look stale/placeholder against the actual owner decisions (user-configured OpenAI-compatible endpoint, off until configured) and should be revisited, but the mechanism (partial-patch upsert, onboarding gate) is sound and worth keeping. |
| `finance.trove.gateway.ratelimit` (`RateLimitConfig`, `RateLimitConfigRepository`, `RateLimitFilter`, `RateLimitService`) | **KEEP, fix D8/NEW-1 on merge** | `finance.trove.core.ratelimit` | Directly required by the public-internet-exposure decision (rate limits explicitly in scope). Sound design (Bucket4j + Valkey token bucket, fail-open on Valkey outage) undermined only by the missing `@EnableScheduling` (D8) and the dead `enabled` flag (NEW-1) — both trivial one-line fixes to make while merging, not architectural problems. Also fix the V4 migration gap that dropped the `/api/v1/chat/**`-specific throttle (§10, G8 detail) — re-add a tighter rule for whatever path the AI/coach endpoints land on in the monolith. |
| `finance.trove.gateway.routing` (`HealthAggregator`, `ProxyController`, `ServiceProperties`, `ServiceRegistry`) | **DELETE** | — | This entire package exists to route HTTP requests *between* separate JVM processes. In a modular monolith there are no separate processes to proxy to — controllers in each merged domain package are called in-process. `HealthAggregator`'s actuator-polling pattern has no purpose once there's one process with one `/actuator/health`. Deleting this package also deletes B1/B2/B3/B4/D7(health-only-half)/D9 by construction — they cannot exist without a proxy. **Do not port the `X-Trove-User-Id`/`X-Trove-User-Email`/`X-Trove-Roles` header-injection pattern into the monolith** — in-process code should read the `TroveUserDetails` principal directly (already available via `@AuthenticationPrincipal` in every controller, exactly as `MeController`/`UserPreferencesController` already do), not round-trip identity through synthetic HTTP headers. |
| `finance.trove.gateway.TroveApiGatewayApplication` (application class itself) | **DELETE** | — | Only one `@SpringBootApplication` survives the merge (trove-core's own), from whichever module is chosen as the shell — likely a fresh one, not this one, since this one's whole `@ComponentScan` exclude-filter trick (excluding `libs/common`'s `SecurityConfig`/`JwtAuthFilter`) was a workaround for running two security philosophies (gateway vs. downstream) side by side, which no longer applies once there's one chain for the whole app. |
| generated `finance.trove.gateway.api`/`finance.trove.gateway.model` (openapi-generator output under `target/generated-sources`, not source-controlled) | **DELETE** | — | Build artifacts, not source. `HealthApi`/`AuditApi` interfaces are unused (D7); `AuthApi` is unused by design (AuthController deliberately doesn't implement it, per its own javadoc at AuthController.java:35-42). The OpenAPI spec (`openapi/api-gateway.yaml`) itself needs a full rewrite for the monolith's real merged surface, not a straight carry-forward. |

### Duplicate/overlapping classes vs. libs/common or other services
- `CookieJwtAuthFilter` (this module) vs. `finance.trove.common.security.JwtAuthFilter`
  (libs/common) — deliberately not the same class (cookie+bearer vs. header-only);
  when this becomes the monolith's *only* JWT filter, `JwtAuthFilter` in
  libs/common becomes dead code across the whole codebase (every other service's
  copy of it) and should be deleted repo-wide, with `CookieJwtAuthFilter`'s logic
  (or a merge of the two) becoming the one filter.
- `GatewaySecurityConfig` (this module) vs. `finance.trove.common.security.SecurityConfig`
  (libs/common, per O2's finding that libs/common ships no auto-configuration and
  every service hand-duplicates a similar security setup) — this module already
  excludes the shared one; on merge, one of the two designs wins (recommend this
  module's, since it already has cookie/OIDC/rate-limit/CSRF integration the
  bare-bones shared one lacks) and every other service's local security
  boilerplate is deleted in favor of it.
- `JwtIssuerService` — per its own javadoc, "the *sole* JWT issuer in the entire
  Trove system." That invariant must be preserved in the monolith (still exactly
  one class calling `Jwts.builder()`); trivial once other services' code is merged
  in as they never had that ability.
- `RequestIdMdcFilter` — imported from `libs/common`, not duplicated here; the
  `@Import(RequestIdMdcFilter.class)` + manually-disabled `FilterRegistrationBean`
  pattern (GatewaySecurityConfig.java:114,204-209) exists specifically to keep it
  inside this module's security chain rather than auto-registered by the servlet
  container — worth preserving that exact wiring trick in the merged security
  config since other services likely rely on the simpler auto-registered form.
- No `MinioStorageService`, `GlobalExceptionHandler`, or `JwtProperties` class
  defined *in this module* — `JwtProperties` and (implicitly, via
  `GlobalExceptionHandler` mapping `IllegalArgumentException` in
  `UserPreferencesController`, per its own javadoc :22-24) `GlobalExceptionHandler`
  are both consumed from `libs/common` already, not reimplemented — good, no
  duplication to resolve here.

## 12. Merge risks/gotchas

- **Bean-name clash risk:** `ValKeyConfig.lettuceRedisClient()`/`redisConnection()`
  are Lettuce-native beans coexisting with Spring Boot's auto-configured Redis
  beans (`StringRedisTemplate` etc.) inside the *same* module already — this
  already works today, so it's a demonstrated-safe pattern, not a new merge risk;
  flagged only because if another service also independently defines a
  `RedisClient`/`StatefulRedisConnection` bean with the same method/bean names,
  Spring will fail fast on bean-definition conflict at monolith boot.
- **Table-name clash risk:** `audit_log`, `rate_limit_config`, `user_preferences`
  are this module's table names in the `trove_gateway` catalog. Since the
  monolith merges 11 separate Postgres catalogs into presumably one schema/database,
  **verify no other service's Flyway migrations also create tables named
  `audit_log`, `rate_limit_config`, or `user_preferences`** before merging — a
  name clash here silently drops or corrupts one service's data during Flyway
  migration replay. Spot-checked one likely collision: model-manager also has an
  audit-style table (`services/model-manager/src/main/resources/db/migration/V2__ai_audit_log.sql`)
  but it is named `ai_audit_log`, not `audit_log` — **no clash** for that pair.
  The full cross-module check (all remaining 8 services' migrations) is out of
  scope for a single-module inventory; flag for the cross-module migration-merge
  pass.
- **Property-key clash risk:** `trove.jwt.*` is shared with every downstream
  service (all validate against the same `JwtProperties` from libs/common) — this
  is intentional and stays a singleton in the monolith. `trove.rate-limit.*` and
  `trove.cors.*` and `trove.authentik.*` are gateway-only prefixes today; check
  whether any other service also defines `trove.rate-limit.enabled` or similar
  before the merge (unlikely, since rate limiting is gateway-only in the current
  architecture, but worth a grep across all 10 `application.yml`s).
- **Static state:** none found — no static mutable fields in this module
  (`TroveRoles.ADMIN_GROUP` is a `static final String` constant, not mutable
  state; `RateLimitService.cachedConfigs` is an instance `volatile` field, not
  static).
- **Hardcoded ports/hostnames:** all 9 `trove.services.*` defaults
  (`ingestion-svc:8081`, `categorisation:8082`, `tax-engine:8083`,
  `budget-svc:8084`, `receipt-proc:8085`, `subscription-det:8086`,
  `notification-svc:8087`, `report-svc:8088`, `model-manager:8089`) become
  entirely moot once those services are merged into one JVM — delete the whole
  `ServiceProperties`/`ServiceRegistry` mechanism along with `routing/` (§11).
  Gateway's own port `8080` and management port `9090` need to be reconciled
  against whatever port(s) trove-core's shell application class settles on.
- **Hardcoded currency (CAD):** none found in this module — grep for
  `"CAD"`/`Currency` across `src/main` returns zero hits. This module is entirely
  currency-agnostic (it never touches money amounts), so no currency work is
  needed here for the CAD/USD-first-class decision.
- **Anything that reads `X-User-Id` / `X-Trove-User-Id`:** confirmed **nothing in
  this module reads either header as an identity source** (§2) — it only *writes*
  `X-Trove-User-Id`/`X-Trove-User-Email`/`X-Trove-Roles` for downstream consumption
  in `ProxyController` (§11, deleted wholesale). Every identity read in this
  module goes through `@AuthenticationPrincipal TroveUserDetails`, sourced from
  the validated JWT/cookie — this is exactly the pattern that should propagate to
  every merged controller, replacing whatever `X-User-Id`-trusting code exists in
  the other 9 services (per prior finding G1, ingestion-service trusts a
  client-controlled `X-User-Id` header directly — that IDOR class of bug does not
  exist in this module and should be eliminated the same way everywhere else
  during the merge: read the authenticated principal, never a header).
- **Two Jackson major versions mixed in one module** (NEW-3, §10) — pick one
  (`tools.jackson` Jackson 3, since that's what 2 of the 4 files already use) and
  normalize before merging with 9 other services' Jackson imports, which are very
  likely all classic `com.fasterxml.jackson` — this could become a much larger
  clash across the whole monolith if left unresolved.
- **Management/actuator port split** (8080 app / 9090 management) is
  gateway-specific configuration; other services likely share app+management
  port. Decide one convention for trove-core before merging `application.yml`s.
