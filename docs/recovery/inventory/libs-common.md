# Inventory: libs/common

`libs/common` (Maven artifact `finance.trove:common`, package root `finance.trove.common`) is
23 main classes / 1286 LOC main, 15 test classes / 1006 LOC test. It is the shared library every
one of the 10 services depends on for auth, error handling, and DTOs. It ships **no Spring Boot
auto-configuration** — every consumer must hand-write an `@Import` list to wire it up, and one
service (model-manager) skipped that step entirely (see §3, matches 01-FINDINGS M1).

Dependencies (`libs/common/pom.xml`): spring-boot-starter-web/security/actuator/validation,
micrometer-registry-prometheus, jjwt-api/impl/jackson 0.12, jackson-datatype-jsr310,
logstash-logback-encoder, lombok. Notably the codebase has moved to **Jackson 3.x** — `Money`,
`JacksonConfig`, and `JwtAuthFilter` import `tools.jackson.databind.*` (Jackson 3's new package)
alongside `com.fasterxml.jackson.annotation.*` (annotations, unchanged in Jackson 3). Any
RestClient/ObjectMapper customizer built for trove-core must target Jackson 3, not 2.x idioms.

## 1. Classes

### config/
- **ActuatorConfig** — empty `@Configuration`, registers no beans. Pure documentation: records
  that every service exposes management port 9090 with `health,info,prometheus,metrics`. Nothing
  to port besides the convention itself (management port + exposed endpoints) into trove-core's
  single `application.yml`.
- **JacksonConfig** — registers a `JsonMapperBuilderCustomizer` bean that sets
  `PropertyNamingStrategies.SNAKE_CASE` and disables `FAIL_ON_UNKNOWN_PROPERTIES`.
  **Verified dead**: `grep -rln "JacksonConfig" services/` finds it referenced by **zero**
  services' main code (one api-gateway test comment mentions it in passing; ingestion-service has
  its own unrelated empty marker class of the same simple name at
  `services/ingestion-service/src/main/java/finance/trove/ingestion/config/JacksonConfig.java:1-6`
  that registers nothing). No service ever imports `finance.trove.common.config.JacksonConfig`.
  See §4 for why this matters — it settles the casing question in the *opposite* direction from
  what the class does.

### domain/ (money & jurisdiction)
- **CurrencyCode** — enum `{CAD, USD}`. Closed set matching the CAD/USD-first-class decision.
- **Jurisdiction** — enum `{CA, US}` with `defaultCurrency()` mapping CA→CAD, US→USD. Javadoc
  says it exists to dispatch tax-engine calculation strategy — that consumer is cut, but the
  enum's other job (driving the onboarding default-currency picker) survives.
- **Money** — the money helper. Immutable value object: `BigDecimal amount` (always
  `setScale(2, HALF_UP)` at construction, non-null) + `CurrencyCode currency` (non-null).
  `add`/`subtract`/`abs`/`isNegative`/`isZero`, cross-currency ops throw
  `IllegalArgumentException`. Serialises `amount` to a JSON **string** via `ToStringSerializer`
  to avoid float precision loss across languages — this is the one field whose wire shape is NOT
  governed by any naming strategy (it's a scalar, not a key), so it survives regardless of the
  camelCase/snake_case decision in §4.
- **PagedResult<T>** — record `(content, totalElements, page, limit)` with compact-constructor
  validation (page≥0, limit>0, totalElements≥0, content non-null, defensively copied to
  `List.copyOf`). `empty(page, limit)` factory.

### dto/ (API envelope)
- **ApiResponse<T>** — the uniform response envelope: `{success, data, error, meta}`. Three
  locked factories: `ok(data)`, `paged(data, meta)`, `error(errorDetail)`. Every controller
  across all 10 services returns this.
- **ErrorDetail** — RFC-7807-shaped error body: `{type, title, status, detail, instance,
  fieldErrors}`, Lombok `@Builder`. **The Java field is `fieldErrors` (camelCase)** —
  `libs/common/src/main/java/finance/trove/common/dto/ErrorDetail.java:37`. Because
  `JacksonConfig`'s SNAKE_CASE customizer is never wired in (see above), the field serialises on
  the wire as `fieldErrors`, not `field_errors`, in every service that actually runs today.
  `openapi/budget-service.yaml:873` independently documents it as `fieldErrors` too — spec and
  runtime agree; only the unused `JacksonConfig` class disagrees with both. See §4.
- **PaginationMeta** — `{total, page, limit}`, `@RequiredArgsConstructor(staticName = "of")`.

### exception/
- **TroveException** — root of the typed hierarchy: `(message, HttpStatus)`, `getStatus()`.
- **ResourceNotFoundException** → 404, **UnauthorizedException** → 401,
  **RateLimitException** → 429, **ServiceUnavailableException** → 503,
  **ValidationException** → 422. All thin subclasses of `TroveException`.
- **GlobalExceptionHandler** — `@RestControllerAdvice`, the shared exception→HTTP translator.
  Handles, in this order: `TroveException` (status from `getStatus()`), `MethodArgumentNotValidException`
  → 422 with `fieldErrors` map, `HttpMessageNotReadableException` → 400,
  `NoResourceFoundException` → 404, `HttpRequestMethodNotSupportedException` → 405,
  `IllegalArgumentException` → 400, catch-all `Exception` → 500
  (`libs/common/src/main/java/finance/trove/common/exception/GlobalExceptionHandler.java:44-147`).
  **G11 verified**: there is no `@ExceptionHandler(ResponseStatusException.class)` and no
  `@ExceptionHandler(AccessDeniedException.class)` anywhere in this class — confirmed by reading
  the full file (147 lines, 7 handlers, list above is exhaustive) and by
  `GlobalExceptionHandlerTest` (`libs/common/src/test/java/.../GlobalExceptionHandlerTest.java`),
  whose four `@Test`s cover only not-found/401/429/generic-500 and never assert a
  `ResponseStatusException` or `AccessDeniedException` path. Any service that throws
  `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)` — ingestion-service does, per
  01-FINDINGS G11 — falls through to the `Exception.class` handler and returns 500 instead of the
  intended 4xx, because `@ExceptionHandler(Exception.class)` matches before Spring's own
  `ResponseStatusExceptionResolver` gets a turn once a `@RestControllerAdvice` claims `Exception`.
  Separately, `AccessDeniedException` (thrown by `@PreAuthorize`/`hasRole` checks) is intercepted
  by Spring Security's `ExceptionTranslationFilter` *before* it would ever reach this advice, and
  `SecurityConfig` (below) configures only an `authenticationEntryPoint`, never an
  `accessDeniedHandler` — so a 403 falls back to Spring Security's default (non-JSON,
  non-`ApiResponse`-shaped) handling. Only api-gateway and model-manager use
  `@PreAuthorize`/`hasRole` today (`grep -rln "@PreAuthorize\|hasRole" services/`), and both have
  their own local `SecurityConfig`, not this one — so the gap is latent, not yet exploited, but
  trove-core's single `SecurityConfig` must add both handlers before any `@PreAuthorize` use is
  widened.

### observability/
- **Redact** — static string-masking helpers for log call sites: `token()` (first 4 chars +
  `***`), `email()` (`x***@domain`), `phone()` (`***` + last 4 digits), `cardNumber()`
  (`****-****-****-XXXX`). Primary defense against secret leakage in logs.
- **RedactingConverter** — Logback `%redact` conversion word; regex-masks `Bearer eyJ...` JWTs,
  standalone JWT-shaped tokens, and 13–19-digit card-number-shaped sequences in the *formatted*
  log message. Backstop, not primary defense (see `Redact` javadoc cross-reference).
- **RequestIdMdcFilter** — `@Component`, `@Order(HIGHEST_PRECEDENCE + 10)`,
  `OncePerRequestFilter`. Reads/generates `X-Request-Id`, puts it in MDC as `requestId`, and after
  the chain completes (in a `finally`) populates `userId` MDC from
  `SecurityContextHolder`'s `TroveUserDetails` principal and logs one status-aware completion
  line (`info`/`warn`/`error` by status bucket). MDC always cleared in `finally`. Depends on
  `TroveUserDetails` from `security/` — so `observability` and `security` are not actually
  separable packages despite the directory split.

### plugin/ (bank-source plugin contract — folds into core per the owner's decision)
- **BankSourcePlugin** — interface: `getId/getDisplayName/getDescription`,
  `testConnection(credentials)`, `fetchTransactions(accountId, from, to, credentials)`. The
  contract every bank-source plugin (SimpleFin, OFX, formerly IMAP) implements.
- **RawTransaction** — record: normalised transaction shape returned by every plugin
  (`id, sourceId, accountId, amount, currency, description, merchantName, date, postedAt, type,
  pending, metadata`). Per 01-FINDINGS O7, `sourceId` is the ambiguous field renamed to
  `providerTransactionId` in the recommended fix — do that rename here, at the shared-type level,
  so it can't recur.
- **TransactionType** — enum `{DEBIT, CREDIT, TRANSFER, FEE, INTEREST, REFUND, OTHER}`.
- **BankSourceConnectionException** — unchecked, deliberately independent of `TroveException`
  (no Spring `HttpStatus` dependency) so plugin code has no Spring coupling.
- **Verified sole consumer**: `grep -rln "BankSourcePlugin\|RawTransaction\|TransactionType"
  services/` returns **only** `services/ingestion-service/**` (11 files: `ConnectedAccount`,
  `DashboardController`, `DeduplicationResult`, `DeduplicationService`, `PluginRegistry`,
  `SyncLog`, `SyncOrchestrator`, `Transaction`, `TransactionController`, `TransactionQuery`,
  `TroveIngestionApplication`). No other service touches this package. This corroborates
  01-FINDINGS M3/M4 ("no plugin architecture" — the interface exists and has exactly one
  consumer module, ingestion-service, which is where it should simply live as a package once
  simplefin/ofx fold into core.

### security/
- **JwtAuthFilter** — `@Component`, `OncePerRequestFilter`. Bypasses `/internal/**`
  (`shouldNotFilter`). Parses `Authorization: Bearer <token>`, verifies with
  `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))` (**HS256**, confirmed by
  `services/api-gateway/src/test/java/.../JwtAlgorithmTest.java:42`:
  `EXPECTED_ALG = "HS256"`, with a comment saying to flip to RS256 — never done). Claims schema:
  `sub` = userId (UUID string), `email`, `roles` (JSON string array). On any failure
  (`ExpiredJwtException`, `JwtException`, `IllegalArgumentException` incl. an unparseable `sub`)
  writes a 401 `ApiResponse` JSON body directly and stops the chain — no controller code runs.
  On success, sets `SecurityContextHolder` with a `TroveUserDetails` principal.
- **JwtProperties** — `@ConfigurationProperties("trove.jwt")`: `secret`, `expirySeconds=900`,
  `refreshExpirySeconds=86400`. `@PostConstruct validate()` throws `IllegalStateException` if
  `secret` is null/blank/<32 chars — but does not check for the well-known **weak default**
  itself (`TROVE_JWT_SECRET:-change-me-minimum-32-chars-in-production!!`, 01-FINDINGS G5), which
  is exactly 32+ chars and therefore passes this check while being public knowledge.
- **SecurityConfig** — `@Configuration @EnableWebSecurity`. Stateless (`SessionCreationPolicy.
  STATELESS`), CSRF disabled (comment: "JWTs replace session cookies" — this is the shared config;
  the public internet exposure decision means CSRF needs to be reconsidered for any
  cookie-based path in trove-core, see Risks). Registers CSP, X-Frame-Options DENY, HSTS
  (1yr + includeSubDomains, applied via `AnyRequestMatcher`), Referrer-Policy
  (STRICT_ORIGIN_WHEN_CROSS_ORIGIN), Permissions-Policy (camera/mic/geo denied). Custom
  `authenticationEntryPoint` writes a minimal inline JSON 401 (**not** `ObjectMapper`-serialised
  `ApiResponse` — it's a hand-written string literal,
  `SecurityConfig.java:80`: `"{\"success\":false,\"error\":{\"status\":401,\"title\":\"Unauthorized\"}}"`,
  which is missing `type`/`detail`/`instance`/`meta` fields that every other error path includes —
  a minor shape inconsistency worth fixing in trove-core's single handler). Registers
  `requestIdMdcFilter` then `jwtAuthFilter` via `addFilterBefore(...,
  UsernamePasswordAuthenticationFilter.class)`. **permitAll list** (`SecurityConfig.java:85-88`):
  `/test/**`, `/actuator/health`, `/actuator/prometheus`, `/api/v1/auth/**`, `/api/v1/health`,
  `/api/health`. `/test/**` open in the shared production config is 01-FINDINGS G9, confirmed
  verbatim in this file. Also explicitly disables Spring Boot's auto-registration of
  `RequestIdMdcFilter` as a standalone servlet filter via a `FilterRegistrationBean` with
  `setEnabled(false)` (`SecurityConfig.java:104-110`) — the javadoc explains why (ordering vs.
  `SecurityContextHolderFilter`) — but performs **no equivalent disablement for `JwtAuthFilter`**,
  which is the root of the double-registration defect (see §3).
- **TroveUserDetails** — `implements UserDetails`: `{userId (UUID), email, roles (Set<String>)}`.
  `getPassword()` returns `null` (JWT-only auth, no password ever checked at service layer).
  `getUsername()` returns email. `getAuthorities()` maps `roles` to `SimpleGrantedAuthority`.

### ServiceTokenAuthFilter — NOT in libs/common
The task brief lists `ServiceTokenAuthFilter` alongside the shared classes above, but it is
**not part of this module**. Verified: `grep -rln "ServiceTokenAuthFilter" --include="*.java" .`
finds it only under `services/notification-service/src/main/java/finance/trove/notification/
config/ServiceTokenAuthFilter.java` (+ its test), a service-local class for
notification-service's separate `/internal/**` security chain. It is documented here (§4) as a
*pattern* trove-core should generalise into a shared service-to-service auth mechanism, but there
is no shared class today to inventory — do not look for it in `libs/common`.

## 2. Consumers — who `@Import`s what

`grep -rn "@Import(" services/` (main code only):

| Service | Imports `finance.trove.common.*` |
|---|---|
| budget-service | `SecurityConfig, JwtAuthFilter, JwtProperties, GlobalExceptionHandler, RequestIdMdcFilter` |
| categorisation-service | same 5-class list |
| ingestion-service | same 5-class list |
| notification-service | same 5-class list (+ its own local `ServiceTokenAuthFilter`, `@Order(1)` chain for `/internal/**`) |
| receipt-processor | same 5-class list |
| report-service | same 5-class list |
| subscription-detector | same 5-class list |
| tax-engine | same 5-class list (module is being cut entirely, per owner decision) |
| **api-gateway** | `RequestIdMdcFilter` **only** — has its own `GatewaySecurityConfig`, own JWT issuance/cookie logic, own `FilterRegistrationBean<RequestIdMdcFilter>` disablement (correctly mirrored from the pattern in common's `SecurityConfig`) |
| **model-manager** | `RequestIdMdcFilter` **only** — has its own local `SecurityConfig` that does **not** import `JwtAuthFilter`/`SecurityConfig` from common. This is 01-FINDINGS M1 (model-manager has no auth) confirmed structurally here: it is the one service that hand-rolled its own config instead of the 5-class import and silently dropped JWT enforcement in the process. |

So 8 of 10 services use the identical 5-class copy-paste `@Import` list (O2's "hand-duplicate a
5-class @Import list" finding, verified exact and complete here); 2 opted out and one of those two
opted out into a security hole.

## 3. Defects verified in this pass

1. **G9 (confirmed, exact text match)** — `/test/**` is `permitAll` in the shared
   `SecurityConfig.java:85-88`, inherited by all 8 services that import it.
2. **G11 (confirmed, mechanism explained)** — `GlobalExceptionHandler` has no
   `ResponseStatusException` handler; its 7 handlers are exhaustively listed in §1. A thrown
   `ResponseStatusException` falls through to the `Exception.class` catch-all → 500 instead of the
   intended status. Additionally (new in this pass): no `AccessDeniedException` handler either,
   and `SecurityConfig` sets an `authenticationEntryPoint` but never an `accessDeniedHandler`, so
   403s bypass the JSON envelope entirely — currently latent because only api-gateway/model-manager
   use role checks, and neither imports this `SecurityConfig`.
3. **JwtAuthFilter double registration (new, verified via a real fix elsewhere in the repo)** —
   `JwtAuthFilter` is `@Component` (implements `Filter` via `OncePerRequestFilter`) *and* is added
   to the Spring Security chain via `SecurityConfig`'s `addFilterBefore(jwtAuthFilter, ...)`.
   Spring Boot auto-registers every `Filter` bean as a standalone servlet-container filter unless
   told otherwise via a disabled `FilterRegistrationBean` — exactly the mechanism `SecurityConfig`
   already uses to protect `RequestIdMdcFilter` (`SecurityConfig.java:104-110`, with a javadoc
   explaining why). **No equivalent bean exists for `JwtAuthFilter` in `libs/common`.** Proof this
   is a real, known bug and not a false read: `services/notification-service/src/main/java/
   finance/trove/notification/config/NotificationSecurityConfig.java:53-58` adds exactly this
   missing `FilterRegistrationBean<JwtAuthFilter>` with `setEnabled(false)`, and its javadoc
   states plainly: *"Without this bean, JwtAuthFilter runs after the Security proxy completes,
   causing 401 on valid service-token requests to /internal/** endpoints."* Only
   notification-service carries this fix. The other 7 importers of `JwtAuthFilter` —
   **budget-service, categorisation-service, ingestion-service, receipt-processor, report-service,
   subscription-detector, tax-engine** — do not, and so run `JwtAuthFilter` twice per request: once
   as a bare auto-registered servlet filter (which populates a `SecurityContext` that Spring
   Security's `SecurityContextHolderFilter` then wipes when the request re-enters the actual
   `FilterChainProxy`), and once again inside the security chain. Net effect today is doubled JWT
   parsing/log noise per request rather than a broken-auth outcome (the in-chain run "wins" because
   it runs last), but it is real duplicated work on every request in 7 services and the exact
   mechanism that, per notification-service's own comment, *does* break `/internal/**` routing —
   worth fixing structurally, not service-by-service, in trove-core.
4. **JacksonConfig is dead code (new)** — see §1/§4. Zero services import it; the SNAKE_CASE
   strategy it would apply never runs anywhere in the live system.
5. **ErrorDetail 403 envelope gap (new)** — see §1 GlobalExceptionHandler / SecurityConfig entries;
   `authenticationEntryPoint`'s hand-written 401 JSON string omits `type`/`detail`/`instance`,
   inconsistent with every other error path's fully-populated `ErrorDetail`.
6. **O2 confirmed exactly** — no `META-INF/spring/org.springframework.boot.autoconfigure.
   AutoConfiguration.imports` file exists anywhere under `libs/common` (`find libs/common
   -iname "*.imports"` → empty), versus `libs/privacy-engine/src/main/resources/META-INF/spring/
   org.springframework.boot.autoconfigure.AutoConfiguration.imports`, which contains exactly one
   line: `finance.trove.privacy.PrivacyAutoConfiguration`. That one line is the entire pattern to
   copy.

## 4. Target core design for trove-core

**Casing decision, resolved by evidence, not preference:** grep of `apps/web/src` and
`apps/mobile/src` for `field_errors`/`fieldErrors` finds **zero occurrences of `field_errors`
anywhere in either client** (`grep -rn "field_errors" apps/web/src apps/mobile/src` → no hits).
Client DTOs and mock handlers uniformly use camelCase field names —
`apps/mobile/src/api/types.ts:3` (`accountId`), `apps/web/src/test/handlers/transactionsV1.ts:8-9,20-21`
(`userId`, `accountId`), `apps/web/src/test/handlers/categoriesV1.ts:6-7` (`parentId`),
`apps/web/src/test/handlers/budgetsV1.ts:15,47` (`categoryId`), `apps/web/src/test/handlers/
coach.ts:13` (`sessionId`). `openapi/budget-service.yaml:873` independently spells the field
`fieldErrors`. Combined with the fact that `JacksonConfig`'s SNAKE_CASE customizer is imported by
**no service** (§1/§3), the runtime wire format every client is actually built against is
**camelCase Jackson defaults**. trove-core must **not** carry `JacksonConfig`'s SNAKE_CASE
strategy forward — keep Jackson's default naming (no `PropertyNamingStrategies` override), keep
`ErrorDetail.fieldErrors` as-is, and delete `JacksonConfig` (or repurpose it to only carry the
`FAIL_ON_UNKNOWN_PROPERTIES`-disable behavior, which is orthogonal to casing and harmless).

Consolidate to exactly one of each, all in a single `finance.trove.core.security`/`.web`/
`.error` package set inside `trove-core`, auto-wired via a proper
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` file copied
from `libs/privacy-engine`'s pattern (O2) so no future package inside the monolith can silently
opt out the way model-manager did:

- **One `SecurityConfig`** — merge the 8 near-identical service copies plus api-gateway's and
  model-manager's bespoke ones into a single filter chain. Fix in the merge, not after:
  (a) add the missing `FilterRegistrationBean<JwtAuthFilter>` disablement (defect 3 above) so it
  can never recur; (b) remove `/test/**` from the permanent `permitAll` list — gate any
  test/smoke endpoints behind a profile instead (G9); (c) add an `accessDeniedHandler` alongside
  the existing `authenticationEntryPoint`, both emitting a fully-populated `ErrorDetail` via the
  same `ObjectMapper`-based serialisation `JwtAuthFilter.writeUnauthorized` already uses, instead
  of `SecurityConfig`'s hand-written JSON string literal (defect 5); (d) since deployment is now
  public-internet-facing behind Traefik with real TLS, re-evaluate whether CSRF can stay fully
  disabled for every path, or whether cookie-based flows (api-gateway's `CookieJwtAuthFilter`,
  folded into core) need it re-enabled for those routes specifically — audit before assuming
  "stateless JWT ⇒ no CSRF" holds monolith-wide once cookie auth (currently api-gateway-only) is
  merged in.
- **One `JwtAuthFilter`** — keep HS256/JJWT 0.12 as-is (rotating to RS256 is a separate, larger
  decision not in scope here), but load the secret from a generated, unique-per-deployment value
  (G5) rather than the `change-me-minimum-32-chars-in-production!!` default env-var fallback —
  `JwtProperties.validate()` should reject that literal string specifically, not just check length.
- **One `GlobalExceptionHandler`** — the union of the 7 existing handlers plus the two missing
  ones identified in defect 2 (`ResponseStatusException`, `AccessDeniedException`).
- **One Jackson strategy** — default camelCase (delete `JacksonConfig`'s naming override per the
  evidence above); keep `FAIL_ON_UNKNOWN_PROPERTIES` disabled globally (lenient deserialization is
  a reasonable default for a monolith merging 10 previously-independent DTO sets while call sites
  get reconciled).
- **One RestClient/HTTP-client customizer** — currently absent entirely (`grep -rln "RestClient\|
  RestTemplate" libs/common` → no hits). trove-core collapses 10 services' worth of inter-service
  HTTP calls into in-process method calls, so most of this need disappears, but the *outbound*
  calls that remain real network calls (OpenAI-compatible AI endpoint, SimpleFin, OFX import
  source, webhook delivery) still need one shared `RestClient.Builder` customizer with explicit
  connect/read timeouts — build this new, there is nothing to port forward.
- **`ServiceTokenAuthFilter`** — generalise notification-service's local class (§1) into a shared
  internal-auth mechanism if trove-core keeps any `/internal/**`-style surface (e.g. for a
  cron/worker path within the same JVM); if everything becomes an in-process call, this pattern
  may not be needed at all and should be dropped rather than ported speculatively.
- **`BankSourcePlugin`/`RawTransaction`/`TransactionType`/`BankSourceConnectionException`** — move
  into an ingestion package inside trove-core (verified single consumer, §1); rename
  `RawTransaction.sourceId` → `providerTransactionId` in the same change (O7).
- **Money/CurrencyCode/Jurisdiction** — port unchanged; these are the one part of this module with
  no defects found and a decision (CAD/USD, per-user setting) they already model correctly.
  `Jurisdiction`'s javadoc reference to tax-calculation dispatch is now stale (tax engine cut) —
  update the comment, keep `defaultCurrency()`.
- **`Redact`/`RedactingConverter`** — port unchanged into a shared logging config; genuinely
  useful, no defects found, no service-specific coupling.
- **No dedicated "validators" package exists** in `libs/common` today (`find libs/common -iname
  "*Validator*"` → empty) — the task brief's mention of "validators" has no corresponding class to
  inventory; Bean Validation is used ad hoc via `spring-boot-starter-validation` (present in
  `pom.xml`) and per-service `@Valid`/`@NotBlank` annotations, several of which are inert per G10
  because the *service's own* `pom.xml`, not this shared one, omits the validation starter —
  verify per-service in trove-core's single `pom.xml` (there will be exactly one, so G10 cannot
  recur once the dependency is declared once).

## 5. Risks

- **Silent auth bypass on merge**: model-manager's pattern (own `SecurityConfig`, doesn't import
  the shared one) is exactly how a package inside trove-core could end up unauthenticated after
  the merge if the single `SecurityConfig` isn't force-applied via auto-configuration (hence O2's
  recommendation to add the `.imports` file — do this **first**, before deleting anything).
- **Filter double-registration is easy to reintroduce**: any new `@Component`-annotated
  `OncePerRequestFilter` added to trove-core's single security chain needs the same
  `FilterRegistrationBean(...).setEnabled(false)` treatment `RequestIdMdcFilter` already has and
  `JwtAuthFilter` is missing — codify this as a rule (or a shared base class/factory) rather than
  relying on each future filter's author remembering notification-service's fix.
- **CSRF re-evaluation is genuinely open, not cosmetic**: this module's `SecurityConfig` disables
  CSRF wholesale on the (currently true) assumption that auth is JWT-header-only; the moment any
  cookie-based session participates (api-gateway's cookie flow does, and it's being folded into
  core), that assumption needs re-checking per-route, not globally — flag for the security review
  the owner's decisions call for (TLS/CSRF/rate-limits/HSTS all explicitly in scope).
- **`fieldErrors` vs `field_errors` is a one-time decision to get right on day one**: nothing today
  actually serialises `field_errors` (JacksonConfig is dead), so there is no real client depending
  on it — but if `JacksonConfig` were accidentally wired in during the merge (it's sitting right
  there, looks like an intentional shared config, isn't obviously dead without the grep done in
  this report), every list-validation-error response would silently rename its key and both
  frontends' error-display code would break with no compile-time signal. Delete the class, don't
  leave it lying around as an attractive nuisance.
- **JwtProperties' weak-default is a config-file problem, not a code problem** — the validation
  code in this module is fine (rejects short secrets); the vulnerability is that every service's
  `application.yml` sets `${TROVE_JWT_SECRET:-change-me-minimum-32-chars-in-production!!}` as a
  fallback. Fixing `JwtProperties` alone (e.g. blocklisting that literal) is a good defense-in-depth
  addition but the real fix is generating a real secret at deploy time and never shipping a
  fallback default for it in trove-core's compose/env files.
