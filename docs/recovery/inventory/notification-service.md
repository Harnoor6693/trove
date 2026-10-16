# notification-service — inventory for trove-core collapse

> **Historical record, 2026-09-03.** Inventory of the pre-rebuild code, since removed. Current state: `docs-site/guide/architecture.md`.

All paths repo-relative to the repository root. Verified against source on
2026-09-03. Cross-checked against `docs/recovery/01-FINDINGS.md` §O (supersedes earlier
sections) — this report agrees with and sharpens D3 and D9, and adds new verified detail
(the actual mechanism of D3, and the full WeeklyDigestScheduler contract-drift chain that
D3's text alludes to but doesn't spell out).

## 1. Purpose and LOC

Multi-channel notification delivery for Trove: in-app notification centre, email/APNs/FCM
dispatch (invoked internally by other services), per-user notification preferences, outbound
webhook subscriptions with signed HMAC delivery + retry, and a weekly digest email cron.
The application class's own javadoc self-reports as **"a stub pending full implementation"**
(`TroveNotificationApplication.java:9`).

- LOC main: **2019** (`find services/notification-service/src/main -name '*.java' | xargs wc -l | tail -1`)
- LOC test: **1553** (`find services/notification-service/src/test -name '*.java' | xargs wc -l | tail -1`)

## 2. Spring wiring

- **Application class**: `finance.trove.notification.TroveNotificationApplication` — plain
  `@SpringBootApplication`, no `@ComponentScan` override, no extra `@Import`
  (`TroveNotificationApplication.java:16-21`). Component scan is therefore rooted at
  `finance.trove.notification` only — nothing under `finance.trove.common.*` is picked up
  unless a class in this module explicitly `@Import`s it.
- **`@Import`/`@Configuration` inventory** (all under `src/main/java/finance/trove/notification/config/`):
  - `NotificationSecurityConfig` (`config/NotificationSecurityConfig.java:27-29`) — `@Import({ SecurityConfig.class, JwtAuthFilter.class, JwtProperties.class, GlobalExceptionHandler.class, RequestIdMdcFilter.class })`, all four pulled in from `libs/common`.
  - `HttpClientConfig` (`config/HttpClientConfig.java:17`) — provides the shared `HttpClient` bean and constructs+initialises the `ApnsChannel` bean (`apnsInit()` called eagerly at bean-creation time, i.e. blocking file I/O during context refresh).
  - `SchedulingConfig` (`config/SchedulingConfig.java:13-16`) — `@EnableScheduling` + `@EnableSchedulerLock(defaultLockAtMostFor = "9m")`, plus the ShedLock `JdbcTemplateLockProvider` bean (`usingDbTime()`).
  - `NotificationProperties` (`@ConfigurationProperties("trove.notifications")`, `config/NotificationProperties.java:14-15`).
  - **`JacksonConfig` from `libs/common` (`libs/common/src/main/java/finance/trove/common/config/JacksonConfig.java`) is NOT imported or scanned anywhere in this module** — grepped for `JacksonConfig`/`@ComponentScan`/`@Import` across `src/main/java`, only the one `@Import` line above exists. Consequence: SNAKE_CASE naming and `FAIL_ON_UNKNOWN_PROPERTIES=false` are **not** in effect here; this service serializes/deserializes JSON with plain Jackson defaults (camelCase, unknown-property-fails-by-default) unless another auto-config intervenes. This is a real cross-service casing inconsistency risk on collapse (see §12).
- **Security filter chains** (`config/NotificationSecurityConfig.java`):
  - `internalChain` (`@Order(1)`, `internalChain(...)` line 34-44): matches `/internal/**`; installs `ServiceTokenAuthFilter` (constant-time Bearer-token compare, `config/ServiceTokenAuthFilter.java:34-46`) ahead of `UsernamePasswordAuthenticationFilter`; `anyRequest().permitAll()` behind the token filter; CSRF disabled on this chain.
  - Default chain from imported `SecurityConfig` (`libs/common/src/main/java/finance/trove/common/security/SecurityConfig.java:58-91`, no explicit `@Order` so it runs after `internalChain`): CSRF disabled, HSTS/CSP/frame-deny/referrer-policy/permissions-policy headers set, `SessionCreationPolicy.STATELESS`, `JwtAuthFilter` + `RequestIdMdcFilter` both added before `UsernamePasswordAuthenticationFilter`. `permitAll` paths: `/test/**`, `/actuator/health`, `/actuator/prometheus`, `/api/v1/auth/**`, `/api/v1/health`, `/api/health` (none of these are actually exposed by this service — dead permit rules inherited from the shared config). Everything else requires `.anyRequest().authenticated()`.
  - A `FilterRegistrationBean<JwtAuthFilter>` with `setEnabled(false)` (`NotificationSecurityConfig.java:53-58`) suppresses Boot's auto-registration of `JwtAuthFilter` as a raw servlet filter — required so `shouldNotFilter` (which skips `/internal/**`) only applies inside the security chain, not as a second unconditional copy.
  - **Identity source**: every `/v1/**` controller pulls `@AuthenticationPrincipal TroveUserDetails user` populated by `JwtAuthFilter` from the JWT `sub` claim (`libs/common/.../JwtAuthFilter.java:102,109-113`) — HS256, secret from `trove.jwt.secret`/`TROVE_JWT_SECRET`. `/internal/notifications/send` uses no per-caller identity at all; the target `userId` is a field in the request body (`SendNotificationRequest.userId`) trusted from whatever internal caller presents a valid service Bearer token. This service does **not** read `X-User-Id` or `X-Trove-User-Id` for authorization on any controller (WeeklyDigestScheduler, as an *outbound* caller, does try to send an `X-Trove-User-Id` header to ingestion-service — see §7/§10, defect D-N1).
- **JacksonConfig / naming strategy**: none in effect for this module — see bullet above (default camelCase).
- **`@EnableScheduling`/`@EnableAsync`**: `@EnableScheduling` is present (`SchedulingConfig.java:14`). **No `@EnableAsync` anywhere in the module** — nothing here uses `@Async`.

## 3. HTTP routes

| Method | Path | Controller#method | Identity source | Request DTO | Response DTO | Notes |
|---|---|---|---|---|---|---|
| GET | `/v1/notifications` | `InAppNotificationController#list` (`inapp/InAppNotificationController.java:30-38`) | JWT `sub` (`TroveUserDetails`) | — (query params) | `ApiResponse<List<InAppNotificationResponse>>` | `page` (default 0), `size` (default 20) query params; paginated via `PaginationMeta.of`. Returns only undismissed rows, newest first. |
| GET | `/v1/notifications/unread-count` | `InAppNotificationController#unreadCount` (line 43-45) | JWT `sub` | — | `ApiResponse<Long>` | unread AND undismissed count |
| PATCH | `/v1/notifications/{id}/read` | `InAppNotificationController#markRead` (line 52-57) | JWT `sub` | — | `ApiResponse<InAppNotificationResponse>` | 404 if not owned |
| POST | `/v1/notifications/read-all` | `InAppNotificationController#markAllRead` (line 61-64) | JWT `sub` | — | `ApiResponse<Integer>` | bulk UPDATE via `@Modifying @Query`, returns rows updated |
| DELETE | `/v1/notifications/{id}` | `InAppNotificationController#dismiss` (line 71-77) | JWT `sub` | — | 204 No Content | idempotent; 404 if not owned |
| GET | `/v1/notifications/preferences` | `PreferenceController#get` (`preference/PreferenceController.java:46-49`) | JWT `sub` | — | `ApiResponse<PreferenceResponse>` | auto-creates default row on first access |
| PUT | `/v1/notifications/preferences` | `PreferenceController#update` (line 67-71) | JWT `sub` | `UpdatePreferenceRequest` (`@Valid`) | `ApiResponse<PreferenceResponse>` | partial update, null fields ignored |
| GET | `/v1/webhooks` | `WebhookController#list` (`webhook/WebhookController.java:38-41`) | JWT `sub` | — | `ApiResponse<List<WebhookSubscriptionResponse>>` | only `enabled=true` rows |
| POST | `/v1/webhooks` | `WebhookController#create` (line 50-57) | JWT `sub` | `CreateWebhookRequest` (`@Valid`; `targetUrl` `@SafeWebhookUrl`, `secret` 16-128 chars) | `ApiResponse<WebhookSubscriptionResponse>` | 201 |
| DELETE | `/v1/webhooks/{id}` | `WebhookController#delete` (line 66-72) | JWT `sub` | — | 204 No Content | 404 if not owned |
| POST | `/internal/notifications/send` | `NotificationSendController#send` (`send/NotificationSendController.java:51-54`) | `ServiceTokenAuthFilter` (Bearer service token); target user comes from body | `SendNotificationRequest` (`@Valid`) | `ApiResponse<Void>` | internal-only; not gateway-routed (see §12) |

No pagination on webhook or preference endpoints (small per-user cardinality). `/v1/notifications` is the only paginated route.

Per `01-FINDINGS.md` D9: the api-gateway's `PREFIX_TO_SERVICE` map (`services/api-gateway/.../ProxyController.java:73`) routes `/api/v1/notifications` → `notification` but **has no entry for `/api/v1/webhooks`**, verified in `services/api-gateway/src/main/java/finance/trove/gateway/routing/ProxyController.java:64-76` — the 3 `WebhookController` routes are unreachable through the gateway (direct-to-service calls, e.g. in a monolith, are unaffected).

## 4. Persistence

Postgres database name (from `docker-compose.yml`/`.env.example`, `application.yml` only has `${NOTIFICATION_POSTGRES_URL}`): **`trove_notification`** (`docker/docker-compose.yml:378`, `infra/full-stack/docker-compose.yml:433`, `.env.example:32`).

`spring.jpa.hibernate.ddl-auto: validate` (`application.yml:15`) — schema is Flyway-owned.

| Entity | Table | Key columns / FKs / unique indexes |
|---|---|---|
| `NotificationPreference` (`preference/NotificationPreference.java:41-81`) | `notification_preference` | `id` PK; `user_id UUID NOT NULL UNIQUE` + `idx_notification_pref_user_id` (non-unique index on top of the unique constraint — redundant); `budget_alert_threshold NUMERIC(3,2)`; still has `tax_reminder_enabled` column (tax engine is cut per scope decision — orphaned field, see §11/§12) |
| `NotificationLog` (`send/NotificationLog.java:37-81`) | `notification_log` | `id` PK; `user_id` (no FK, no index other than composite below); `channel` CHECK IN (`EMAIL`,`PUSH_APNS`,`PUSH_FCM`); `type` CHECK IN 7 fixed values including `TAX_REMINDER` (dead value once tax engine is cut); `status` CHECK IN (`PENDING`,`SENT`,`FAILED`) — `PENDING` is declared in the DB constraint but never written by app code (dispatcher always writes `SENT` or `FAILED`, see D3 below); indexes: `idx_notification_log_user_id (user_id, created_at DESC)`, partial `idx_notification_log_pending ON (status) WHERE status='PENDING'` (dead index — no row ever has that status) |
| `InAppNotification` (`inapp/InAppNotification.java:8-47`) | `in_app_notification` | `id` PK; `user_id` (no FK); partial indexes `idx_in_app_user_active (user_id, created_at DESC) WHERE dismissed=false`, `idx_in_app_user_unread_count (user_id) WHERE read=false AND dismissed=false` — well-targeted to the two repository queries actually used |
| `WebhookSubscription` (`webhook/WebhookSubscription.java:11-47`) | `webhook_subscription` | `id` PK; `user_id` (no FK, no unique constraint — a user can register the same `target_url` twice); `secret VARCHAR(128)`; `event_types text[]`; index `idx_webhook_sub_user(user_id)` |
| `WebhookDeliveryLog` (`webhook/WebhookDeliveryLog.java:8-52`) | `webhook_delivery_log` | `id` PK; `subscription_id` FK → `webhook_subscription(id) ON DELETE CASCADE`; `status` CHECK IN (`PENDING`,`DELIVERED`,`FAILED`,`EXHAUSTED`); indexes `idx_webhook_log_pending(status, next_attempt_at) WHERE status IN ('PENDING','FAILED')`, `idx_webhook_log_sub(subscription_id, created_at DESC)` |
| (ShedLock, not a JPA entity) | `shedlock` | `name` PK, `lock_until`, `locked_at`, `locked_by` — created by `V5__create_shedlock.sql` |

Repositories with custom `@Query`:
- `InAppNotificationRepository.markAllReadForUser` — `@Modifying @Query("UPDATE InAppNotification n SET n.read = true, n.readAt = :now WHERE n.userId = :userId AND n.read = false")` (`inapp/InAppNotificationRepository.java:17-20`).
- `WebhookDeliveryLogRepository.findDueForRetry` — `@Query("SELECT l FROM WebhookDeliveryLog l WHERE l.status IN ('PENDING','FAILED') AND l.nextAttemptAt <= :now ORDER BY l.nextAttemptAt ASC")` (`webhook/WebhookDeliveryLogRepository.java:11-13`).

Flyway migrations (`src/main/resources/db/migration/`):
- `V1__notification_preference.sql` — creates `pgcrypto` extension + `notification_preference` table + index.
- `V2__notification_log.sql` — creates `notification_log` table with channel/type/status CHECK constraints + 2 indexes.
- `V3__in_app_notification.sql` — creates `in_app_notification` table + 2 partial indexes.
- `V4__webhook_subscription.sql` — creates `webhook_subscription` and `webhook_delivery_log` (with FK+cascade) + 3 indexes.
- `V5__create_shedlock.sql` — creates the ShedLock table (used by both schedulers in this module).

## 5. Config — every property in `application*.yml`

`src/main/resources/application.yml`:

| Key | Env var | Default | Secret? |
|---|---|---|---|
| `server.port` | — | `8087` | no |
| `spring.application.name` | — | `notification-service` | no |
| `spring.profiles.default` | — | `local` | no |
| `spring.datasource.url` | `NOTIFICATION_POSTGRES_URL` | **none** (required) | no (but embeds host/db name) |
| `spring.datasource.username` | `NOTIFICATION_POSTGRES_USER` | **none** (required) | no |
| `spring.datasource.password` | `NOTIFICATION_POSTGRES_PASSWORD` | **none** (required) | **yes** |
| `spring.jpa.hibernate.ddl-auto` | — | `validate` | no |
| `spring.jpa.open-in-view` | — | `false` | no |
| `spring.flyway.enabled` | — | `true` | no |
| `spring.flyway.locations` | — | `classpath:db/migration` | no |
| `spring.mail.host` | `SMTP_HOST` | `localhost` | no |
| `spring.mail.port` | `SMTP_PORT` | `587` | no |
| `spring.mail.username` | `SMTP_USER` | `""` (empty) | **yes** (placeholder) |
| `spring.mail.password` | `SMTP_PASSWORD` | `""` (empty) | **yes** (placeholder) |
| `spring.mail.properties.mail.smtp.auth` | — | `true` | no |
| `spring.mail.properties.mail.smtp.starttls.enable` | — | `true` | no |
| `spring.mail.properties.mail.smtp.starttls.required` | — | `true` | no |
| `spring.mail.properties.mail.smtp.ssl.checkserveridentity` | — | `true` | no |
| `trove.jwt.secret` | `TROVE_JWT_SECRET` | **none** (required; `JwtProperties.validate()` throws below 32 chars) | **yes** |
| `trove.notifications.from-address` | `NOTIFICATION_FROM_ADDRESS` | `noreply@trove.local` | no |
| `trove.notifications.from-name` | — | `"Trove"` (hardcoded, no env override) | no |
| `trove.notifications.smtp-enabled` | `SMTP_ENABLED` | `false` | no |
| `trove.notifications.service-token` | `TROVE_SERVICE_TOKEN` | **none** (required) | **yes** |
| `trove.apns.key-path` | `APNS_KEY_PATH` | `""` (empty → APNs disabled) | no (path) |
| `trove.apns.key-id` | `APNS_KEY_ID` | `""` | no |
| `trove.apns.team-id` | `APNS_TEAM_ID` | `""` | no |
| `trove.apns.bundle-id` | `APNS_BUNDLE_ID` | `finance.trove.app` | no |
| `trove.apns.production` | `APNS_PRODUCTION` | `false` | no |
| `trove.fcm.service-account-json` | `FCM_SERVICE_ACCOUNT_JSON` | `""` (empty → FCM disabled) | no (path) |
| `trove.fcm.project-id` | `FCM_PROJECT_ID` | `""` | no |
| `trove.ingestion.url` | `INGESTION_URL` | `http://ingestion-svc:8081` | no (hardcoded internal hostname) |
| `management.server.port` | — | `9090` | no |
| `management.endpoints.web.exposure.include` | — | `health,info,prometheus,metrics` | no |

**Default secrets found in deploy config (not in `application.yml` itself, but override the required-with-no-default keys above at deploy time)**:
- `docker/docker-compose.yml:117` and 8 other lines, and `docker/docker-compose.release.yml` (10 lines): `TROVE_JWT_SECRET:${TROVE_JWT_SECRET:-change-me-minimum-32-chars-in-production!!}` — a real, checked-in, 32+-char default secret that satisfies `JwtProperties.validate()` and would sign valid tokens if left unrotated in production.
- `docker/docker-compose.yml:381`, `docker/docker-compose.release.yml:253`: `TROVE_SERVICE_TOKEN:${TROVE_SERVICE_TOKEN:-dev-internal-service-token}` — default service token gating `/internal/**`.
- `docker/docker-compose.yml:379-380`, `docker/docker-compose.release.yml` (equivalent), `infra/full-stack/docker-compose.yml:434-435` (user hardcoded, password via `${POSTGRES_PASSWORD}` — no default there): `NOTIFICATION_POSTGRES_USER: trove` / `NOTIFICATION_POSTGRES_PASSWORD: trove` hardcoded (not even a `:-default`, literal value) in `docker/docker-compose.yml` and `docker/docker-compose.release.yml`.
- `services/notification-service/.env.example:19,22-23` mirrors all three, flagged inline `# CHANGE IN PRODUCTION`.
- `infra/full-stack/docker-compose.yml` (the more production-shaped compose file) does **not** default `TROVE_JWT_SECRET`/`TROVE_SERVICE_TOKEN`/postgres password — those are required env vars with no fallback there, which is the correct posture; the weak defaults live only in `docker/docker-compose.yml` and `docker/docker-compose.release.yml`.

## 6. Outbound HTTP

| Target | Path | Headers set | Timeouts? | file:line |
|---|---|---|---|---|
| ingestion-service (`http://ingestion-svc:8081` default, `trove.ingestion.url`/`INGESTION_URL`) | `GET /v1/transactions?user_id={uid}&date_from={from}&date_to={to}&limit=200` | `X-Trove-User-Id: <uid>` | **No explicit timeout** on this `RestClient` (built from injected `RestClient.Builder` with only `.baseUrl()`, `digest/WeeklyDigestScheduler.java:42`) — relies on whatever default the shared `RestClient.Builder` bean carries (not configured in this module) | `digest/WeeklyDigestScheduler.java:55-60` |
| APNs (`api.push.apple.com` / `api.sandbox.push.apple.com`, chosen by `trove.apns.production`) | `POST /3/device/{token}` | `authorization: bearer <ES256 JWT>`, `apns-topic`, `apns-push-type: alert`, `apns-priority: 10`, `content-type: application/json` | Yes — shared `HttpClient` bean has `connectTimeout(10s)` (`config/HttpClientConfig.java:26-30`); no read/response timeout set | `channel/ApnsChannel.java:130-140` |
| FCM (`https://fcm.googleapis.com/v1/projects/{projectId}/messages:send`) | `POST` | `Authorization: Bearer <OAuth2 token from ServiceAccountCredentials>` | **No timeout configured** — `RestClient.Builder` injected with no `.requestFactory()`/timeout customization (`channel/FcmChannel.java:52-61`) | `channel/FcmChannel.java:108-122` |
| User-registered webhook target (`sub.getTargetUrl()`, validated `https` + non-private-IP at creation time only) | `POST <targetUrl>` | `X-Trove-Signature: sha256=<HMAC-SHA256 of payload with sub.secret>`, `X-Trove-Event: <eventType>`, `Content-Type: application/json` | Yes — `SimpleClientHttpRequestFactory` with `connectTimeout(10s)`, `readTimeout(10s)`, redirects explicitly disabled (`webhook/WebhookDeliveryService.java:61-69`) | `webhook/WebhookDeliveryService.java:70-75` |

## 7. Scheduled / async / events

- `WeeklyDigestScheduler.sendWeeklyDigests()` — `@Scheduled(cron = "0 0 8 * * MON")` (every Monday 08:00 UTC), `@SchedulerLock(name="weekly-digest", lockAtMostFor="PT30M")` (`digest/WeeklyDigestScheduler.java:45-46`). Enumerates `PreferenceRepository.findAllByWeeklyDigestEnabledTrueAndEmailEnabledTrueAndEmailAddressIsNotNull()` — i.e. every user in the whole database with digest+email opted in, no per-tenant/batch limit. For each user, calls ingestion-service (see §6/§10 — this call is broken end-to-end) and, if it returns data in the shape the scheduler expects, dispatches a `WEEKLY_DIGEST` templated email via `NotificationDispatcher`.
- `WebhookRetryScheduler.retryDueDeliveries()` — `@Scheduled(fixedDelay = 30_000)` (every 30s), `@SchedulerLock(name="webhook-retry", lockAtMostFor="PT29S", lockAtLeastFor="PT5S")` (`webhook/WebhookRetryScheduler.java:19-20`). Enumerates `WebhookDeliveryLogRepository.findDueForRetry(now)` (status PENDING/FAILED, `next_attempt_at <= now`), attempts `WebhookDeliveryService.deliver(...)` per row with exponential backoff (`2^attempt` seconds, max 5 attempts before `EXHAUSTED`). Per `01-FINDINGS.md` D3: **`WebhookDeliveryService.scheduleDelivery(...)` — the only method that ever inserts a `PENDING` row — is called from nowhere in `src/main` (only from its own test)**, verified by `grep -rn "scheduleDelivery" services/notification-service/src/main` returning zero call sites outside the service class itself. So this scheduler has no producer feeding it; the retry loop runs forever over an empty/dead table.
- No `@Async` anywhere in the module (confirmed by grep across `src/main`).
- No Spring application events (`ApplicationEventPublisher`/`@EventListener`) used anywhere in this module.

## 8. External dependencies

- **Postgres** — `trove_notification` database (required, no MinIO/Redis usage in this module).
- **SMTP** — via Spring Mail (`JavaMailSender`), gated by `trove.notifications.smtp-enabled`/`SMTP_ENABLED` (default `false`, i.e. off unless explicitly turned on).
- **APNs** — Apple push, optional (silently disabled if `APNS_KEY_PATH` unset).
- **FCM** — Firebase push via Google service-account OAuth2, optional (silently disabled if `FCM_SERVICE_ACCOUNT_JSON` unset).
- **ingestion-service** — internal HTTP dependency for the weekly digest (see §6, broken).
- **ShedLock over the same Postgres DB** — distributed lock for both schedulers (`shedlock` table).
- No MinIO, no Redis, no Ollama, no PaddleOCR, no Authentik, no LiteLLM, no SimpleFIN dependency in this module (all out of scope for notification-service).

## 9. Tests

81 `@Test` methods total across 19 files:
- `MigrationIT.java` (4) — `@SpringBootTest` + Testcontainers Postgres, validates Flyway migrations apply cleanly against real Postgres.
- `OpenApiTest.java` (1) — presumably asserts the springdoc OpenAPI endpoint is reachable/valid.
- `webhook/`: `SafeWebhookUrlValidatorTest` (9, unit, exercises SSRF-guard logic directly), `WebhookControllerTest` (5, MockMvc + mocked repository), `WebhookDeliveryServiceTest` (7, mocks repos + RestClient), `WebhookRetrySchedulerTest` (3, mocks repo+service).
- `inapp/`: `InAppNotificationControllerTest` (5, MockMvc), `InAppNotificationServiceTest` (6, mocked repository).
- `config/ServiceTokenAuthFilterTest.java` (3) — direct filter unit tests with `MockHttpServletRequest`/`MockHttpServletResponse`, no Spring context.
- `digest/WeeklyDigestSchedulerTest.java` (4) — WireMock-stubbed ingestion-service + mocked `PreferenceRepository`/`NotificationDispatcher`. **See defect below — this suite is the one that hides D-N1.**
- `preference/`: `NotificationPreferenceControllerIT` (5, likely `@SpringBootTest`/MockMvc + H2), `NotificationPreferenceServiceTest` (3, mocked repo), `NotificationPreferenceEntityTest` (1).
- `channel/`: `ApnsChannelJwtCacheTest` (2), `ApnsChannelTest` (3), `EmailChannelTest` (5), `FcmChannelTest` (4) — all channel-level unit tests, mocking the transport (JavaMailSender / HttpClient / RestClient) rather than hitting real APNs/FCM.
- `send/`: `TemplateRenderTest` (2, Thymeleaf rendering only), `NotificationSendControllerIT` (5, MockMvc against `/internal/notifications/send`), `NotificationDispatcherTest` (4, all channels mocked).

**Test that asserts a bug as correct**: `digest/WeeklyDigestSchedulerTest.java`. Its WireMock stubs (`stubTransactions`, lines 88-98, and the inline stub in `topCategoryIdentifiedCorrectly`, lines 71-79) respond to `GET /v1/transactions.*` with a body shaped `{"transactions":[...]}`, matching the scheduler's own `TransactionSummaryResponse` record (`digest/WeeklyDigestScheduler.java:116-117`) — but the real `TransactionController.getTransactions` in ingestion-service returns a bare JSON array (`List<Transaction>`, `services/ingestion-service/.../TransactionController.java:71-80`), not an object with a `transactions` key. None of the WireMock stubs assert on request headers or query-parameter names either (`urlPathMatching("/v1/transactions.*")` ignores query string content entirely), so the suite never notices that the scheduler sends `X-Trove-User-Id` (`WeeklyDigestScheduler.java:58`) where ingestion-service requires `X-User-Id` (`required = true`, `TransactionController.java:73`), nor that it sends `user_id`/`date_from`/`date_to` query params where the real endpoint reads `from`/`to` with no `user_id` param at all (`TransactionController.java:76-80`, filtering is by the header, not a query param). All four tests in this file pass while the feature is 100% non-functional against the real service — see D-N1 below.

## 10. Verified defects

| id | summary | file:line | verified how |
|---|---|---|---|
| D3 (confirms `01-FINDINGS.md` D3) | `NotificationDispatcher.dispatch` initializes `status = "SENT"` before attempting delivery and only downgrades to `"FAILED"` inside a `catch`; none of the three channels (`EmailChannel.send`, `ApnsChannel.send`, `FcmChannel.send`) ever throws on failure — SMTP/MailException, non-2xx APNs/FCM HTTP responses, and even the `default -> log.warn("Unknown channel")` case in the dispatcher's own switch all log-and-return instead of throwing. `status` is therefore **permanently `"SENT"`** and `error_message` permanently `null` regardless of actual delivery outcome; `FAILED` and the DB's own `PENDING` check-constraint value are both dead/unreachable in practice. | `send/NotificationDispatcher.java:87-109`; `channel/EmailChannel.java:60-64`; `channel/ApnsChannel.java:141-147`; `channel/FcmChannel.java:113-125` | Read all four methods; confirmed every catch block in the three channels logs and returns without rethrowing, and the dispatcher's `default` branch for unknown channel names also does not throw, so the `try` block around the `switch` in `dispatch()` never sees an exception on any of these paths. |
| D-N1 (new — the mechanism behind `01-FINDINGS.md` D3's implicit "webhook/digest producers are broken", made explicit here for the digest path) | `WeeklyDigestScheduler` calls ingestion-service with a header (`X-Trove-User-Id`) and query params (`user_id`, `date_from`, `date_to`) that don't match the real `TransactionController` contract (`X-User-Id` required header; `from`/`to` params; no `user_id` param), **and** expects a JSON object `{"transactions":[...]}` where the real endpoint returns a bare array. Any one of these three mismatches alone would break the call; all three are present simultaneously. Because the whole loop body is wrapped in a per-user `try/catch` that logs and `skipped++`, the weekly digest scheduler runs every Monday, silently fails for every single eligible user, every week, and reports success (`log.info("Weekly digest complete: {} sent, {} skipped", ...)` with `sent=0`). No exception surfaces past the loop; the cron "completes" cleanly every time. | `digest/WeeklyDigestScheduler.java:55-60` (call), `108-111` (swallow); `services/ingestion-service/src/main/java/finance/trove/ingestion/transaction/TransactionController.java:71-80` (real contract) | Read both sides of the call directly: header name, query param names, and response shape all diverge; confirmed the ingestion-service header is `required = true` with no fallback, so a missing/wrong header alone means every call 400s before params or body shape even matter. |
| D-N2 (new) | `WebhookDeliveryService.deliver(...)` re-uses `sub.getTargetUrl()` (whatever was stored at subscription-creation time) with no re-validation. `SafeWebhookUrlValidator` (kept — good SSRF control) only runs on the `@Valid CreateWebhookRequest` at `POST /v1/webhooks` time. A DNS name that resolved to a public IP at creation time and is later repointed to a private/loopback address (DNS rebinding) will be delivered to at retry/delivery time with no re-check — `SimpleClientHttpRequestFactory` performs its own DNS resolution independently of the validator. | `webhook/WebhookDeliveryService.java:49-75` (no call to the validator or any IP check); `webhook/SafeWebhookUrlValidator.java:34-43` (resolution only happens inside the `@Constraint` validator, invoked only via `@Valid` on the controller DTO) | Read `WebhookDeliveryService.deliver` end to end — no re-validation call present; confirmed `SafeWebhookUrlValidator` is wired only via the `@SafeWebhookUrl` annotation on `WebhookController.CreateWebhookRequest.targetUrl` (`webhook/WebhookController.java:31`), which only runs at creation. |
| D-N3 (new) | `NotificationPreference` retains `taxReminderEnabled` (entity field, DB column, `UpdatePreferenceRequest` field, `PreferenceResponse` field, and a `notification_log.type` CHECK-constraint value `TAX_REMINDER`) even though the tax engine is a fixed-scope cut (owner decision: replaced by CSV/PDF export, no computed tax numbers, no reminders implied). Dead surface area to remove on collapse, not a runtime bug. | `preference/NotificationPreference.java:76-77`; `preference/UpdatePreferenceRequest.java:46`; `preference/PreferenceResponse.java:21`; `db/migration/V1__notification_preference.sql:14`; `db/migration/V2__notification_log.sql:5-8` (`'TAX_REMINDER'` in the CHECK list); `send/NotificationDispatcher.java:33,58` (`tax-reminder` in the Thymeleaf allowlist); `src/main/resources/templates/email/tax-reminder.html` (template still present) | Grepped `tax` (case-insensitive) across the module's entity/DTO/migration/template files; all six sites confirmed present in source. |
| D-N4 (new, cross-cutting note — gateway-side, not this module's bug, but affects whether this module's public routes are reachable in the deployed topology) | `api-gateway`'s `ProxyController.proxy(...)` builds a **fresh** header set for every proxied call — only `X-Trove-User-Id`, `X-Trove-User-Email`, `X-Trove-Roles`, `X-Request-Id`, `X-Forwarded-For` are set; the original client `Authorization` header is never copied onto the downstream request. `notification-service`'s own `JwtAuthFilter` (inherited from `libs/common`, used for every `/v1/**` route in this module) requires exactly that `Authorization: Bearer <JWT>` header and ignores `X-Trove-User-Id`/`X-User-Id` entirely. As deployed today, every public route in §3 (`/v1/notifications*`, `/v1/webhooks*`) would 401 when reached through the gateway, because the gateway strips the one header the service actually checks. | `services/api-gateway/src/main/java/finance/trove/gateway/routing/ProxyController.java:170-176` (headers built); `libs/common/src/main/java/finance/trove/common/security/JwtAuthFilter.java:89-93` (only reads `Authorization`) | Read `ProxyController.proxy` in full — confirmed the `HttpHeaders headers` object is constructed from scratch with no copy of `request.getHeader("Authorization")`; grepped for `Authorization` in `ProxyController.java` — zero hits. |

## 11. Collapse recommendation — every package in `src/main`

Target base package on collapse: `finance.trove.notification.*` (unchanged — no name collision with any other service's top package as long as `finance.trove.<service>` prefixes are preserved).

| Package | Action | Destination | Reason |
|---|---|---|---|
| `finance.trove.notification` (root — `TroveNotificationApplication`) | **DELETE** | — | `@SpringBootApplication` bootstrap class is dropped entirely; trove-core has one application class. Its own javadoc ("stub pending full implementation") is accurate and should not survive as a comment anywhere near production code. |
| `finance.trove.notification.channel` (`ApnsChannel`, `EmailChannel`, `FcmChannel`) | **KEEP, fix first** | `finance.trove.notification.channel` | Real, mostly well-built delivery logic (APNs JWT caching, FCM OAuth2, SMTP via Thymeleaf). Must fix D3 (make failures actually propagate/record as FAILED) before trusting `notification_log.status` for anything. |
| `finance.trove.notification.config` (`HttpClientConfig`, `NotificationProperties`, `NotificationSecurityConfig`, `SchedulingConfig`, `ServiceTokenAuthFilter`) | **REWRITE** (mostly delete, fold survivors) | `finance.trove.notification.config` / shared `finance.trove.core.security` | `NotificationSecurityConfig`'s two-chain pattern (`internalChain` for `/internal/**` + inherited default chain) is duplicated near-identically across services (see `ServiceTokenAuthFilter`, `JwtAuthFilter`, `SecurityConfig` below) — collapse to ONE security config for the whole monolith, one `ServiceTokenAuthFilter`-equivalent gate (or drop `/internal/**` entirely once dispatch becomes an in-process method call instead of HTTP). `SchedulingConfig`'s `@EnableScheduling`+ShedLock pattern is also duplicated per-service (per D8 in `01-FINDINGS.md`) — collapse to one. `HttpClientConfig`/`NotificationProperties` survive as-is (module-scoped, no clash). |
| `finance.trove.notification.digest` (`WeeklyDigestScheduler`) | **REWRITE** | `finance.trove.notification.digest` | Fix D-N1 (header name, query params, response shape) against the *real* ingestion transaction-listing contract; consider making this an in-process call to the ingestion package's service class instead of HTTP once both live in the same JVM — removes the whole contract-drift class of bug. Also drop the hardcoded `"currency": "CAD"` template variable (`digest/WeeklyDigestScheduler.java:101`, see §12) now that currency is a per-user setting. |
| `finance.trove.notification.inapp` (`InAppNotification`, controller/service/repo/DTOs) | **KEEP** | `finance.trove.notification.inapp` | Complete, correctly scoped (ownership-checked, paginated, indexed). Per `01-FINDINGS.md` D3, this table currently has no writer anywhere in the codebase — on collapse, wire the other domains (budget alerts, sync status, etc.) to actually call `InAppNotificationService` / persist rows, or the centre stays permanently empty. |
| `finance.trove.notification.preference` (`NotificationPreference`, controller/service/repo/DTOs) | **KEEP, trim** | `finance.trove.notification.preference` | Solid partial-update pattern. Drop `taxReminderEnabled` end-to-end (D-N3) as part of the tax-engine cut. |
| `finance.trove.notification.send` (`NotificationDispatcher`, `NotificationLog`, controller, DTOs) | **REWRITE** | `finance.trove.notification.send` | Core orchestration logic is sound in shape (template allowlist, multi-channel fan-out, audit log) but must fix D3 before the audit log means anything. Once collapsed, `/internal/notifications/send` becomes an ordinary in-process method call from other domains (budget, sync, subscription) — the whole `ServiceTokenAuthFilter`/`/internal/**` HTTP hop for this one call is scaffolding for a service boundary that no longer exists; convert to a plain `@Service` bean call and delete the controller + internal security chain. |
| `finance.trove.notification.webhook` (`WebhookSubscription`, `WebhookDeliveryLog`, controller, `WebhookDeliveryService`, `WebhookRetryScheduler`, `SafeWebhookUrl(Validator)`) | **KEEP `SafeWebhookUrl`/`SafeWebhookUrlValidator` as-is; REWRITE the rest** | `finance.trove.notification.webhook` | `SafeWebhookUrlValidator` is a genuinely good SSRF guard — keep verbatim, and additionally call it (or an equivalent live IP check) at delivery time too (D-N2), not just at subscription-creation time. The entity/repo/delivery/retry machinery is fine but currently has **zero producers** (`scheduleDelivery` is called from nowhere but its own test) — wire real event sources (budget alert fired, sync completed, etc.) to call it once those domains are collapsed in, or the whole webhook subsystem stays permanently dormant exactly as it is today. |

**Duplicated infra classes named in the task** (present in this module, near-identical copies live in most/all of the other 9 services — collapse to one instance each in `trove-core`):
- `ServiceTokenAuthFilter` (`config/ServiceTokenAuthFilter.java`) — service-local copy of the same Bearer-token-compare pattern; check other services for byte-for-byte duplicates before assuming this exact one wins.
- `JwtAuthFilter`, `SecurityConfig`, `JwtProperties`, `GlobalExceptionHandler`, `RequestIdMdcFilter` — all imported from `libs/common` (not duplicated *within* this module), but every one of the 10 services does its own `@Import` of the same five classes into its own per-service `@Configuration` (as `NotificationSecurityConfig` does here) — on collapse this becomes exactly one `@Import`/`@Configuration` for the whole monolith; the ten near-identical `XxxSecurityConfig` wrapper classes (this module's `NotificationSecurityConfig` among them) all get deleted down to one.
- No `MinioStorageService` in this module (correctly absent — nothing here touches MinIO).
- `JacksonConfig` — **not imported here at all** (see §2); check whether the version that other services *do* import (`libs/common`'s, with SNAKE_CASE) should become the monolith's single Jackson config, since this module currently diverges from it silently.

## 12. Merge risks / gotchas

- **Bean-name clash risk**: `HttpClientConfig` exposes an unqualified `HttpClient httpClient()` bean (`config/HttpClientConfig.java:26`) and an unqualified `ApnsChannel apnsChannel(...)` bean (line 45) — if any other service being merged also defines an `HttpClient` bean with the default name `httpClient`, Spring will collide on collapse into one `ApplicationContext`. Same risk for `SchedulingConfig.lockProvider(DataSource)` (`config/SchedulingConfig.java:19`) — if other services define their own `LockProvider` bean (they likely do, per D8's note that 4 services have their own `SchedulingConfig`), only one `LockProvider` bean can survive in one context; the collapse must consolidate to a single ShedLock provider over one shared table (this module already creates its own `shedlock` table via `V5__create_shedlock.sql` — every other service with a `SchedulingConfig` likely has its own **duplicate** `shedlock` table via its own Flyway history; these must be merged into exactly one).
- **Table-name clash risk**: none observed *within* this module against other domains inspected so far, but `shedlock` (created here in `V5__create_shedlock.sql`) is a strong candidate for a cross-service collision — every one of the ≥4 services with `@EnableSchedulerLock` (D8) plausibly ships its own `V*__create_shedlock.sql`; Flyway migration merge must de-duplicate this to one creation.
- **Property-key clashes**: `trove.jwt.secret`, `trove.notifications.*`, `trove.apns.*`, `trove.fcm.*`, `trove.ingestion.url` are namespaced enough to be low-risk, but `server.port: 8087` and `management.server.port: 9090` are meaningless in a single monolith JVM (only one of the ten `server.port` values survives) — must be reconciled/deleted along with the other 9 services' equivalents.
- **Static/eager state**: `HttpClientConfig.apnsChannel(...)` calls `channel.apnsInit()` synchronously during `@Bean` construction (blocking file read + EC key parse at context-refresh time, `config/HttpClientConfig.java:52-54`) — harmless alone, but stacking ten services' worth of eager `@PostConstruct`/bean-init I/O into one context-refresh will lengthen monolith startup; worth converting to lazy init.
- **Hardcoded hostnames/ports**: `trove.ingestion.url` default `http://ingestion-svc:8081` (`application.yml:49`, and the WeeklyDigestScheduler `@Value` default at `digest/WeeklyDigestScheduler.java:39`) — a Docker-network hostname that means nothing once ingestion's code lives in the same JVM; must become a direct method call (see §11) rather than a config value to delete.
- **Hardcoded currency (CAD) site**: `WeeklyDigestScheduler.java:101` — `"currency", "CAD"` is hardcoded into the weekly-digest email template variables regardless of the recipient's actual per-user currency setting (owner decision: CAD/USD are both first-class, chosen at onboarding). Every US user's weekly digest would show dollar amounts labeled CAD. Must be fixed to read the user's actual currency setting when this scheduler is rewritten (D-N1 rewrite is a natural place to also fix this).
- **Anything that reads `X-User-Id` / `X-Trove-User-Id`**: this module's own controllers never read either header (identity is JWT-derived, see §2) — but `WeeklyDigestScheduler` *sends* `X-Trove-User-Id` outbound to ingestion-service (§6, §10 D-N1), which is the wrong header name for that target's real contract. On collapse this whole outbound call should disappear in favor of an in-process call, which sidesteps the header question entirely — but flag it so nobody "fixes" the header name instead of removing the HTTP hop.
- **Jackson naming inconsistency** (§2): this module does not apply `libs/common`'s SNAKE_CASE `JacksonConfig`; verify whether the services being merged that *do* apply it (if any — `ingestion-service`'s own `JacksonConfig` is an empty no-op stub, so check each service individually) would change this module's `/v1/notifications*` and `/v1/webhooks*` JSON field casing on collapse if a single shared Jackson config is adopted for the whole monolith — this is a wire-format-breaking change for any client that has JSON-field expectations baked in (web app, mobile app).
- **Gateway `Authorization` header not forwarded** (D-N4, §10): not this module's bug, but blocks every one of this module's public routes from working through the gateway as currently deployed. Irrelevant to trove-core itself (no gateway hop once collapsed) but relevant context for anyone tempted to "test the current system end-to-end before collapsing" as a baseline — that baseline is currently non-functional for reasons unrelated to notification-service's own code.
