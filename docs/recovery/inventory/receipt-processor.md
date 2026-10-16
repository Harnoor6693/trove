# Inventory: services/receipt-processor

> **Historical record, 2026-09-03.** Inventory of the pre-rebuild code, since removed. Current state: `docs-site/guide/architecture.md`.

Audited against working tree at commit `9ad1b5f` (main), 2026-09-03. Every claim below was
verified by reading the cited file; where a claim from `docs/recovery/01-FINDINGS.md`
did not reproduce against current source, that is called out explicitly (see §10).

## 1. Purpose and LOC

Accepts multipart receipt image/PDF uploads, stores the original in MinIO, and runs a
seven-stage async pipeline (validate → OCR → heuristic parse → AI extraction fallback →
transaction matching → Canadian tax-split → confidence scoring) that produces a
`Receipt` record with extracted merchant/amount/date/line-items, optionally links it to
an ingestion-service transaction, and flags low-confidence results for human review.

- Main: **3,379** LOC (`find services/receipt-processor/src/main -name '*.java' | xargs wc -l | tail -1`)
- Test: **2,277** LOC (`find services/receipt-processor/src/test -name '*.java' | xargs wc -l | tail -1`)

## 2. Spring wiring

- **Application class**: `finance.trove.receipt.TroveReceiptProcessorApplication`
  (`src/main/java/finance/trove/receipt/TroveReceiptProcessorApplication.java:36`) —
  `@SpringBootApplication @EnableAsync`. No `@EnableScheduling` anywhere in the module
  (grep confirms zero `@Scheduled` usages — see §7).
- **`@Import`/`@Configuration` inventory**:
  - `ReceiptSecurityConfig` (`config/ReceiptSecurityConfig.java:22`) —
    `@Import({SecurityConfig.class, JwtAuthFilter.class, JwtProperties.class, GlobalExceptionHandler.class, RequestIdMdcFilter.class})`,
    all from `libs/common`. No service-specific security rules.
  - `ReceiptAsyncConfig` (`config/ReceiptAsyncConfig.java:22`) — declares the
    `receiptExecutor` `ThreadPoolTaskExecutor` bean (core 3 / max 6 / queue 100,
    `CallerRunsPolicy`).
  - `ReceiptPipelineConfig` (`config/ReceiptPipelineConfig.java:33`) —
    `@EnableConfigurationProperties(IngestionProperties.class)`; manually `@Bean`-wires
    all 7 pipeline stage beans plus `MultipartFileValidator`, `HeuristicExtractor`,
    `AiExtractor`, `TransactionMatchingService`. Injects `Optional<PaddleOcrAdapter>` so
    the app starts when the adapter auto-config isn't active.
  - `MinioConfig` (`storage/MinioConfig.java:22`) —
    `@EnableConfigurationProperties(MinioProperties.class)`; builds `MinioClient` bean,
    `MinioStorageService` bean, and a `MinioInitializer` inner class whose
    `@PostConstruct` creates the bucket if missing (fails startup with
    `IllegalStateException` if the MinIO call errors).
  - `TaxSplitService` (`taxsplit/TaxSplitService.java:28`) —
    `@Service @EnableConfigurationProperties(ProvinceTaxRates.class)`.
  - `ProvinceTaxRates` (`taxsplit/ProvinceTaxRates.java`) — `@PostConstruct` throws
    `IllegalStateException` at startup if `trove.tax-split.default-rates` is absent.
  - Auto-config actually reaching this module via a real
    `META-INF/spring/…AutoConfiguration.imports` file: only
    `finance.trove.ai.gateway.AiGatewayAutoConfiguration` (from `libs/ai-gateway-client`,
    verified at `libs/ai-gateway-client/src/main/resources/META-INF/spring/…imports`)
    and `finance.trove.model.paddleocr.PaddleOcrAutoConfiguration` (from
    `plugins/models/paddleocr-adapter`, `@ConditionalOnProperty("trove.paddleocr.enabled", havingValue="true")`,
    no `matchIfMissing`). **`libs/common` ships no auto-configuration imports file at
    all** — every bean it contributes here arrives only via the explicit `@Import` list
    in `ReceiptSecurityConfig`. In particular `finance.trove.common.config.JacksonConfig`
    (the shared SNAKE_CASE `PropertyNamingStrategy` customizer) is **not** in that
    `@Import` list and is never imported anywhere in this module — grep for
    `JacksonConfig` under `services/receipt-processor` returns nothing. The default
    Jackson naming strategy therefore applies here, and every DTO field that needs
    snake_case on the wire (`extracted_merchant`, `review_required`, etc.) carries an
    explicit `@com.fasterxml.jackson.annotation.JsonProperty("…")` override
    (`receipt/ReceiptResponse.java`, `receipt/ReceiptSummary.java`, `receipt/StatusResponse.java`,
    `upload/UploadResponse.java`, `receipt/LineItemResponse.java`) — it happens to work
    today only because every field is explicitly annotated.
- **Security filter chain** (shared `libs/common/src/main/java/finance/trove/common/security/SecurityConfig.java:67-90`,
  activated here by the `@Import` above): CSRF disabled, `SessionCreationPolicy.STATELESS`,
  CSP/HSTS/frame-deny/referrer-policy/permissions-policy headers set.
  `permitAll()` paths: `/test/**`, `/actuator/health`, `/actuator/prometheus`,
  `/api/v1/auth/**`, `/api/v1/health`, `/api/health` — receipt-processor exposes none of
  the `/api/v1/auth/**` routes itself, that allowlist is just inherited unused. Everything
  else is `.anyRequest().authenticated()`.
  **Identity source**: `JwtAuthFilter` (`libs/common/.../security/JwtAuthFilter.java`)
  parses the `Authorization: Bearer <jwt>` header, HMAC-SHA256-verifies against
  `trove.jwt.secret`, and populates `SecurityContextHolder` with a `TroveUserDetails`
  built from the JWT **`sub` claim** (parsed as a UUID). Controllers read it via
  `@AuthenticationPrincipal TroveUserDetails user` → `user.getUserId()`
  (`receipt/ReceiptController.java` throughout, `upload/ReceiptUploadController.java:60`).
  **This service never reads an inbound `X-User-Id` header for identity.** (It *sends*
  `X-User-Id` as an outbound header to ingestion-service — see §6.)
- **Jackson**: no local `JacksonConfig`; effectively default Jackson naming (see above),
  DTOs compensate with explicit `@JsonProperty`. `ObjectMapper`/`JsonMapper` type used
  throughout production code is `tools.jackson.databind.*` (Jackson 3.x rebrand);
  `@JsonProperty`/`@JsonIgnoreProperties` annotations stay on
  `com.fasterxml.jackson.annotation.*` (unchanged package in Jackson 3) — consistent, not
  a bug.
- **`@EnableScheduling`**: absent — no `@Scheduled` anywhere in this module.
- **`@EnableAsync`**: present on the application class
  (`TroveReceiptProcessorApplication.java:35`); the only `@Async` method is
  `ReceiptPipelineOrchestrator.processAsync` (`pipeline/ReceiptPipelineOrchestrator.java:86`,
  `@Async("receiptExecutor")`).

## 3. HTTP routes

| METHOD | Path | Controller#method | Identity source | Request DTO | Response DTO | Notes |
|---|---|---|---|---|---|---|
| POST | `/v1/receipts/upload` | `ReceiptUploadController#upload` (`upload/ReceiptUploadController.java:60`) | `@AuthenticationPrincipal TroveUserDetails` (JWT `sub`) | multipart `file` (required), `source` param (default `UPLOAD`), `X-Trove-Province` header (optional) | `ApiResponse<UploadResponse>` | Returns 202. Stores file to MinIO, persists `Receipt(UPLOADED)`, kicks off `processAsync` on `receiptExecutor`. |
| GET | `/v1/receipts` | `ReceiptController#list` (`receipt/ReceiptController.java:60`) | JWT `sub` | query `page` (default 0), `limit` (default 20, clamped server-side to [1,100]) | `ApiResponse<List<ReceiptSummary>>` with `PaginationMeta` | Ordered newest-first (`findByUserIdOrderByCreatedAtDesc`). |
| GET | `/v1/receipts/{id}` | `ReceiptController#get` (`:98`) | JWT `sub` | path `id: UUID` | `ApiResponse<ReceiptResponse>` | 404 (not 403) when receipt belongs to another user — `findByIdAndUserId`. Includes pre-signed thumbnail URL when `thumbnailKey` set. |
| GET | `/v1/receipts/{id}/status` | `ReceiptController#status` (`:152`) | JWT `sub` | path `id` | `ApiResponse<StatusResponse>` | Lightweight poll target for async pipeline progress. |
| DELETE | `/v1/receipts/{id}` | `ReceiptController#delete` (`:190`) | JWT `sub` | path `id` | 204, no body | Deletes DB row + line items in one `@Transactional`; MinIO original/thumbnail deletion is fire-and-forget `afterCommit()`. |
| PATCH | `/v1/receipts/{id}/review` | `ReceiptController#review` (`:238`) | JWT `sub` | path `id`, body `ReviewRequest{accepted, corrections}`, header `X-Trove-Province` (optional) | `ApiResponse<ReceiptSummary>` | Three outcomes: reject→FAILED; accept w/ corrections→re-run pipeline (`status=UPLOADED`, line items deleted, `processAsync` re-invoked); accept w/o corrections→clears `reviewRequired`. |
| GET | `/v1/receipts/{id}/download` | `ReceiptController#download` (`:274`) | JWT `sub` | path `id` | `ApiResponse<String>` | Pre-signed MinIO GET URL for the original file, expiry from `trove.minio.presigned-url-expiry-minutes`. |

No other controllers. Actuator endpoints (`health,info,prometheus,metrics`, exposed per
`application.yml:66-73`) are mounted at `/actuator/*` by Spring Boot itself, not by a
controller in this module; only `/actuator/health` and `/actuator/prometheus` are
`permitAll` (§2) — `/actuator/info` and `/actuator/metrics` require a valid JWT.

## 4. Persistence

**Postgres database name** (`application.yml:12`): `trove_receipt` (from
`jdbc:postgresql://localhost:5432/trove_receipt`, overridable via `RECEIPT_POSTGRES_URL`).

### Entities → tables

- **`Receipt`** (`receipt/Receipt.java:31`) → table `receipt`. Implements
  `Persistable<UUID>` with a manually-managed `isNew` transient flag (service
  pre-assigns the UUID before save so the MinIO key can be built first; `@PostPersist`/`@PostLoad`
  flip `isNew` false). Columns: `id` (PK), `user_id` (FK-less UUID, ownership only),
  `status` (enum string), `image_key`, `thumbnail_key`, `source`, `upload_size_bytes`,
  `ocr_text` (TEXT), `extracted_merchant` (varchar 200), `extracted_amount` (numeric 12,2),
  `extracted_currency` (varchar 3), `extracted_date`, `extracted_line_items` (JSONB),
  `gst_amount`/`pst_amount`/`hst_amount` (numeric 12,2), `confidence` (numeric 3,2),
  `matched_transaction_id`, `review_required`, `error_message`,
  `processing_started_at`/`processing_completed_at`, `created_at`/`updated_at`.
- **`LineItem`** (`receipt/LineItem.java:34`) → table `line_item`. `id` PK
  (`GenerationType.UUID`), `receipt_id` (FK to `receipt.id`, `ON DELETE CASCADE` at the DB
  level — see V2 migration), `description`, `quantity`, `unit_price`, `total_price`,
  `is_taxable`, `created_at`.

### Repositories with custom queries

- `ReceiptRepository` (`receipt/ReceiptRepository.java`): `findByUserIdOrderByCreatedAtDesc(UUID, Pageable)`;
  `findByIdAndUserId(UUID, UUID)` — the ownership gate used everywhere (404-not-403 pattern).
- `LineItemRepository` (`receipt/LineItemRepository.java`): `findByReceiptId(UUID)`;
  `deleteByReceiptId(UUID)`.

No `@Query`-annotated methods in this module — everything is derived-query.

### Flyway migrations (`src/main/resources/db/migration/`)

| File | Summary | Tables/indexes |
|---|---|---|
| `V1__create_receipt_table.sql` | Creates `receipt` with a `status` CHECK constraint (10 values, missing `QUEUED` — fixed in V3) and a `source` CHECK constraint (`UPLOAD`,`SCAN`,`EMAIL`); enables `pgcrypto` for `gen_random_uuid()`. | `receipt`; `idx_receipt_user_id (user_id, created_at DESC)`; partial `idx_receipt_status … WHERE status NOT IN ('COMPLETE','FAILED')`; partial `idx_receipt_user_review (user_id, review_required) WHERE review_required = true`. |
| `V2__create_line_item_table.sql` | Creates `line_item` with `receipt_id REFERENCES receipt(id) ON DELETE CASCADE`. | `line_item`; `idx_line_item_receipt_id (receipt_id)`. |
| `V3__add_queued_status.sql` | Drops and re-adds the `receipt.status` CHECK constraint to add `QUEUED` (the `ReceiptStatus` enum value that exists in Java but was never DB-reachable until this migration — `ReceiptStatus.java` docs it as "reserved for future use", it is never actually set by any code path). | Alters `receipt_status_check`. |

No unique index beyond the PKs; no unique constraint on `(user_id, image_key)` or similar
(duplicate uploads of the same file are not prevented at the DB layer).

## 5. Config — every property in `application.yml`

| Key | Env var | Default | Secret? |
|---|---|---|---|
| `server.port` | — | `8085` | no |
| `spring.application.name` | — | `receipt-processor` | no |
| `spring.profiles.default` | — | `local` | no |
| `spring.config.import` | — | `classpath:prompts.yml` | no |
| `spring.datasource.url` | `RECEIPT_POSTGRES_URL` | `jdbc:postgresql://localhost:5432/trove_receipt` | no |
| `spring.datasource.username` | `RECEIPT_POSTGRES_USER` | `trove` | **yes — default secret** |
| `spring.datasource.password` | `RECEIPT_POSTGRES_PASSWORD` | `trove` | **yes — default secret** |
| `spring.jpa.hibernate.ddl-auto` | — | `validate` | no |
| `spring.jpa.show-sql` | — | `false` | no |
| `spring.jpa.properties.hibernate.dialect` | — | `org.hibernate.dialect.PostgreSQLDialect` | no |
| `spring.flyway.enabled` | — | `true` | no |
| `spring.flyway.locations` | — | `classpath:db/migration` | no |
| `spring.flyway.baseline-on-migrate` | — | `false` | no |
| `spring.servlet.multipart.enabled` | — | `true` | no |
| `spring.servlet.multipart.max-file-size` | — | `20MB` | no |
| `spring.servlet.multipart.max-request-size` | — | `21MB` | no |
| `trove.jwt.secret` | `TROVE_JWT_SECRET` | **none — `${TROVE_JWT_SECRET}` with no fallback, startup fails if unset** | **yes, no default (fail-fast — good)** |
| `trove.minio.endpoint` | `MINIO_ENDPOINT` | `http://minio:9000` | no (hostname; MinIO is being dropped per owner decision) |
| `trove.minio.access-key` | `MINIO_ACCESS_KEY` | none (required, no fallback) | **yes, no default** |
| `trove.minio.secret-key` | `MINIO_SECRET_KEY` | none (required, no fallback) | **yes, no default** |
| `trove.minio.bucket-receipts` | — | `trove-receipts` (hardcoded, not env-overridable) | no |
| `trove.minio.presigned-url-expiry-minutes` | — | `30` | no |
| `trove.ai.model-manager-url` | `MODEL_MANAGER_URL` | `http://model-manager:8089` | no |
| `trove.ai.timeout-seconds` | — | `30` | no |
| `trove.ai.retry-attempts` | — | `2` | no |
| `trove.ai.retry-delay-ms` | — | `500` | no |
| `trove.ai.service-token` | **not declared in this module's `application.yml` at all** | required by `AiGatewayProperties.validate()` (`libs/ai-gateway-client/.../AiGatewayProperties.java:65-69`) — startup throws `IllegalStateException` if blank/missing. Must arrive via relaxed env-var binding (e.g. `TROVE_AI_SERVICE_TOKEN`) since it is absent from YAML. | **yes, undocumented required secret — landmine for anyone reading only this module's application.yml** |
| `trove.paddleocr.base-url` | `PADDLEOCR_URL` | `http://paddleocr:8866` | no (dangling — no paddleocr service exists in any compose file per prior audit M8; being cut per owner decision) |
| `trove.paddleocr.timeout-seconds` | — | `60` | no |
| `trove.paddleocr.enabled` | — | `true` (hardcoded here, overriding the library default of `false`) | no |
| `trove.ingestion.url` | `INGESTION_URL` | `http://ingestion-svc:8081` | no — **this one is correct**, matches the actual container name `ingestion-svc` (unlike report-service/subscription-detector's `ingestion-service` default, flagged as O4 in the prior audit). |
| `trove.tax-split.province-rates.*` | — | 10 hardcoded CA province GST/PST/HST rate tables | no |
| `trove.tax-split.default-rates` | — | `{gst:0.05, pst:0.00, hst:0.00}` | no |
| `management.endpoints.web.exposure.include` | — | `health,info,prometheus,metrics` | no |
| `management.endpoint.health.show-details` | — | `when-authorized` | no |
| `management.metrics.export.prometheus.enabled` | — | `true` | no |

`prompts.yml` (`src/main/resources/prompts.yml`) additionally supplies `receipt.extract`
(the AI extraction prompt template, injected via `@Value("${receipt.extract}")` in
`ReceiptPipelineConfig.java:53`) — not a secret, but a required key with no default; if
`prompts.yml` fails to load via `spring.config.import`, startup fails.

**Default secrets flagged**: `spring.datasource.username`/`password` default to
`trove`/`trove` — fine for local Postgres, dangerous if this default ever reaches a
public-internet deployment unchanged. `trove.jwt.secret`, `trove.minio.access-key`,
`trove.minio.secret-key` have no defaults (fail-closed, good). `trove.ai.service-token`
has no default and isn't even referenced in this module's YAML (fail-closed but silent
until you read the library source).

## 6. Outbound HTTP

| Target | Path | Headers set | Timeouts | file:line |
|---|---|---|---|---|
| ingestion-service (`trove.ingestion.url`, default `http://ingestion-svc:8081`) | `GET /v1/transactions?date_from&date_to&min_amount&max_amount&type=DEBIT` | `X-User-Id: <receipt.userId>` | connect 5s / read 10s default, from `IngestionProperties` (`config/IngestionProperties.java:32-33`), built into `RequestConfig` via Apache HttpClient5 | `matching/TransactionMatchingService.java:75-90` (`findMatches`) |
| ingestion-service | `PATCH /v1/transactions/{transactionId}` | `X-User-Id: <userId>`, `Content-Type: application/json`; body `{receipt_id, notes}` | same client config as above | `matching/TransactionMatchingService.java:119-133` (`patchTransactionWithReceipt`) — failure is caught and logged as a warning, non-fatal |
| PaddleOCR service (`trove.paddleocr.base-url`, default `http://paddleocr:8866`) | OCR multipart POST (in `plugins/models/paddleocr-adapter`, `PaddleOcrClient`, invoked from `pipeline/stages/OcrStage.java:56` via `PaddleOcrAdapter.ocr(bytes, mimeType)`) | adapter-internal, not set by this module directly | `trove.paddleocr.timeout-seconds` (60s), `connect-timeout-seconds` (5s, library default) | `pipeline/stages/OcrStage.java:56` |
| model-manager / AI gateway (`trove.ai.model-manager-url`, default `http://model-manager:8089`) | LiteLLM-routed completion call, invoked via `AiGatewayClient.complete(AiRequest)` from `extraction/AiExtractor.java:62` (`ModelAlias.LOCAL_QUALITY`, `PrivacyTier.HIGH`) | adapter-internal (bearer service token from `trove.ai.service-token`) | `trove.ai.timeout-seconds` (30s), `retry-attempts` (2), `retry-delay-ms` (500) | `extraction/AiExtractor.java:62` |
| MinIO (`trove.minio.endpoint`, default `http://minio:9000`) | S3-compatible SDK calls (`putObject`/`getObject`/`removeObject`/`getPresignedObjectUrl`/`statObject`) via `io.minio.MinioClient` | SDK-managed, not custom headers | not explicitly configured in this module — relies on MinIO SDK defaults | `storage/MinioStorageService.java` (whole file) |

No outbound calls anywhere read or forward an `Authorization` header — every outbound
call authenticates by service-token (AI) or by re-stamping `X-User-Id` from the already-
JWT-verified `receipt.getUserId()` (matching), never by relaying the caller's own JWT.

## 7. Scheduled/async

- No `@Scheduled` methods anywhere in `src/main` (verified: zero matches).
- `@Async("receiptExecutor")` on `ReceiptPipelineOrchestrator.processAsync(UUID receiptId, String userProvince)`
  (`pipeline/ReceiptPipelineOrchestrator.java:86`) — invoked from
  `ReceiptUploadService.upload()` (`upload/ReceiptUploadService.java:89`) after initial
  persist, and again from `ReceiptService.review()` (`receipt/ReceiptService.java:196`)
  when a reviewer submits corrections. Runs the 7-stage pipeline sequentially on the
  `receiptExecutor` pool (core 3 / max 6 / queue 100 / `CallerRunsPolicy`), persisting the
  receipt's `status` before each stage so pollers see forward progress.
- No `ApplicationEvent`/`@EventListener` usage anywhere in this module.

## 8. External dependencies

- **Postgres** — database `trove_receipt`, own schema, Flyway-migrated (§4).
- **MinIO** — object storage for original files + thumbnails, bucket `trove-receipts`
  (`storage/MinioConfig.java`). **Being dropped per owner decision** in favor of local
  disk; every call site in §6/§11 needs a disk-backed replacement.
- **PaddleOCR** — HTTP OCR service at `trove.paddleocr.base-url`; **no such container
  exists in any compose file** (confirmed dangling per prior audit M8, not re-verified
  here beyond confirming the property and adapter wiring are real). **Being cut per
  owner decision** in favor of vision-LLM extraction via the AI gateway.
- **model-manager / AI gateway / LiteLLM** — used only for the `EXTRACTING` stage
  fallback (`AiExtractor`), `ModelAlias.LOCAL_QUALITY` / `PrivacyTier.HIGH`. Consistent
  with the owner's "user-configured OpenAI-compatible endpoint, off until configured"
  direction, though today it goes through model-manager/LiteLLM rather than directly.
- **ingestion-service** — queried/patched for transaction matching (§6).
- No Redis, SMTP, SimpleFIN, Authentik, or LiteLLM-direct dependency in this module.

## 9. Tests

Total `@Test` methods across the module: **~114** (sum of per-file counts below;
`BaseIT`/`JwtTestHelper` are support classes, not test classes themselves).

| File | Count | Type | What it mocks/injects |
|---|---|---|---|
| `extraction/HeuristicExtractorTest.java` | 12 | unit | none — pure regex logic |
| `pipeline/ReceiptPipelineOrchestratorTest.java` | 8 | unit (Mockito) | mocks all 7 stage beans + `ReceiptRepository` |
| `pipeline/ReceiptPipelineIT.java` | 3 (2 real tests + setup) | Spring Boot IT, Testcontainers (Postgres+MinIO) + WireMock (PaddleOCR, ingestion) | full pipeline through real HTTP upload with a real signed JWT (`JwtTestHelper`) |
| `pipeline/stages/MatchingStageTest.java` | 10 | unit (Mockito + WireMock) | mocks `TransactionMatchingService`; asserts `X-User-Id` header sent to ingestion (`:157`, `:169`) |
| `pipeline/stages/ExtractionStageTest.java` | 5 | unit | mocks `AiExtractor` |
| `pipeline/stages/ParsingStageTest.java` | 5 | unit | mocks `HeuristicExtractor` |
| `pipeline/stages/TaxSplitStageTest.java` | 5 | unit | real `TaxSplitService` |
| `pipeline/stages/ScoringStageTest.java` | 8 | unit (Mockito) | mocks `LineItemRepository`, `TransactionMatchingService` |
| `pipeline/stages/OcrStageTest.java` | 5 | unit (Mockito) | mocks `MinioStorageService`, `PaddleOcrAdapter` |
| `pipeline/stages/ValidationStageTest.java` | 6 | unit (Mockito) | mocks `MinioStorageService` |
| `receipt/ReceiptControllerIT.java` | 11 | Spring Boot IT (real JWT via `JwtTestHelper`, `Authorization: Bearer …`) | real security filter chain exercised — not `@WithMockUser` |
| `receipt/ReceiptServiceTest.java` | 4 | unit (Mockito) | mocks `ReceiptRepository`, `LineItemRepository`, `MinioStorageService`, `TaxSplitService`, `ReceiptPipelineOrchestrator`, `TransactionMatchingService` |
| `storage/MinioStorageServiceTest.java` | 4 | unit (Mockito) | mocks `MinioClient` |
| `upload/MultipartFileValidatorTest.java` | 13 | unit | none — pure validation logic |
| `upload/ReceiptUploadServiceTest.java` | 3 | unit (Mockito) | mocks `ReceiptRepository`, `MinioStorageService`, `ReceiptPipelineOrchestrator`, `MultipartFileValidator` |
| `upload/ReceiptUploadControllerIT.java` | 5 | Spring Boot IT (real JWT) | real filter chain |
| `taxsplit/TaxSplitServiceTest.java` | 7 | unit | real `ProvinceTaxRates` |
| `OpenApiTest.java` | 1 | Spring Boot slice | asserts `/v3/api-docs` resolves |

**Positive finding, contradicting the prior audit's blanket M10 claim ("EVERY service
test injects authentication directly")**: this module's integration tests
(`ReceiptControllerIT`, `ReceiptUploadControllerIT`, `ReceiptPipelineIT`) all mint real
signed JWTs via `JwtTestHelper.tokenFor(UUID)` and send them as a genuine
`Authorization: Bearer` header — the real `JwtAuthFilter`/`SecurityConfig` chain is
exercised, not bypassed with `@WithMockUser`. M10 does not hold for receipt-processor
specifically.

No test in this module asserts a wrong behavior as correct that I could find — the
closest candidate, `ScoringStageTest`, only exercises `matchConfidence` at 0.0/0.92/0.96/1.0
alongside varying ocr/extraction confidence; it never specifically exercises the
realistic "no transaction match, everything else maxed" combination that caps the
composite score at 0.80 (see D10 in §10), so the ceiling bug is simply untested rather
than asserted-as-correct.

## 10. Verified defects

| id | Summary | file:line | Verified how |
|---|---|---|---|
| D1 | **Composite confidence can never reach the 0.92 review threshold — and therefore line items are never persisted — for any receipt that doesn't match an existing transaction.** `composite = ocr*0.35 + extraction*0.45 + match*0.20`; with `matchConfidence=0.0` (no match found, or ingestion-service unreachable) the ceiling is `0.35+0.45=0.80 < 0.92`. `persistLineItems()` and the transaction-patch call only run when `clamped >= REVIEW_THRESHOLD`. | `pipeline/stages/ScoringStage.java:73-91` (formula), `:83-84` (threshold gate) | Read source directly; confirms prior audit M8's claim. |
| D2 | US-only slash-date parsing corrupts non-US receipts: `MM/dd/yyyy` is the only slash format tried, so a Canadian/UK-style `05/06/2026` (5 June) is parsed as May 6. | `extraction/HeuristicExtractor.java:107-113` (`DateTimeFormatter.ofPattern("MM/dd/yyyy")`) | Read source directly; confirms prior audit M8's claim. |
| D3 | Currency inference stamps `CAD` on any receipt merely containing a bare `$` sign, including US receipts with no `USD`/`CAD` token: `if (upper.contains("CAD") \|\| upper.contains("$")) return "CAD";`. Given the owner's plan to make USD first-class for US users, this silently mis-tags US receipts. | `extraction/HeuristicExtractor.java:154` | Read source directly; confirms prior audit M8's claim. |
| D4 | `trove.ai.service-token` is a hard-required startup property (`AiGatewayProperties.validate()` throws `IllegalStateException` if blank) but is **not present anywhere in `application.yml`** for this module — the only way it can be satisfied is undocumented relaxed env-var binding. Anyone reading only this module's config would conclude AI extraction "just needs `MODEL_MANAGER_URL`" and hit a startup crash. | `src/main/resources/application.yml` (absent), required by `libs/ai-gateway-client/src/main/java/finance/trove/ai/gateway/AiGatewayProperties.java:65-69` | Read both files; grepped for `service-token`/`serviceToken` in `application.yml` — zero matches. |
| D5 | `spring.datasource.username`/`password` default to the literal `trove`/`trove` with no forced override, i.e. a default secret that would reach a public-internet Postgres if the env vars are ever forgotten in a deployment script. | `src/main/resources/application.yml:12-14` | Read source directly. |
| D6 | **The prior audit's A1 finding does not reproduce against the current working tree.** A1 claimed `MinioStorageService.java:5` imports the broken `io.minio.http.Method` (pre-9.x path). The current file imports `io.minio.Http.Method` (the correct nested-class path for the pinned `minio 9.0.3` — confirmed against `pom.xml:66` `<minio.version>9.0.3</minio.version>`), which is the *fixed* form A1 itself describes as correct. This specific compile-breaking import is not present in the current tree; A1 is stale for this file as of this audit. (Not re-verified: whether the module compiles clean end-to-end for other reasons — that requires an actual `mvn clean` build, which this task's rules forbid running.) | `storage/MinioStorageService.java:5`; contrast with `docs/recovery/01-FINDINGS.md:32-36` | `git log -p` on the file shows no import-line change since the `edd0c00` feature commit; grepped the whole module and `report-service`'s sibling `MinioStorageService.java` — both already use `io.minio.Http.Method`. |
| D7 | `ReceiptStatus.QUEUED` exists in the Java enum and the V3 migration's DB CHECK constraint, but is never assigned by any code path in this module (`ReceiptPipelineOrchestrator` goes straight from `UPLOADED` to `VALIDATING`) — dead enum value, harmless but worth pruning during the collapse rather than carrying it forward. | `receipt/ReceiptStatus.java:16` (javadoc self-admits "reserved for future use"), `pipeline/ReceiptPipelineOrchestrator.java:98-103` | Read source; grepped for `QUEUED` usage outside the enum/migration — none found. |
| D8 | The PaddleOCR wire-protocol contract this module's tests assume (`{"results":[{"text","confidence","box"}]}`, per `ReceiptPipelineIT.java:100`) is invented, per the prior audit's M8 finding; not independently re-verified against a real PaddleOCR instance here (none exists to test against), but the WireMock stub shape is confirmed to match what `OcrStage`/`PaddleOcrAdapter` expect. Moot once PaddleOCR is cut per the owner's decision. | `pipeline/ReceiptPipelineIT.java:98-101` | Read the WireMock stub and cross-referenced against `plugins/models/paddleocr-adapter` DTOs (not fully re-audited; carried forward from prior audit with the caveat noted). |

## 11. Collapse recommendation (per package in `src/main`)

| Package | Action | Destination (if KEEP) | Reason |
|---|---|---|---|
| `finance.trove.receipt` (root, `TroveReceiptProcessorApplication`) | DELETE | — | Bootstrap class disappears; trove-core has one `@SpringBootApplication`. |
| `finance.trove.receipt.config` (`IngestionProperties`, `ReceiptAsyncConfig`, `ReceiptPipelineConfig`, `ReceiptSecurityConfig`) | REWRITE | `finance.trove.core.receipt.config` | `ReceiptSecurityConfig` duplicates the same `@Import` boilerplate every other service hand-rolls (per O2, this should collapse to one core security config, not 10 copies). `ReceiptAsyncConfig`'s `receiptExecutor` bean name must be namespaced or merged with other services' per-domain executors to avoid bean-name clashes (see §12). `ReceiptPipelineConfig`/`IngestionProperties` keep their shape but move packages. |
| `finance.trove.receipt.extraction` (`AiExtractor`, `HeuristicExtractor`, `HeuristicExtractionResult`, `LineItemCandidate`) | REWRITE | `finance.trove.core.receipt.extraction` | Keep heuristic extractor as-is (fix D2/D3 while moving). `AiExtractor` needs to become the vision-LLM entry point per the owner's OCR-replacement decision (see the special-attention note below) rather than text-only OCR-then-extract. |
| `finance.trove.receipt.matching` (`TransactionMatchingService`, `TransactionSummary`, `MatchingServiceException`) | KEEP (internal-call rewrite) | `finance.trove.core.receipt.matching` | Logic is sound; once ingestion and receipts are in the same JVM/transaction this becomes an in-process service call (or at minimum drop the `X-User-Id` header hop and the HTTP timeout/retry plumbing) instead of `RestClient` + Apache HttpClient5. |
| `finance.trove.receipt.pipeline` + `.pipeline.stages` (orchestrator, 7 stages, context, exceptions) | KEEP (targeted edits) | `finance.trove.core.receipt.pipeline` | Core business logic, well-isolated, well-tested. Edits needed: `OcrStage`→delete or replace with vision-LLM call (owner decision); `TaxSplitStage`/`taxsplit` package stays (tax-time export needs GST/PST/HST breakdown data, even though the tax *engine*/Sa100 exporter is cut — this reverse-calculator is a different, smaller feature the owner did not flag for removal, but confirm this explicitly since it sits adjacent to the cut tax engine). Fix D1 (score-ceiling bug) while touching `ScoringStage`. |
| `finance.trove.receipt.receipt` (entity, repos, controller, service, DTOs, `ReceiptStatus`) | KEEP | `finance.trove.core.receipt` | Core domain. Drop `QUEUED` from the enum (D7) during the move; the `receipt`/`line_item` table names likely need a schema check against the other 9 services being merged (see §12) since Postgres now has one catalog. |
| `finance.trove.receipt.storage` (`MinioConfig`, `MinioProperties`, `MinioStorageService`, `StorageException`) | REWRITE | `finance.trove.core.receipt.storage` | **MinIO is being dropped.** Replace `MinioStorageService`'s four operations (upload/download/delete/presignedUrl) with a local-disk-backed implementation preserving the same call sites (`ValidationStage`, `OcrStage`→replacement, `ReceiptUploadService`, `ReceiptService.getPresignedUrl`/`deleteFromStorage`). `report-service` has an identically-named `MinioStorageService.java` (`services/report-service/src/main/java/finance/trove/report/storage/MinioStorageService.java`) — **class-name clash**, must be merged into one storage abstraction, not two copies under different packages. |
| `finance.trove.receipt.taxsplit` (`ProvinceTaxRates`, `TaxSplit`, `TaxSplitService`) | KEEP | `finance.trove.core.receipt.taxsplit` | This is the GST/PST/HST reverse-calculator used to populate the tax-inclusive breakdown on a receipt — distinct from the Sa100/tax-engine that's explicitly cut. Confirm with the owner it survives (it produces the categorized-transaction tax data the CSV/PDF export will need), just flagging the naming proximity to "tax engine" as a scope-decision risk. |
| `finance.trove.receipt.upload` (`MultipartFileValidator`, `ReceiptUploadController`, `ReceiptUploadService`, `UploadResponse`) | KEEP | `finance.trove.core.receipt.upload` | Core domain; `ReceiptUploadService` needs its two `minioStorageService.upload()` calls repointed at the new disk-storage implementation, everything else (validation, thumbnailing, DB persist, `processAsync` kick-off) is storage-agnostic already. |

**Classes duplicating `libs/common` or another service** (named per the task's explicit
list):
- `ReceiptSecurityConfig` — duplicate boilerplate pattern repeated in (at least)
  `services/report-service` and presumably every other service; collapse to one config
  in trove-core, or better, give `libs/common` a real `AutoConfiguration.imports` file
  per O2 so no service needs this class at all.
- `finance.trove.receipt.storage.MinioStorageService` — name-clashes with
  `finance.trove.report.storage.MinioStorageService` in `report-service`. Both are being
  replaced by disk storage anyway; merge into one `finance.trove.core.storage.ReceiptFileStore`
  (or similar) rather than keeping two classes.
- `JwtAuthFilter`, `JwtProperties`, `GlobalExceptionHandler`, `RequestIdMdcFilter`,
  `SecurityConfig` — **not duplicated in this module**, these are consumed directly
  from `libs/common` via `@Import` (`config/ReceiptSecurityConfig.java:22`), so
  receipt-processor itself contributes nothing to consolidate here; the risk is that
  *other* services in the collapse hand-rolled their own copies instead of importing
  these — check each sibling module's inventory for that.

## 12. Merge risks/gotchas

- **Bean-name clash**: `ReceiptAsyncConfig` declares `@Bean("receiptExecutor")`
  (`config/ReceiptAsyncConfig.java:24`). Every other service that has its own
  `@Async`-backed pipeline (report-service, categorisation-service, etc.) likely declares
  its own similarly-named executor bean (`reportExecutor`, etc. — not verified here, flag
  for cross-module inventory pass). Since trove-core will be one Spring context, any two
  services that happen to reuse the *same* bean name (or that both declare an unnamed
  `ThreadPoolTaskExecutor` @Bean and rely on Spring's "default executor" resolution) will
  collide. `receiptExecutor` itself is safely namespaced already; just confirm siblings are too.
- **Table-name clash**: `receipt` and `line_item` are generic table names. Given "11
  databases become one Postgres process" is *already* true today (O6 in the prior
  audit) but each service currently gets its **own catalog** (`trove_receipt` here) —
  collapsing to one catalog for trove-core means `receipt`/`line_item` must be checked
  against every other service's schema for a same-name, different-shape table. Not
  found in this module's own scope, but this is the single highest-risk item for this
  package's migration.
- **Property-key clash**: `trove.ingestion.url`/`trove.ai.*`/`trove.minio.*`/
  `trove.paddleocr.*`/`trove.tax-split.*`/`trove.jwt.secret` are all global
  (non-service-prefixed) keys under the shared `trove.*` namespace. Multiple services
  binding the *same* `@ConfigurationProperties("trove.ingestion")` etc. is actually
  fine/intended (that's how `IngestionProperties` is meant to be shared) — but
  `trove.tax-split.*` and `trove.minio.*` are receipt-processor-local property trees
  that must not accidentally collide with another service's use of the same prefix for
  a different purpose. Not observed here, but worth a grep across all 10
  `application.yml` files before merging.
- **Static state**: none found — no `static` mutable fields, no singletons holding
  request-scoped data, in this module's `src/main`.
- **Hardcoded ports/hostnames**: `trove.paddleocr.base-url` defaults to
  `http://paddleocr:8866` (dangling, being cut); `trove.minio.endpoint` defaults to
  `http://minio:9000` (being cut); `trove.ai.model-manager-url` defaults to
  `http://model-manager:8089`; `trove.ingestion.url` defaults to
  `http://ingestion-svc:8081` (this one is correct, matches actual compose service
  name — see §5). `server.port: 8085` becomes irrelevant once this collapses into
  trove-core's single port.
- **Hardcoded currency (CAD) sites**: `HeuristicExtractor.extractCurrency()`
  (`extraction/HeuristicExtractor.java:152-156`) defaults a bare `$` to `CAD` (D3
  above) — this is the sharpest currency hardcode in the module and directly conflicts
  with the owner's CAD/USD-both-first-class decision; must be fixed (infer from
  per-user currency setting, not from OCR text guessing) as part of the merge, not
  carried forward as-is. `taxsplit/*` is Canada-only by design (GST/PST/HST reverse
  calculator) — that's fine as a Canada-specific *feature*, not a hardcode bug, but
  confirm it's gated off (or simply skipped) for USD/US-onboarded users rather than
  silently applying Canadian tax math to a US receipt; nothing in `TaxSplitStage`
  currently checks the user's country/currency before running (`pipeline/stages/TaxSplitStage.java:48-49`
  only checks `extractedAmount != null`, never currency or country).
- **Anything that reads `X-User-Id`**: nothing in `src/main` of this module reads
  `X-User-Id` inbound (identity is JWT-`sub`-derived only, per §2). This module only
  *writes* `X-User-Id` outbound to ingestion-service (`matching/TransactionMatchingService.java:83,124`)
  — once matching becomes an in-process call inside trove-core, both the header and the
  HTTP hop disappear entirely; do not carry the header pattern into the merged code.
