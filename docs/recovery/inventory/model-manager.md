# model-manager — Inventory

Verified against source at HEAD (working tree). Cited paths are repo-relative from the repository root.

---

## 1. Purpose and LOC

`model-manager` is the AI-facing service: it terminates the "AI financial coach" chat
endpoint (proxies straight to Ollama, bypassing its own LiteLLM gateway abstraction), holds
`coach_conversation`/`coach_insight` history, runs a weekly LLM-generated insight job, exposes
an admin `ModelRegistration` catalogue with a 60s health-check scheduler, and receives
fire-and-forget AI-call audit records posted by peer services via `POST /internal/audit`.

- Main: 2595 LOC (`find services/model-manager/src/main -name '*.java' | xargs wc -l | tail -1`)
- Test: 1737 LOC (`find services/model-manager/src/test -name '*.java' | xargs wc -l | tail -1`)

---

## 2. Spring wiring

- **Application class**: `services/model-manager/src/main/java/finance/trove/modelmanager/TroveModelManagerApplication.java` — plain `@SpringBootApplication`, no extra `@Import`/`@ComponentScan` overrides.
- **`@Configuration` classes** (all in `config/`):
  - `SecurityConfig.java` — `@Configuration @EnableWebSecurity @EnableMethodSecurity @Import(RequestIdMdcFilter.class)`. Builds the one `SecurityFilterChain` bean.
  - `SchedulingConfig.java` — `@Configuration @EnableScheduling @EnableSchedulerLock(defaultLockAtMostFor="9m")`; defines the ShedLock `LockProvider` bean (JDBC-backed, `shedlock` table).
  - `YamlPropertySourceFactory.java` — plain `PropertySourceFactory`, not a `@Configuration`; used by `@PropertySource(value="classpath:coach-prompts.yml", factory=YamlPropertySourceFactory.class)` on `CoachPromptsProperties`.
- **No `@EnableAsync`** anywhere in the module — `CoachContextBuilder` parallelises its 4 peer-service fetches with raw `CompletableFuture.supplyAsync` on the common `ForkJoinPool`, not a Spring-managed `@Async` executor (`chat/CoachContextBuilder.java:191-193`).
- **Auto-config picked up from classpath** (via `libs/ai-gateway-client` and `libs/privacy-engine`, both of which ship `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`):
  - `finance.trove.ai.gateway.AiGatewayAutoConfiguration` (`libs/ai-gateway-client/src/main/java/finance/trove/ai/gateway/AiGatewayAutoConfiguration.java`) — registers `ModelRoutingService`, `LiteLlmRestClient`, `AiGatewayClient` beans. **`AiGatewayClient` and `LiteLlmRestClient` are never used by any class in model-manager** — only the `ModelRoutingService` bean is consumed (by `ChatService.resolveLocalModel()`).
  - `finance.trove.privacy.PrivacyAutoConfiguration` (`libs/privacy-engine`) — registers `PrivacyClassifier`/`PrivacyEnforcementAspect` machinery; **model-manager references only the `PrivacyTier` enum**, not the classifier or the `@PrivacyEnforced` AOP aspect.
- **Security filter chain** — `config/SecurityConfig.java:53-64`:
  ```
  .csrf(disable)
  .sessionManagement(STATELESS)
  .exceptionHandling(401 entry point)
  .addFilterBefore(requestIdMdcFilter, UsernamePasswordAuthenticationFilter.class)
  .authorizeHttpRequests(
      "/internal/**", "/actuator/**" -> permitAll
      anyRequest -> authenticated())
  ```
  **`permitAll` paths**: `/internal/**`, `/actuator/**`. Everything else requires `authenticated()`.
  **Identity derivation: THERE IS NONE.** No `JwtAuthFilter` (or any other `AuthenticationProvider`/filter that populates `SecurityContextHolder`) is imported or registered anywhere in this module (`grep -rn "JwtAuthFilter\|Jwt" services/model-manager/src/main` → zero hits). `libs/common` ships `finance.trove.common.security.JwtAuthFilter` (`libs/common/src/main/java/finance/trove/common/security/JwtAuthFilter.java`) and every other Spring Boot service in the repo explicitly `@Import`s or `@Bean`s it — model-manager is the only one that does not (verified counts below, section 10 / defect M1). Consequence: `anyRequest().authenticated()` can never be satisfied (Spring Security's default `AnonymousAuthenticationFilter` populates an `AnonymousAuthenticationToken`, which `authenticated()` explicitly excludes) — every non-`/internal`, non-`/actuator` route 401s in a real deployment. Controllers still declare `@AuthenticationPrincipal TroveUserDetails user` (e.g. `chat/ChatController.java:94`, `insight/InsightController.java:33`) as if a filter populated it; it never does. `trove.jwt.secret` is configured (`application.yml:22-23`) but read by nothing in this module — no `JwtProperties` bean is ever constructed here.
  `/internal/audit` is separately guarded in code by a constant-time shared-secret header check (`audit/AuditController.java:96-98`), independent of the (non-functional) Spring Security chain.
- **JacksonConfig / naming strategy**: no `JacksonConfig` class in this module. `ObjectMapper` is the Spring Boot default (`tools.jackson.databind.ObjectMapper` — note this is the Jackson 3 `tools.jackson` package, not `com.fasterxml.jackson.databind`, used by `ChatController`/`LiteLlmChatGateway`; meanwhile `audit/AiAuditLog.java:3` uses `com.fasterxml.jackson.annotation.JsonAutoDetect` from Jackson 2 — **two Jackson major-version packages mixed in the same module**). No explicit `PropertyNamingStrategy` is configured; DTOs rely on default camelCase field names (records `DashResponse`, `BudgetResponse`, etc. in `CoachContextBuilder`) — this only works because the peer services being called (`ingestion-service`, `budget-service`) also emit camelCase.
- **`@EnableScheduling`**: `config/SchedulingConfig.java:14`. Two `@Scheduled` methods use it — `model/ModelHealthScheduler.checkHealth()` and `insight/WeeklyInsightScheduler.generateWeeklyInsights()` (see section 7).
- **`@EnableAsync`**: absent (confirmed above).

---

## 3. HTTP routes

| Method | Path | Controller#method | Identity source | Request DTO | Response DTO | Notes |
|---|---|---|---|---|---|---|
| POST | `/v1/chat/completions` | `ChatController.complete` (`chat/ChatController.java:93`) | `@AuthenticationPrincipal TroveUserDetails` (never populated — see §2) | `Map<String,Object>` body (`{messages:[...]}`, OpenAI-shaped) + `X-Session-Id` (opt, UUID, auto-gen if absent) + `X-Tax-Jurisdiction` header (default `CA`) + `stream` query param (default `false`) | SSE stream (`stream=true`) of `data: {"choices":[{"delta":{...}}]}\n\n` frames terminated by `data: [DONE]\n\n`, or JSON `ApiResponse<{choices:[{message:{role,content}}]}>` (`stream=false`) | Always routes `LOCAL_QUALITY`/`HIGH` (calls Ollama directly, not via `LiteLlmChatGateway`/`AiGatewayClient`). Sets response headers `X-Model-Used`, `X-Privacy-Tier`, `X-Session-Id`. Bad `X-Session-Id` → 400 `ApiResponse.error`. |
| GET | `/v1/chat/suggestions` | `ChatController.suggestions` (`:163`) | same | — | `ApiResponse<List<String>>` | Keyword-matched against most recent turn (`SuggestionService`). |
| GET | `/v1/chat/history` | `ChatController.history` (`:198`) | same | query `sessionId` (UUID, required) | `ApiResponse<List<CoachConversation>>` | SYSTEM rows excluded, oldest-first. |
| DELETE | `/v1/chat/history` | `ChatController.deleteHistory` (`:229`) | same | query `sessionId` (UUID, optional — all sessions if omitted) | `ApiResponse<Void>` | Idempotent. |
| GET | `/v1/insights` | `InsightController.list` (`insight/InsightController.java:33`) | same | — | `ApiResponse<List<CoachInsight>>` | Undelivered only, newest first; no pagination param. |
| GET | `/v1/models` | `ModelController.listActiveModels` (`model/ModelController.java:72`) | any authenticated user | — | `List<ModelRegistration>` | No `ApiResponse` envelope (raw list) — inconsistent with rest of module. |
| POST | `/v1/models/{id}/active` | `ModelController.toggleActive` (`:101`) | `@PreAuthorize("hasRole('ADMIN')")` | path `id` (UUID), query `active` (boolean) | `ModelRegistration` | 404 if id unknown. |
| GET | `/v1/models/routing` | `ModelController.getRoutingTable` (`:135`) | ADMIN only | — | `List<ModelRegistration>` | Identical body to `/v1/models`. |
| POST | `/v1/models/pull` | `ModelController.pullModel` (`:164`) | ADMIN only | `{alias}` | `{alias, status:"pull_requested"}` (202) | Stub — no real pull orchestration exists anywhere in the repo. |
| GET | `/v1/models/{alias}/health` | `ModelController.modelHealth` (`:194`) | any authenticated user | path `alias` | `{alias, healthy, lastHealthAt}` | 404 if alias unknown. |
| POST | `/v1/embeddings` | `EmbeddingsController.createEmbeddings` (`embeddings/EmbeddingsController.java:59`) | **no `@AuthenticationPrincipal`/auth annotation at all** — falls through only to the blanket `anyRequest().authenticated()` chain rule | `{input, model?}` | OpenAI-shaped `{object,data:[{object,embedding,index}],model,usage}` | Stub: always returns a single `[0.0]` vector regardless of input. `model` defaults to `LOCAL_FAST`. |
| GET | `/v1/audit` | `AuditController.listAuditLogs` (`audit/AuditController.java:74`) | ADMIN only | Spring Data `Pageable` (`page`,`size`,`sort`) | `Page<AiAuditLog>` | |
| POST | `/internal/audit` | `AuditController.receiveAuditLog` (`:106`) | **not JWT** — `X-Service-Token` header, constant-time-compared against `trove.internal.service-token` | `AiAuditLog` (full entity, client-supplied — including `id`/`createdAt` fields, since Jackson field-visibility is `ANY`) | `AiAuditLog` (201) | `permitAll` path. 401 if token absent/mismatched. |

13 routes total across 4 controllers. No route in the module uses `@PageableDefault`/`sort` whitelisting beyond Spring Data defaults on `/v1/audit`.

---

## 4. Persistence

**Database name** (`application.yml:11`): `${MODELS_POSTGRES_URL}` → per `.env.example:6`, `jdbc:postgresql://localhost:5432/trove_models` — DB name `trove_models`.

**`ddl-auto: validate`** (`application.yml:14`) — schema is Flyway-only; entities must match exactly or the context fails to start.

### Entities → tables

| Entity | Table | Key columns | FKs | Unique / indexes |
|---|---|---|---|---|
| `ModelRegistration` (`model/ModelRegistration.java:359`) | `model_registration` | `id` PK, `alias`, `provider`, `model_id`, `is_local`, `min_privacy_tier`, `context_window`, `is_active`, `is_healthy`, `last_health_at`, `created_at` | none | `alias` UNIQUE (`V1__model_registration.sql:3`) |
| `CoachConversation` (`chat/CoachConversation.java:29`) | `coach_conversation` | `id` PK, `user_id`, `session_id`, `role` (CHECK `SYSTEM/USER/ASSISTANT`), `content`, `privacy_tier`, `model_used`, `created_at` | none (no FK to a `users` table — `user_id` is a bare UUID, cross-service ownership is trusted, not enforced) | `idx_coach_user_session(user_id,session_id,created_at)`, `idx_coach_user_id(user_id,created_at DESC)` (`V5`) |
| `CoachInsight` (`insight/CoachInsight.java:11`) | `coach_insight` | `id` PK, `user_id`, `insight_text`, `delivered`, `created_at`, `delivered_at` | none | partial index `idx_coach_insight_user_undelivered(user_id,delivered,created_at DESC) WHERE delivered=false` (`V6`) |
| `AiAuditLog` (`audit/AiAuditLog.java:37`) | `ai_audit_log` | `id` PK, `user_id`, `service`, `alias`, `provider`, `model_id`, `privacy_tier`, `prompt_tokens`, `output_tokens`, `latency_ms`, `success`, `error_message`, `created_at` | none | `idx_audit_log_user_id`, `idx_audit_log_created_at` (`V2`) |
| — (ShedLock, not a JPA entity) | `shedlock` | `name` PK, `lock_until`, `locked_at`, `locked_by` | — | — |

**Note on `CoachConversation.sessionId`**: entity declares `@Column(name="session_id", nullable=false)` (`chat/CoachConversation.java:57`) but the column was added by `V4__coach_conversation_session.sql:2` with no `NOT NULL` and never tightened by `V5`. `ddl-auto=validate` in current Hibernate does not fail startup on a nullability mismatch, so this drift is silent — a row written by JDBC bypassing the entity (or a future migration) could leave `session_id` null with no DB-level guard.

### Repositories with custom `@Query`

- `CoachConversationRepository` (`chat/CoachConversationRepository.java`):
  - `findSystemRowForSession` (`:47`) — `@Query` JPQL, `role='SYSTEM'`.
  - `findMostRecentByUserId(userId, Pageable)` (`:59`) — `@Query` JPQL ordered `createdAt DESC`, paged.
  - `findDistinctUserIds()` (`:82`) — `@Query("SELECT DISTINCT c.userId FROM CoachConversation c")` — used only to seed the weekly insight job's user set (see §7 — this is the "active users" enumeration, and it is *conversation* history, not account/user-table membership).
  - Derived: `findByUserIdOrderByCreatedAtAsc`, `findByUserIdAndSessionIdOrderByCreatedAtAsc`, `deleteByUserIdAndSessionId`, `deleteByUserId`.
- `CoachInsightRepository` — derived only: `findByUserIdAndDeliveredFalseOrderByCreatedAtDesc`.
- `AiAuditLogRepository` — derived only: `findByUserIdOrderByCreatedAtDesc` (declared, not actually called by any controller — `/v1/audit` uses `findAll(Pageable)`, unfiltered by user).
- `ModelRepository` — derived only: `findByActiveTrue`, `findByAlias`.

### Flyway migrations

| File | Summary | Tables/indexes touched |
|---|---|---|
| `V1__model_registration.sql` | Creates `model_registration`; seeds 4 rows (`LOCAL_FAST`/OLLAMA, `LOCAL_QUALITY`/OLLAMA, `HYBRID_EFFICIENT`/LITELLM, `CLOUD_QUALITY`/LITELLM). | `model_registration` |
| `V2__ai_audit_log.sql` | Creates `ai_audit_log` + 2 indexes. | `ai_audit_log` |
| `V3__coach_conversation.sql` | Creates `coach_conversation` with lowercase `role` CHECK (`user`/`assistant`) and `model_alias` column. | `coach_conversation` |
| `V4__coach_conversation_session.sql` | Adds `session_id`, `privacy_tier` (default `LOW`), `model_used`; index on `session_id`. | `coach_conversation` |
| `V5__coach_conversation_fix.sql` | Drops old role/privacy-tier CHECKs; uppercases existing `role` data; adds `SYSTEM` to the CHECK; makes `privacy_tier` NOT NULL default `HIGH`, backfills `LOW`→`HIGH`; **drops `model_alias`**; adds 2 composite indexes. | `coach_conversation` — this migration exists purely to patch a design change mid-flight (role casing + privacy-tier default), evidence the entity/table were reworked after initial ship. |
| `V6__coach_insight.sql` | Creates `coach_insight` + partial index. | `coach_insight` |
| `V7__create_shedlock.sql` | Creates `shedlock` table (ShedLock's required schema). | `shedlock` |

---

## 5. Config — every property in `application*.yml`

Only one file: `services/model-manager/src/main/resources/application.yml` (no profile-specific overlays exist in `src/main/resources`).

| Key | Env var | Default | Secret? |
|---|---|---|---|
| `server.port` | — | `8089` | no |
| `spring.application.name` | — | `model-manager` | no |
| `spring.profiles.default` | — | `local` | no |
| `spring.datasource.url` | `MODELS_POSTGRES_URL` | **none — required** | no (but shape leaks DB host) |
| `spring.datasource.username` | `MODELS_POSTGRES_USER` | **none — required** | yes |
| `spring.datasource.password` | `MODELS_POSTGRES_PASSWORD` | **none — required** | yes — `.env.example:8` ships `trove`/`trove`, flagged `# CHANGE IN PRODUCTION` |
| `spring.jpa.hibernate.ddl-auto` | — | `validate` | no |
| `spring.jpa.open-in-view` | — | `false` | no |
| `spring.flyway.enabled` | — | `true` | no |
| `spring.flyway.locations` | — | `classpath:db/migration` | no |
| `trove.jwt.secret` | `TROVE_JWT_SECRET` | **none — required, but read by nothing** (§2) | yes — `.env.example:12` ships a placeholder flagged `CHANGE IN PRODUCTION` |
| `trove.ollama.url` | `OLLAMA_URL` | `http://ollama:11434` (`${VAR:-default}` placeholder) | no |
| `trove.coach.ingestion-url` | `INGESTION_URL` | `http://ingestion-svc:8081` | no — **hardcoded service hostname/port as fallback** |
| `trove.coach.budget-url` | `BUDGET_URL` | `http://budget-svc:8084` | no — same |
| `trove.coach.subscription-url` | `SUBSCRIPTION_URL` | `http://subscription-det:8086` | no — same |
| `trove.coach.context-fetch-timeout-seconds` | — | `5` | no |
| `trove.coach.max-history-messages` | — | `20` | no |
| `management.server.port` | — | `9090` | no |
| `management.endpoints.web.exposure.include` | — | `health,info,prometheus,metrics` | no |

**Properties consumed by code but absent from `application.yml` entirely** (relying on bare `@Value` defaults or undeclared env-var relaxed-binding — flag these explicitly, they are easy to lose in a merge):

| Key (code site) | Default in code | Secret? | Gotcha |
|---|---|---|---|
| `trove.internal.service-token` (`audit/AuditController.java:47`) | **none** — `@Value("${trove.internal.service-token}")` with no `:default` | yes | Not in `application.yml` at all. Only resolves because `TROVE_INTERNAL_SERVICE_TOKEN` (docker-compose env var) happens to relaxed-bind to this key. If that env var is ever renamed/dropped without updating this `@Value`, the app fails to start with no obvious cause. |
| `trove.litellm.url` (`chat/LiteLlmChatGateway.java:30`) | `http://litellm-gateway:4000` | no | Same "not in application.yml" gap; happens to match the compose service DNS name so it silently works. |
| `trove.litellm.master-key` (`chat/LiteLlmChatGateway.java:31`) | `""` (empty string) | yes | **Broken wiring, not just missing default**: docker-compose sets env var `LITELLM_MASTER_KEY`, but the property key is `trove.litellm.master-key` — relaxed binding requires `TROVE_LITELLM_MASTER_KEY`, which is never set anywhere. This key is *never* populated by any deployment config in the repo → `masterKey` is always `""` → every outbound LiteLLM call sends `Authorization: Bearer ` (empty). See defect D3. |
| `trove.litellm.default-model` (`:32`) | `llama3:8b` | no | Same gap; harmless because default matches intent. |

**Every `${VAR:-default}` / `${VAR:default}` placeholder in this module**: `OLLAMA_URL` (`:http://ollama:11434`), `INGESTION_URL`, `BUDGET_URL`, `SUBSCRIPTION_URL` (all in `application.yml`), plus the three undeclared `trove.litellm.*`/`trove.coach.*fetch fallback in `CoachContextBuilder`'s constructor `@Value` defaults (`chat/CoachContextBuilder.java:47-50`, same URLs repeated as a second independent set of `${VAR:-default}` — see §12 duplication risk).

`coach-prompts.yml` (bound via `@PropertySource`+`YamlPropertySourceFactory` into `CoachPromptsProperties`) is prompt/template content, not deployment config — not itemized here; it does define `trove.coach.max_history_messages: 20` (`coach-prompts.yml:37`), which is a **second, independent source for the same logical setting** as `application.yml`'s `trove.coach.max-history-messages: 20` (§12).

---

## 6. Outbound HTTP

| Target | Path | Headers set | Timeouts | file:line |
|---|---|---|---|---|
| Ollama (`trove.ollama.url`, default `http://ollama:11434`) | `POST /api/chat` (streaming) | `Content-Type: application/json` only — **no `Authorization`, no `X-User-Id`** | **none** — `RestClient.Builder` used with no `ClientHttpRequestFactory` timeout configuration anywhere in the module | `chat/ChatController.java:255-268` (`streamResponse`) |
| Ollama (same) | `POST /api/chat` (non-stream) | same | none | `chat/ChatController.java:279-283` (`nonStreamResponse`) |
| ingestion-service (`trove.coach.ingestion-url`) | `GET /v1/dashboard/today` | `X-Trove-User-Id: <userId>` | none | `chat/CoachContextBuilder.java:115-117` (`fetchDashboard`) |
| budget-service (`trove.coach.budget-url`) | `GET /v1/budgets` | `X-Trove-User-Id: <userId>` | none | `chat/CoachContextBuilder.java:151-153` (`fetchBudget`) |
| budget-service (same) | `GET /v1/networth` | `X-Trove-User-Id: <userId>` | none | `chat/CoachContextBuilder.java:209-211` (`fetchNetWorth`) |
| subscription-detector (`trove.coach.subscription-url`) | `GET /v1/subscriptions/summary` | `X-Trove-User-Id: <userId>` | none | `chat/CoachContextBuilder.java:183-185` (`fetchSubscriptions`) |
| LiteLLM (`trove.litellm.url`, default `http://litellm-gateway:4000`) | `POST /v1/chat/completions` | `Content-Type: application/json`, `Authorization: Bearer <masterKey>` (masterKey always `""` in practice — §5) | none | `chat/LiteLlmChatGateway.java:56-62` |

**Verified header-name bug** (special-attention item): `CoachContextBuilder` sends `X-Trove-User-Id` (`chat/CoachContextBuilder.java:117,153,185,211`) to all four peer calls. Cross-checked against the actual peer controllers:
- `ingestion-service`'s `DashboardController` reads `@RequestHeader(value="X-User-Id", required=false)` (`services/ingestion-service/src/main/java/finance/trove/ingestion/dashboard/DashboardController.java:93`) — wrong header name means the header is simply never seen; the endpoint is `required=false` and silently degrades to **zeroed dashboard data for every user** (not an error, not even logged) — see `unavailableDash()` fallback path being hit even though the HTTP call itself returns 200.
- `budget-service`'s `BudgetController`/`NetWorthController` and `subscription-detector`'s `SubscriptionController` don't read any `X-*-User-Id` header at all — they resolve identity from `@AuthenticationPrincipal TroveUserDetails`, i.e. **require a validated JWT** (`services/budget-service/src/main/java/finance/trove/budget/budget/BudgetController.java:51`, `services/budget-service/.../networth/NetWorthController.java`, `services/subscription-detector/.../SubscriptionController.java:56` etc.). `CoachContextBuilder` never forwards any `Authorization` header to these three calls, so they 401 unconditionally regardless of the header-name bug — caught by the blanket `catch (Exception e)` in each `fetch*` method and silently rendered as `(data unavailable)`.
- Net effect: **the coach's "personalised financial context" is always `(data unavailable)` for budget/net-worth/subscriptions, and always zero-valued for the dashboard section, for every user, in every environment** — the headline feature never sees real data even where JWT/auth issues (M1) are hypothetically fixed. `CoachContextBuilderTest` (`src/test/java/finance/trove/modelmanager/chat/CoachContextBuilderTest.java`) stubs WireMock with `urlPathMatching` only — it never asserts on the outbound *header name*, so this bug is invisible to the test suite (see §9, §10 D1).

---

## 7. Scheduled / async

- `ModelHealthScheduler.checkHealth()` (`model/ModelHealthScheduler.java:280`) — `@Scheduled(fixedDelay=60_000)`, **no `@SchedulerLock`** (unlike the weekly job) despite `SchedulingConfig` enabling ShedLock module-wide — if model-manager is ever scaled to >1 replica this runs on every instance concurrently with no coordination. Enumerates `ModelRepository.findByActiveTrue()` (currently the 4 seed rows from `V1`), probes a `ModelAdapter` bean selected by `provider` string (`OLLAMA`/`LITELLM`/`PADDLEOCR`) via 3 `@Autowired(required=false)` fields. **No class anywhere in the repo implements `finance.trove.model.adapter.ModelAdapter`** (verified: `grep -rln "implements ModelAdapter" services/ libs/` → zero hits outside the interface's own declaration) — all three fields are permanently `null` in a real Spring context, so every registration is unconditionally marked `healthy=false` on every tick, forever (see defect D2). Test coverage (`model/ModelHealthSchedulerTest.java`) only exercises this via package-private setters that inject mocks directly, bypassing Spring wiring entirely — it cannot and does not catch this.
- `WeeklyInsightScheduler.generateWeeklyInsights()` (`insight/WeeklyInsightScheduler.java:19`) — `@Scheduled(cron="0 0 8 * * MON")` + `@SchedulerLock(name="weekly-insight", lockAtMostFor="PT30M")`. Enumerates `CoachConversationRepository.findDistinctUserIds()` — i.e. **only users who have at least one `coach_conversation` row**, not all registered users; a user who has never opened the AI chat once never receives a weekly insight even if they have rich transaction/budget data. For every enumerated user it calls `InsightGenerationService.generateInsightForUser(userId, "CA")` with **`"CA"` hardcoded** as the tax jurisdiction (`:26`) — ignores the user's actual locale/currency entirely; the owner's per-user CAD/USD decision has no effect on this job. `WeeklyInsightSchedulerTest.java:28-32` asserts `generateInsightForUser(userId1, "CA")` as the expected call — **the test encodes the hardcoded-CA bug as correct behavior** (defect D5 / §9).
- No `@Async` methods anywhere in the module (confirmed — `grep -rn "@Async" services/model-manager/src/main` → zero hits); `CoachContextBuilder`'s fan-out uses raw `CompletableFuture.supplyAsync(...)` on the JVM-common `ForkJoinPool.commonPool()`, not a request-scoped or Spring-managed executor.
- No Spring application events (`ApplicationEvent`/`@EventListener`) anywhere in the module.

---

## 8. External dependencies

- **Postgres** — `trove_models` database (own schema, own Flyway history; §4).
- **Ollama** — called directly by `ChatController` for chat completions (`trove.ollama.url`, default `http://ollama:11434`); also nominally probed by `ModelHealthScheduler` via a `ModelAdapter` bean that does not exist (§7).
- **LiteLLM** (`litellm-gateway`, per `docker/docker-compose.yml:95-103`) — used only by `LiteLlmChatGateway`, which itself is used only by `InsightGenerationService` (the weekly-insight job) — **not** by the interactive chat path. Auth to it is broken (§5/§6).
- **ai-gateway-client / LiteLlmRestClient** (`libs/ai-gateway-client`) — auto-configured but its `AiGatewayClient`/`LiteLlmRestClient` beans are dead code in this module (nothing injects them); only `ModelRoutingService` is used.
- **MinIO, PaddleOCR, SimpleFIN, Authentik, SMTP** — no references anywhere in `services/model-manager` (all cut per owner decisions; nothing to migrate from this module).
- **ShedLock** (own `shedlock` table in `trove_models`, not an external service).
- Peer HTTP dependencies (in-mesh, not external infra): `ingestion-service`, `budget-service`, `subscription-detector` (all called by `CoachContextBuilder`, all effectively broken per §6).

---

## 9. Tests

85 `@Test` methods total across 22 test classes (`grep -rc "@Test" ... | sum` = 85).

By type:
- **Pure unit (Mockito, no Spring context)**: `ChatServiceTest` (4), `SuggestionServiceTest` (5), `CoachPromptsPropertiesTest` (5), `CoachConversationTest` (5), `ModelRoutingRegistryTest` (5), `InsightGenerationServiceTest` (2), `WeeklyInsightSchedulerTest` (3), `ModelHealthSchedulerTest` (4) — 33 tests. Mock the repository/gateway/adapter collaborators directly; several construct the class-under-test by hand (`new ChatService(...)`, `scheduler.setOllamaAdapter(...)`) bypassing all Spring wiring.
- **WireMock-backed unit**: `CoachContextBuilderTest` (5), `LiteLlmChatGatewayTest` (5) — 10 tests. Real HTTP over loopback to a stub server; never assert outbound request headers for `CoachContextBuilder` (§6).
- **`@WebMvcTest`/MockMvc slice**: `ChatControllerTest` (6), `AuditControllerTest` (3), `EmbeddingsControllerTest` (2), `ModelControllerTest` (8), `InsightControllerTest` (3) — 22 tests. All inject authentication directly via `@WithMockUser` or `.with(authentication(...))`, never exercising the real (absent) auth filter chain — this is exactly what masks M1 (§10).
- **`@SpringBootTest`/integration (Testcontainers Postgres or H2)**: `ChatControllerIT` (`chat/` package, 7 tests, Testcontainers+WireMock), `ChatControllerIT` (`integration/` package, **a second, differently-implemented class with the identical simple name**, 3 tests, H2+Mockito beans), `CoachConversationMigrationIT` (3), `CoachConversationRepositoryTest` (2), `ModelControllerMvcTest` (3), `OpenApiTest` (2) — 20 tests.
- No `@Disabled` tests found in this module (`grep -rn "@Disabled" services/model-manager/src/test` → zero hits) — unlike the repo-wide M2 finding, this module's suite is not artificially green via skipped tests.

**Duplicate test class**: `chat/ChatControllerIT.java` (189 lines, Testcontainers+WireMock) and `integration/ChatControllerIT.java` (83 lines, H2+`@MockitoBean`) are two unrelated implementations sharing a bare class name in different packages — confusing for anyone maintaining the suite post-merge; recommend consolidating or renaming one during collapse.

**Test that asserts a bug as correct**: `insight/WeeklyInsightSchedulerTest.java:30-32` —
```java
verify(insightGenerationService).generateInsightForUser(userId1, "CA");
verify(insightGenerationService).generateInsightForUser(userId2, "CA");
```
This locks in the hardcoded `"CA"` jurisdiction (§7, defect D5) as expected behavior; the test will need rewriting, not just relocating, once the currency-per-user requirement is implemented.

No test in the suite constructs the real `SecurityFilterChain` bean and exercises it unauthenticated against a protected route — that is precisely the gap that would have caught M1.

---

## 10. Verified defects

| id | summary | file:line | verified how |
|---|---|---|---|
| M1 | No authentication mechanism at all — `SecurityConfig` requires `authenticated()` on every non-`/internal`,`/actuator` route but never registers `JwtAuthFilter` (or any filter/provider that authenticates a request); the only other Spring Boot service with zero `JwtAuthFilter` references. | `config/SecurityConfig.java:53-64` (chain); confirmed absent via `grep -rn "JwtAuthFilter" services/model-manager/src/main` (0 hits) vs. api-gateway 11, tax-engine 3, categorisation 2, report 2, budget/ingestion/notification/receipt/subscription 1 each | Read `SecurityConfig.java` fully; grepped for `JwtAuthFilter`/`Jwt`/`X-User-Id` across `src/main` (zero); cross-checked against `libs/common/JwtAuthFilter.java` which every other service imports; per-service grep counts reproduced above. Also present as M1 in `docs/recovery/01-FINDINGS.md:345-352` — independently re-verified here against current source, not taken on faith. |
| D1 | `CoachContextBuilder` sends `X-Trove-User-Id` to all four peer calls; `ingestion-service` expects `X-User-Id` (silently zeroes the dashboard section), and `budget-service`/`subscription-detector` don't accept any user-id header at all — they require a forwarded JWT, which is never sent — so those three calls 401 unconditionally. The AI coach's "personalised context" is therefore always empty/zeroed for every user, independent of M1. | `chat/CoachContextBuilder.java:117,153,185,211` (header sites); `services/ingestion-service/src/main/java/finance/trove/ingestion/dashboard/DashboardController.java:93` (`X-User-Id`, required=false); `services/budget-service/src/main/java/finance/trove/budget/budget/BudgetController.java:51` and `.../networth/NetWorthController.java` (`@AuthenticationPrincipal`, no header path); `services/subscription-detector/src/main/java/finance/trove/subscription/subscription/SubscriptionController.java:56` (same) | Read `CoachContextBuilder.java` in full; grepped `X-User-Id`/`X-Trove-User-Id`/`RequestHeader`/`AuthenticationPrincipal` across the three peer controllers; confirmed no `Authorization` header is ever set by `CoachContextBuilder`; confirmed `CoachContextBuilderTest.java` never asserts the outbound header name (only URL path matchers), so the bug is untested. |
| D2 | `ModelHealthScheduler` autowires `ModelAdapter ollamaAdapter/liteLlmAdapter/paddleOcrAdapter` as `@Autowired(required=false)` fields, but **no class in the entire repo implements `finance.trove.model.adapter.ModelAdapter`** — all three are always `null` in production, so every active model registration is unconditionally marked `is_healthy=false` on every 60s tick, forever. `/v1/models/{alias}/health` therefore always reports unhealthy. | `model/ModelHealthScheduler.java:247-254` (fields), `:286-289` (null→unhealthy branch) | `grep -rln "implements ModelAdapter" services/ libs/` → zero results (only the interface declaration itself and DTO classes in `libs/model-adapter-api` matched a bare-substring grep, none `implements` it). Confirmed `ModelHealthSchedulerTest.java` only reaches the adapters via package-private test setters (`scheduler.setOllamaAdapter(mock)`), never via real Spring autowiring — masks the gap. |
| D3 | `trove.litellm.master-key` is never populated by any deployment config: docker-compose sets env var `LITELLM_MASTER_KEY`, but the property key needs `TROVE_LITELLM_MASTER_KEY` for Spring relaxed-binding to reach `@Value("${trove.litellm.master-key:}")`; `application.yml` has no `trove.litellm` section at all. Every outbound LiteLLM call (used only by the weekly insight job) sends `Authorization: Bearer ` (empty), which the real LiteLLM gateway will reject once `LITELLM_MASTER_KEY` is actually enforced server-side. | `chat/LiteLlmChatGateway.java:31,58` (default `""`, header construction); absence confirmed in `services/model-manager/src/main/resources/application.yml` (no `litellm` string present) | `grep -n "litellm" services/model-manager/src/main/resources/application.yml` → zero hits; confirmed only one `application.yml` exists (no profile overlay defines it either); `LiteLlmChatGatewayTest.java` constructs the gateway directly with a valid literal key, bypassing the real `@Value` resolution, so this is invisible to the unit test. |
| D4 | `AuditController`'s `POST /internal/audit` accepts a full client-supplied `AiAuditLog` JSON body (including `id`, `createdAt`) because the entity is `@JsonAutoDetect(fieldVisibility=ANY)` with no request-DTO boundary — any peer service (or anything that reaches the internal network with the shared token) can forge historical audit rows with an arbitrary `createdAt`/`id`, undermining the "immutable compliance record" guarantee documented on the class. | `audit/AiAuditLog.java:36` (`@JsonAutoDetect`), `audit/AuditController.java:106-113` (`@RequestBody AiAuditLog log` bound straight to `repository.save`) | Read both files fully; confirmed no separate request DTO exists in the `audit` package and `receiveAuditLog` never strips/reassigns `id`/`createdAt` before `save`. |
| D5 | `WeeklyInsightScheduler` hardcodes tax jurisdiction `"CA"` for every user regardless of the user's actual setting, and the corresponding test (`WeeklyInsightSchedulerTest.java:30-32`) asserts this hardcoded value as the expected call — the bug is encoded as a passing test, not merely uncovered. Directly conflicts with the owner's per-user CAD/USD decision. | `insight/WeeklyInsightScheduler.java:26` | Read scheduler and its test in full; confirmed the `verify(...).generateInsightForUser(userId, "CA")` assertions are the only behavioral check on jurisdiction handling. |
| D6 | `WeeklyInsightScheduler` enumerates recipients via `CoachConversationRepository.findDistinctUserIds()` — i.e. users who have sent at least one chat message — not all users with financial data. A user who never opens the chat UI never receives a weekly insight, silently. | `insight/WeeklyInsightScheduler.java:20-21`; `chat/CoachConversationRepository.java:82` | Read both files; the only user-enumeration query in the module scoped to "who gets a weekly insight" is this one. |
| D7 | `ModelHealthScheduler.checkHealth()` runs on a raw `@Scheduled(fixedDelay=60_000)` with **no `@SchedulerLock`**, unlike the weekly job, even though ShedLock is wired module-wide via `SchedulingConfig`. At >1 replica every instance runs the (currently no-op-since-D2) health probe concurrently with no coordination. | `model/ModelHealthScheduler.java:280` (no `@SchedulerLock` annotation, contrast with `insight/WeeklyInsightScheduler.java:20` which has one) | Read both scheduled methods side by side; confirmed absence of the annotation/import in `ModelHealthScheduler.java`. |
| D8 | Two Jackson major-version packages are mixed in the same module: `chat/ChatController.java` and `chat/LiteLlmChatGateway.java` use `tools.jackson.databind.ObjectMapper` (Jackson 3) while `audit/AiAuditLog.java` uses `com.fasterxml.jackson.annotation.JsonAutoDetect` (Jackson 2). Both compile today only because both artifacts are on the classpath transitively; a merge into a single `trove-core` module risks a classpath/behavior clash if other services standardize on one or the other. | `chat/ChatController.java:1-2`, `chat/LiteLlmChatGateway.java:1-3` vs. `audit/AiAuditLog.java:3` | Grepped import lines in both files directly. |

---

## 11. Collapse recommendation — every `src/main` package

Destination root assumed: `finance.trove.<domain>.<sub>` inside `trove-core`.

| Package | Disposition | Destination | Reason |
|---|---|---|---|
| `finance.trove.modelmanager` (root — `TroveModelManagerApplication`) | DELETE | — | One `@SpringBootApplication` survives the collapse (`trove-core`'s own); this one is redundant. |
| `finance.trove.modelmanager.config` (`SecurityConfig`, `SchedulingConfig`, `YamlPropertySourceFactory`) | **DELETE `SecurityConfig`** (it is non-functional — no `JwtAuthFilter` wired, M1); **KEEP `SchedulingConfig`** if `trove-core` doesn't already have an equivalent ShedLock `LockProvider` bean (else DELETE as duplicate — every service defines its own copy of this class, verify against the service chosen as the collapse's security/scheduling baseline before merging); **KEEP `YamlPropertySourceFactory`** only if no other service already ships an identical helper (it's a generic, content-free utility — check for a duplicate name clash first). | `finance.trove.core.config` | `SecurityConfig` here is the one broken example in the fleet (per owner's "single collapsed monolith" direction, use one real `SecurityConfig`+`JwtAuthFilter` pair from a working service, e.g. `budget-service` or `ingestion-service`, not this one). |
| `finance.trove.modelmanager.chat` (`ChatController`, `ChatService`, `CoachContextBuilder`, `CoachConversation`, `CoachConversationRepository`, `CoachPromptsProperties`, `LiteLlmChatGateway`, `SuggestionService`) | **KEEP, with fixes** — this is the AI-coach chat/settings surface the task explicitly wants preserved. Must fix M1 (wire real auth), D1 (header bug — send `X-User-Id` + forward `Authorization`, or better, call peers in-process once collapsed into one JVM), and decide whether `LiteLlmChatGateway` survives (currently only used by the insight job) or is unified with the direct-Ollama path in `ChatController`. | `finance.trove.core.ai.chat` | Core of the "AI settings/coach module" the task calls out. |
| `finance.trove.modelmanager.insight` (`CoachInsight`, `CoachInsightRepository`, `InsightController`, `InsightGenerationService`, `WeeklyInsightScheduler`) | **KEEP, with fixes** — fix D5 (hardcoded `"CA"` — read the user's actual jurisdiction/currency setting) and D6 (enumerate all users with financial data, not just chat users) before shipping. | `finance.trove.core.ai.insight` | Weekly insight is a real, wanted feature; the bugs are fixable in place. |
| `finance.trove.modelmanager.model` (`ModelController`, `ModelHealthScheduler`, `ModelRegistration`, `ModelRepository`) | **KEEP the entity/repository/controller** (becomes the "AI settings: which endpoint/model to use" admin surface — this is exactly the "user-configured OpenAI-compatible endpoint" the owner wants); **REWRITE `ModelHealthScheduler`** — its `ModelAdapter` abstraction is unimplemented dead code (D2/D7); replace with a direct health-ping against the user-configured endpoint (Ollama `/api/tags` or an OpenAI-compatible `/v1/models` call), and add `@SchedulerLock`. | `finance.trove.core.ai.settings` | Matches "AI settings module" replacement scope named in the task. |
| `finance.trove.modelmanager.routing` (`ModelRoutingRegistry`) | KEEP, likely simplify — decide against `libs/ai-gateway-client`'s `ModelRoutingService` (compile-time alias table) which duplicate concept. Only one privacy-tier-aware model-alias resolver should survive the merge. | `finance.trove.core.ai.settings` (or fold into it) | Two independent implementations of "resolve alias+tier → model" exist (this class and `libs/ai-gateway-client.ModelRoutingService`) — pick one. |
| `finance.trove.modelmanager.embeddings` (`EmbeddingsController`) | DELETE or REWRITE — currently a pure stub (always returns a zero vector); if embeddings aren't on the near-term roadmap, delete; if kept, it needs a real vision/text-embedding call through the user-configured endpoint and, critically, an actual auth annotation (it currently has none at all — not even the broken `@AuthenticationPrincipal` pattern the rest of the module uses). | `finance.trove.core.ai.embeddings` (only if kept) | Currently dead weight; also the one endpoint in the module missing even the (non-functional) auth annotation pattern — a real gap once M1 is fixed elsewhere and this one is forgotten. |
| `finance.trove.modelmanager.audit` (`AiAuditLog`, `AiAuditLogRepository`, `AuditController`) | **REWRITE** — the cross-service "every service POSTs its AI calls here over HTTP with a shared token" design only made sense across 10 separate JVMs. Once collapsed into one process, this becomes an in-process call (a `@Service` method or event) writing directly to the `ai_audit_log` table — delete the HTTP endpoint, the shared-token check, and the `X-Service-Token` config entirely. Fix D4 (don't accept client-supplied `id`/`createdAt`) as part of the rewrite. | `finance.trove.core.ai.audit` | `/internal/audit` and its shared-secret plumbing (`trove.internal.service-token`, `TROVE_SERVICE_TOKEN` used by 9 other services to call it) is exactly the kind of inter-service HTTP call the modular-monolith collapse is meant to eliminate. |

**Classes that duplicate `libs/common` or another service** (name every one, per instructions):
- `config.SecurityConfig` — every one of the other 9 Spring Boot services has its own near-identical `SecurityConfig` class importing `libs/common`'s `JwtAuthFilter`; this module's copy is the outlier that dropped the import. Pick one working copy as the collapse's single `SecurityConfig`.
- `config.SchedulingConfig` — same ShedLock-`LockProvider`-registration boilerplate is very likely duplicated verbatim in every other service that uses `@Scheduled` (not independently re-verified against all 9 other services in this pass — flag for cross-service dedup check during the actual merge).
- `RequestIdMdcFilter` (`libs/common`, only `@Import`ed here, not redefined) — no duplicate in this module; noted only because `SecurityConfig` disables its servlet-container auto-registration via a `FilterRegistrationBean` trick (`config/SecurityConfig.java:71-77`) — verify every other service does the same, or this MDC behavior will be inconsistent post-merge.
- `JwtProperties`, `MinioStorageService`, `GlobalExceptionHandler` — **none of these three exist anywhere in this module** (grepped, zero hits) — nothing to reconcile from model-manager's side for those specific classes.

---

## 12. Merge risks / gotchas

- **Bean-name / config-class clash**: `SecurityConfig`, `SchedulingConfig` class names are near-certainly repeated verbatim (same simple name, different package) in most of the other 9 services — Spring bean names default to the class's uncapitalized simple name, so two `@Configuration class SecurityConfig` in different packages collapsing into one module is fine only as long as only one survives per §11; do not merge them both into the same package.
- **Table-name clash**: none observed against Postgres reserved/likely-shared names — `model_registration`, `ai_audit_log`, `coach_conversation`, `coach_insight`, `shedlock` are all distinctively prefixed except `shedlock`, which **every other service that uses ShedLock also creates via its own `V*__create_shedlock.sql`** — collapsing 10 services into 1 schema means 10 near-identical `CREATE TABLE shedlock` migrations will collide; keep exactly one.
- **Property-key clash**: `trove.jwt.secret` is declared here and (per M1) unused — every other service's identical key must be the one that survives; make sure the merged `application.yml` doesn't end up with model-manager's copy silently shadowing a real one via file ordering. `trove.coach.max-history-messages` (`application.yml`) vs. `trove.coach.max_history_messages` (`coach-prompts.yml`) are two independently-loaded property sources binding the **same** `CoachPromptsProperties.maxHistoryMessages` field — confirm which one wins under Spring's property-source precedence (`@PropertySource` beans are typically lower priority than the main `Environment`, so `application.yml`'s value likely wins today) before deleting either file in the merge; don't just delete `application.yml`'s copy without checking `coach-prompts.yml`'s import order survives.
- **Hardcoded ports/hostnames**: `trove.coach.ingestion-url`/`budget-url`/`subscription-url` default to `http://ingestion-svc:8081` / `http://budget-svc:8084` / `http://subscription-det:8086` (`application.yml:26-28`, duplicated again as `@Value` defaults in `CoachContextBuilder.java:47-50`) — these Docker-Compose-DNS-name defaults become meaningless once collapsed into one JVM; the entire `CoachContextBuilder` outbound-HTTP pattern should become in-process method calls, which also fixes D1 for free.
- **Hardcoded currency/jurisdiction**: `WeeklyInsightScheduler.java:26` hardcodes `"CA"` (D5); `CoachContextBuilder.unavailableDash()` hardcodes `"currency": "CAD"` as its fallback value (`chat/CoachContextBuilder.java:141`) — both need to read the user's actual per-user currency setting once that exists, per the owner's CAD/USD decision. Grep the rest of the module for other `"CAD"`/`"CA"` literals before considering this closed — these were the two found in this pass.
- **Static state**: none found — no `static` mutable fields outside the two WireMock test-fixture statics (`static WireMockServer wm`, test-only) and one `ForkJoinPool.commonPool()` implicit use in `CoachContextBuilder` (shared JVM-wide, becomes a bigger contention surface once this module's async fan-out shares a JVM with 9 other services' workloads — worth moving to a dedicated bounded executor during the merge).
- **Reads `X-User-Id`**: **no controller in this module reads `X-User-Id`** — it is the *outbound* header `CoachContextBuilder` gets *wrong* (sends `X-Trove-User-Id` instead), not an inbound trust boundary here. Every inbound identity path in this module is (nominally, per broken M1) `@AuthenticationPrincipal TroveUserDetails`, not a header. When merging with services that *do* trust inbound `X-User-Id` (`ingestion-service`, confirmed in §6), make sure the collapsed module picks one identity-propagation convention consistently.
- **Two `AiAuditLog` classes with the same simple name**: `finance.trove.modelmanager.audit.AiAuditLog` (JPA entity, this module) and `finance.trove.ai.gateway.AiAuditLog` (`libs/ai-gateway-client/src/main/java/finance/trove/ai/gateway/AiAuditLog.java` — a plain DTO used internally by `AiGatewayClient` before it POSTs to `/internal/audit`) are two distinct classes with the identical simple name in different packages already; not a compile clash today (fully qualified), but confusing, and directly relevant to §11's audit-package rewrite — the DTO version should likely disappear entirely once `/internal/audit` becomes an in-process call.
