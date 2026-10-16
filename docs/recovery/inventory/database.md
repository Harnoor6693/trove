# Database Inventory — Trove Monolith Collapse (DATABASE plan)

> **Historical record, 2026-09-03.** Inventory of the ten pre-rebuild Flyway histories. The permission to rewrite migrations applied only before the rebuild; the current schema is one history ending at V46 and is not to be rewritten. Current state: `docs-site/guide/architecture.md`.

Verified directly against source on 2026-09-03. Every migration file below was read in
full (not sampled). Section O of `docs/recovery/01-FINDINGS.md` was treated as
superseding earlier sections; where this report touches O6/O7 it is called out.

No production data exists anywhere (confirmed: this is a dev repo, `docker-compose`
volumes are throwaway). **Every migration listed in §10 may be rewritten freely** — the
only hard constraint is that every column a Java `@Entity`/DTO or a web/mobile client
currently reads must have a home in the new schema.

---

## 1. Sources

**Flyway migrations (34 files, 10 service modules, all under
`services/*/src/main/resources/db/migration/`):**

| Service | Migrations |
|---|---|
| api-gateway | V1–V5 |
| budget-service | V1–V6 |
| categorisation-service | V1–V3 |
| ingestion-service | V1–V4 |
| model-manager | V1–V7 |
| notification-service | V1–V5 |
| receipt-processor | V1–V3 |
| report-service | V1 |
| subscription-detector | V1–V2 |
| tax-engine | V1–V6 |

No Java-based (`JavaMigration`) migrations exist anywhere (`find` for `*.java` under any
`db/migration` returned nothing). No `schema.sql`, `data.sql`, or `import.sql` exists
anywhere in the repo (checked repo-wide, excluding `node_modules`/`target`). No
Testcontainers init scripts exist — every `BaseIT` class (28 files under
`services/*/src/test/java`, e.g.
`services/budget-service/src/test/java/finance/trove/budget/support/BaseIT.java`) spins
up a bare `PostgreSQLContainer` and lets that module's own Flyway migrations populate it;
there is no separate seed SQL for tests.

**Database catalogs.** Two DIFFERENT init scripts create the 11 catalogs, and they
disagree:

- `docker/postgres-init.sql` (used by `docker/docker-compose.yml`, the compose file the
  README points at) creates **11** databases: `trove_gateway`, `trove_ingestion`,
  `trove_categorisation`, `trove_notification`, `trove_budget`, `trove_report`,
  `trove_models`, `trove_receipt`, `trove_subscription`, `trove_tax`, `authentik`.
- `infra/full-stack/postgres-init/01-create-databases.sql` creates only **10** —
  **`trove_receipt` is missing** from the list (verified by diff of the two files'
  `VALUES`/`CREATE DATABASE` lists). Any deployment that boots from
  `infra/full-stack/` and lets Postgres run its init scripts leaves receipt-processor
  with no database to migrate into. This is moot for the collapse (all 11 catalogs
  disappear into one), but it's a live defect in the pre-collapse stack worth a one-line
  mention to whoever is still running that compose file during the transition.

**Per-service DB name / owning catalog** (from each `application.yml`, cross-checked
against the `docker/docker-compose.yml` service env blocks):

| Service | DB name | Config source |
|---|---|---|
| api-gateway | `trove_gateway` | `application.yml:11` `${GATEWAY_POSTGRES_URL}` → compose line 114 |
| budget-service | `trove_budget` | `application.yml:9` default, compose line 265 |
| categorisation-service | `trove_categorisation` | `application.yml:9` default, compose line 195 |
| ingestion-service | `trove_ingestion` | `application.yml:9` default, compose line 161 |
| model-manager | `trove_models` | `application.yml:10` `${MODELS_POSTGRES_URL}` → compose line 454 |
| notification-service | `trove_notification` | `application.yml:10` `${NOTIFICATION_POSTGRES_URL}` → compose line 378 |
| receipt-processor | `trove_receipt` | `application.yml:12` default, compose line 298 |
| report-service | `trove_report` | `application.yml:9` `${DB_NAME:trove_report}`, compose line 416 |
| subscription-detector | `trove_subscription` | `application.yml:7` `${DB_NAME:trove_subscription}`, compose line 339 |
| tax-engine | `trove_tax` | `application.yml:7` `${DB_NAME:trove_tax}`, compose line 232 |

---

## 2. Cross-service inventory

Legend: `col:type` — `N`=NOT NULL, nullable otherwise; default shown in `()` when present.

### api-gateway (`trove_gateway`)

**`audit_log`** — V1, altered V3
- `id:UUID` PK `DEFAULT gen_random_uuid()`
- `user_id:UUID` (nullable)
- `method:VARCHAR(10)` N
- `path:VARCHAR(500)` N
- `status:INTEGER` N
- `duration_ms:BIGINT` N
- `ip_address:VARCHAR(45)` (nullable — was `INET`, changed by V3 for Hibernate `String` compatibility)
- `user_agent:VARCHAR(500)` (nullable)
- `request_id:UUID` N
- `created_at:TIMESTAMPTZ` N `(now())`
- Indexes: `idx_audit_log_user_id(user_id)`, `idx_audit_log_created_at(created_at DESC)`

**`rate_limit_config`** — V2, reseeded V4 (data-only migration, no DDL)
- `id:UUID` PK
- `endpoint_pattern:VARCHAR(200)` N UNIQUE
- `requests_per_min:INTEGER` N
- `burst_capacity:INTEGER` N
- `updated_at:TIMESTAMPTZ` N `(now())`
- V4 `DELETE`s all rows and reseeds with production Bucket4j values (`/api/v1/auth/**`,
  `/api/v1/coach/stream`, `/api/v1/receipts/**`, `/api/v1/ingestion/sync`, `/api/**`).

**`user_preferences`** — V5
- `user_id:UUID` PK
- `deployment:VARCHAR(32)` (nullable — Java doc says "cloud"/"self-hosted"/"local")
- `ai_mode:VARCHAR(32)` (nullable — Java doc says "sonnet"/"haiku"/"local-llm")
- `onboarding_completed:BOOLEAN` N `(false)`
- `created_at:TIMESTAMPTZ` N, `updated_at:TIMESTAMPTZ` N
- Index: `user_preferences_user_id_idx(user_id)` (redundant with the PK — flag for §11)

### budget-service (`trove_budget`)

**`budget_period`** — V1
- `id:UUID` PK, `user_id:UUID` N, `month:DATE` N
- `total_income:NUMERIC(12,2)` N `(0)`, `currency:VARCHAR(3)` N `('CAD')`
- `budget_type:VARCHAR(12)` N `('FLEXIBLE')` CHECK ∈ {FLEXIBLE, ROLLOVER, ZERO_BASED}
- `created_at`/`updated_at:TIMESTAMPTZ` N
- UNIQUE `(user_id, month)`; index `idx_budget_period_user_id(user_id, month DESC)`

**`envelope`** — V2
- `id:UUID` PK, `user_id:UUID` N
- `period_id:UUID` N FK → `budget_period(id)` ON DELETE CASCADE
- `category_id:UUID` N (logical ref → categorisation-service `category`, not a real FK — cross-DB)
- `category_name:VARCHAR(100)` N (denormalised copy of the category name)
- `allocated`/`spent`/`rollover_amount:NUMERIC(12,2)` N `(0)`, `currency:VARCHAR(3)` N `('CAD')`
- `created_at`/`updated_at:TIMESTAMPTZ` N
- UNIQUE `(period_id, category_id)`; indexes on `period_id`, `user_id`, `(user_id, category_id)`

**`savings_goal`** — V3
- `id:UUID` PK, `user_id:UUID` N, `name:VARCHAR(200)` N
- `target_amount:NUMERIC(12,2)` N, `current_amount:NUMERIC(12,2)` N `(0)`
- `deadline:DATE` (nullable), `currency:VARCHAR(3)` N `('CAD')`, `is_achieved:BOOLEAN` N `(false)`
- `created_at`/`updated_at:TIMESTAMPTZ` N; index `idx_savings_goal_user_id(user_id)`

**`net_worth_snapshot`** — V4
- `id:UUID` PK, `user_id:UUID` N, `snapshot_date:DATE` N
- `total_assets`/`total_liabilities:NUMERIC(12,2)` N `(0)`
- `net_worth:NUMERIC(12,2)` **GENERATED ALWAYS AS** `(total_assets - total_liabilities) STORED`
- `currency:VARCHAR(3)` N `('CAD')`, `created_at:TIMESTAMPTZ` N
- UNIQUE `(user_id, snapshot_date)`; index `idx_net_worth_user_date(user_id, snapshot_date DESC)`

**`asset`** — V5
- `id:UUID` PK, `user_id:UUID` N, `name:VARCHAR(200)` N
- `asset_type:VARCHAR(15)` N CHECK ∈ {CASH, INVESTMENT, REAL_ESTATE, VEHICLE, OTHER}
- `value:NUMERIC(12,2)` N `(0)`, `currency:VARCHAR(3)` N `('CAD')`
- `account_id:UUID` (nullable — logical ref → ingestion-service `connected_account`, cross-DB)
- `created_at`/`updated_at:TIMESTAMPTZ` N; index `idx_asset_user_id(user_id)`

**`liability`** — V6
- `id:UUID` PK, `user_id:UUID` N, `name:VARCHAR(200)` N
- `liability_type:VARCHAR(20)` N CHECK ∈ {MORTGAGE, CAR_LOAN, STUDENT_LOAN, CREDIT_CARD, OTHER}
- `balance:NUMERIC(12,2)` N `(0)`, `interest_rate:NUMERIC(5,4)` N `(0)`
- `currency:VARCHAR(3)` N `('CAD')`, `account_id:UUID` (nullable, cross-DB logical ref, same as `asset`)
- `created_at`/`updated_at:TIMESTAMPTZ` N; index `idx_liability_user_id(user_id)`

### categorisation-service (`trove_categorisation`)

**`category`** — V1 (seeded with 51 system categories in the same migration)
- `id:UUID` PK, `name:VARCHAR(100)` N UNIQUE
- `parent_id:UUID` (nullable) FK → `category(id)` (self-referential, no ON DELETE clause —
  deleting a parent with children will fail the FK, not cascade)
- `icon:VARCHAR(50)` (nullable), `is_system:BOOLEAN` N `(true)`, `created_at:TIMESTAMPTZ` N

**`merchant_category_map`** — V2
- `id:UUID` PK, `merchant:VARCHAR(255)` N
- `match_type:VARCHAR(10)` N CHECK ∈ {EXACT, PREFIX}
- `category_id:UUID` N FK → `category(id)` (real FK, same DB)
- `confidence:DECIMAL(4,3)` N `(0.90)`, `created_at:TIMESTAMPTZ` N
- Index `idx_merchant_map_merchant(merchant)`

**`category_override`** — V3
- `id:UUID` PK, `user_id:UUID` N
- `transaction_id:UUID` N (logical ref → ingestion-service `transaction`, cross-DB)
- `category_id:UUID` N FK → `category(id)` (real FK, same DB)
- `created_at:TIMESTAMPTZ` N; UNIQUE `(user_id, transaction_id)`

### ingestion-service (`trove_ingestion`)

**`connected_account`** — V1
- `id:UUID` PK, `user_id:UUID` N
- `source_plugin:VARCHAR(50)` N (plugin id — `simplefin`/`ofx`)
- `display_name:VARCHAR(200)` N, `institution:VARCHAR(200)` (nullable)
- `account_type:VARCHAR(20)` N CHECK ∈ {CHECKING, SAVINGS, CREDIT, INVESTMENT, OTHER}
- `currency:VARCHAR(3)` N `('CAD')`
- `config_encrypted:TEXT` N (plugin credentials, encrypted at rest)
- `last_synced_at:TIMESTAMPTZ` (nullable)
- `sync_status:VARCHAR(10)` N `('PENDING')` CHECK ∈ {PENDING, OK, ERROR}
- `error_message:TEXT` (nullable), `created_at`/`updated_at:TIMESTAMPTZ` N
- Indexes: `idx_connected_account_user_id(user_id)`, `idx_connected_account_user_status(user_id, sync_status)`

**`transaction`** — V2
- `id:UUID` PK
- `account_id:UUID` N FK → `connected_account(id)` ON DELETE CASCADE
- `user_id:UUID` N
- `source_id:VARCHAR(255)` (nullable) — **see §6.f, this is the M0/O7 column**
- `amount:DECIMAL(19,4)` N — note the 4-decimal scale, see §6
- `currency:VARCHAR(3)` N `('CAD')`
- `type:VARCHAR(10)` N CHECK ∈ {DEBIT, CREDIT}
- `description:TEXT` (nullable), `merchant_name:VARCHAR(255)` (nullable)
- `date:DATE` N, `posted_at:TIMESTAMPTZ` (nullable) — two distinct time concepts, see §7
- `pending:BOOLEAN` N `(false)`
- `category_id:UUID` (nullable, logical ref → categorisation-service `category`, cross-DB)
- `tags_json:TEXT` (nullable — a JSON array serialized to TEXT, not JSONB — inconsistent
  with `receipt.extracted_line_items` and `report.requested_filters`, both real JSONB)
- `notes:TEXT` (nullable), `created_at`/`updated_at:TIMESTAMPTZ` N
- Indexes: `idx_transaction_account_id`, `idx_transaction_user_id`, `idx_transaction_date`
- `idx_transaction_source` **UNIQUE** on `(account_id, source_id) WHERE source_id IS NOT NULL` — see §6.f

**`sync_log`** — V3, altered V4
- `id:UUID` PK
- `account_id:UUID` N FK → `connected_account(id)` ON DELETE CASCADE
- `user_id:UUID` N (not independently indexed — only reachable via `account_id`, see §4)
- `plugin_id:VARCHAR(50)` (nullable)
- `status:VARCHAR(10)` N `('RUNNING')` (no CHECK constraint — unusual next to `sync_status`,
  which does have one, on the same conceptual axis in `connected_account`)
- `transactions_fetched`/`_new`/`_updated`/`_skipped:INT` N `(0)`
- `transactions_failed:INT` N `(0)` — added by V4
- `error_message:TEXT` (nullable)
- `started_at:TIMESTAMPTZ` N `(now())`, `completed_at:TIMESTAMPTZ` (nullable)
- Index: `idx_sync_log_account_id(account_id)`

### model-manager (`trove_models`)

**`model_registration`** — V1 (seeded with 4 rows: LOCAL_FAST/LOCAL_QUALITY/HYBRID_EFFICIENT/CLOUD_QUALITY)
- `id:UUID` PK, `alias:VARCHAR(30)` N UNIQUE, `provider:VARCHAR(20)` N, `model_id:VARCHAR(100)` N
- `is_local:BOOLEAN` N `(false)`, `min_privacy_tier:VARCHAR(10)` N `('LOW')`
- `context_window:INT` N `(8192)`, `is_active:BOOLEAN` N `(true)`, `is_healthy:BOOLEAN` N `(false)`
- `last_health_at:TIMESTAMPTZ` (nullable), `created_at:TIMESTAMPTZ` N
- This is the "hardware/model registry" flagged for removal in verification — see §8/§9.

**`ai_audit_log`** — V2
- `id:UUID` PK, `user_id:UUID` (nullable), `service:VARCHAR(50)` (nullable), `alias:VARCHAR(30)` (nullable)
- `provider:VARCHAR(20)` (nullable), `model_id:VARCHAR(100)` (nullable), `privacy_tier:VARCHAR(10)` (nullable)
- `prompt_tokens`/`output_tokens:INT` (nullable), `latency_ms:BIGINT` (nullable)
- `success:BOOLEAN` N `(true)`, `error_message:TEXT` (nullable), `created_at:TIMESTAMPTZ` N
- Indexes: `idx_audit_log_user_id`, `idx_audit_log_created_at` — **same index names as
  api-gateway's `audit_log`**, harmless today (different DBs) but will collide by name if
  both tables ever share one schema without renaming (Postgres index names must be
  unique per-schema, not just per-table) — flag for §10.

**`coach_conversation`** — V3, altered V4, altered again V5 (final shape below)
- `id:UUID` PK, `user_id:UUID` N
- `role:VARCHAR(20)` N CHECK ∈ {SYSTEM, USER, ASSISTANT} (V5 uppercased existing data and
  replaced the original lowercase `{user, assistant}` CHECK)
- `content:TEXT` N
- `session_id:UUID` (nullable, added V4 — **no FK target exists**; sessions are ValKey-only,
  see §5)
- `privacy_tier:VARCHAR(10)` N `('HIGH')` (added V4 as nullable/`DEFAULT 'LOW'`, tightened
  to NOT NULL/`DEFAULT 'HIGH'` by V5, backfilled)
- `model_used:VARCHAR(100)` (nullable, added V4)
- `model_alias:VARCHAR(30)` — added V4, **dropped again by V5** (net: does not exist)
- `created_at:TIMESTAMPTZ` N
- Indexes (final, post-V5): `idx_coach_user_session(user_id, session_id, created_at ASC)`,
  `idx_coach_user_id(user_id, created_at DESC)`. (The V3 index
  `idx_coach_conversation_user_id` and the V4 `idx_coach_conversation_session` are
  superseded but not dropped — Flyway leaves them; both are redundant with the V5 indexes
  and should not be recreated in the collapsed schema.)

**`coach_insight`** — V6
- `id:UUID` PK, `user_id:UUID` N, `insight_text:TEXT` N
- `delivered:BOOLEAN` N `(false)`, `created_at:TIMESTAMPTZ` N, `delivered_at:TIMESTAMPTZ` (nullable)
- Partial index `idx_coach_insight_user_undelivered(user_id, delivered, created_at DESC) WHERE delivered = false`

**`shedlock`** — V7 — see §3 (identical table also created by notification-service V5)

### notification-service (`trove_notification`)

**`notification_preference`** — V1
- `id:UUID` PK, `user_id:UUID` N UNIQUE
- `email_enabled:BOOLEAN` N `(true)`, `email_address:VARCHAR(320)` (nullable)
- `push_enabled:BOOLEAN` N `(false)`, `apns_token:TEXT` (nullable), `fcm_token:TEXT` (nullable)
- `weekly_digest_enabled:BOOLEAN` N `(true)`
- `budget_alert_threshold:NUMERIC(3,2)` N `(0.90)` (a ratio, not money)
- `subscription_alert_enabled:BOOLEAN` N `(true)`, `tax_reminder_enabled:BOOLEAN` N `(true)`
- `updated_at:TIMESTAMPTZ` N; index `idx_notification_pref_user_id(user_id)` (redundant with the UNIQUE)

**`notification_log`** — V2
- `id:UUID` PK, `user_id:UUID` N
- `channel:VARCHAR(12)` N CHECK ∈ {EMAIL, PUSH_APNS, PUSH_FCM}
- `type:VARCHAR(20)` N CHECK ∈ {BUDGET_OVERAGE, SUBSCRIPTION_ALERT, TAX_REMINDER,
  WEEKLY_DIGEST, SYNC_COMPLETE, SYNC_ERROR, CUSTOM}
- `subject:VARCHAR(500)` (nullable), `body:TEXT` (nullable)
- `status:VARCHAR(8)` N CHECK ∈ {PENDING, SENT, FAILED}
- `sent_at:TIMESTAMPTZ` (nullable), `error_message:TEXT` (nullable), `created_at:TIMESTAMPTZ` N
- Index `idx_notification_log_user_id(user_id, created_at DESC)`; partial index
  `idx_notification_log_pending(status) WHERE status = 'PENDING'`

**`in_app_notification`** — V3
- `id:UUID` PK, `user_id:UUID` N, `type:VARCHAR(30)` N, `title:VARCHAR(300)` N, `body:TEXT` N
- `action_url:TEXT` (nullable), `read:BOOLEAN` N `(false)`, `dismissed:BOOLEAN` N `(false)`
- `created_at:TIMESTAMPTZ` N, `read_at`/`dismissed_at:TIMESTAMPTZ` (nullable)
- Partial indexes: `idx_in_app_user_active(user_id, created_at DESC) WHERE dismissed = false`,
  `idx_in_app_user_unread_count(user_id) WHERE read = false AND dismissed = false`

**`webhook_subscription`** + **`webhook_delivery_log`** — V4
- `webhook_subscription`: `id:UUID` PK, `user_id:UUID` N, `target_url:TEXT` N,
  `secret:VARCHAR(128)` N, `enabled:BOOLEAN` N `(true)`, `event_types:TEXT[]` N `('{}')`,
  `created_at`/`updated_at:TIMESTAMPTZ` N. Index `idx_webhook_sub_user(user_id)`.
- `webhook_delivery_log`: `id:UUID` PK, `subscription_id:UUID` N FK →
  `webhook_subscription(id)` ON DELETE CASCADE, `event_type:VARCHAR(50)` N,
  `payload:TEXT` N, `status:VARCHAR(10)` N CHECK ∈ {PENDING, DELIVERED, FAILED, EXHAUSTED},
  `attempt_count:INT` N `(0)`, `next_attempt_at:TIMESTAMPTZ` (nullable),
  `last_status_code:INT` (nullable), `error_message:TEXT` (nullable),
  `created_at`/`updated_at:TIMESTAMPTZ` N.
  Partial index `idx_webhook_log_pending(status, next_attempt_at) WHERE status IN
  ('PENDING','FAILED')`; index `idx_webhook_log_sub(subscription_id, created_at DESC)`.

**`shedlock`** — V5 — see §3

### receipt-processor (`trove_receipt`)

**`receipt`** — V1, altered V3
- `id:UUID` PK, `user_id:UUID` N
- `status:VARCHAR(15)` N `('UPLOADED')` CHECK — V1 set: {UPLOADED, VALIDATING, OCR,
  PARSING, EXTRACTING, MATCHING, TAX_SPLIT, SCORING, COMPLETE, FAILED}; V3 replaced the
  constraint to add `QUEUED` (net 11 values)
- `image_key:TEXT` (nullable), `thumbnail_key:TEXT` (nullable) — MinIO object keys today;
  repurpose as local-disk paths (§8/§9)
- `source:VARCHAR(10)` N `('UPLOAD')` CHECK ∈ {UPLOAD, SCAN, EMAIL} — `EMAIL` is dead per
  the "IMAP/bank-email ingestion is cut" decision, but it's just an enum value on an
  existing column, not a table; no action needed beyond dropping it from the CHECK
- `upload_size_bytes:BIGINT` N `(0)`
- `ocr_text:TEXT` (nullable)
- `extracted_merchant:VARCHAR(200)` (nullable), `extracted_amount:NUMERIC(12,2)` (nullable)
- `extracted_currency:VARCHAR(3)` (nullable, **no default** — the one money-adjacent
  currency column in the whole schema without `DEFAULT 'CAD'`, see §6)
- `extracted_date:DATE` (nullable), `extracted_line_items:JSONB` (nullable)
- `gst_amount`/`pst_amount`/`hst_amount:NUMERIC(12,2)` (nullable each)
- `confidence:NUMERIC(3,2)` (nullable)
- `matched_transaction_id:UUID` (nullable, logical ref → ingestion-service `transaction`, cross-DB)
- `review_required:BOOLEAN` N `(false)`, `error_message:TEXT` (nullable)
- `processing_started_at`/`processing_completed_at:TIMESTAMPTZ` (nullable)
- `created_at`/`updated_at:TIMESTAMPTZ` N
- Indexes: `idx_receipt_user_id(user_id, created_at DESC)`; partial
  `idx_receipt_status(status) WHERE status NOT IN ('COMPLETE','FAILED')`; partial
  `idx_receipt_user_review(user_id, review_required) WHERE review_required = true`

**`line_item`** — V2
- `id:UUID` PK, `receipt_id:UUID` N FK → `receipt(id)` ON DELETE CASCADE
- `description:TEXT` N, `quantity:NUMERIC(8,2)` N `(1)`
- `unit_price`/`total_price:NUMERIC(10,2)` N `(0)` — yet a third money precision/scale, see §6
- `is_taxable:BOOLEAN` N `(true)`, `created_at:TIMESTAMPTZ` N
- No `currency` column (inherits the parent `receipt.extracted_currency`)
- Index `idx_line_item_receipt_id(receipt_id)`

### report-service (`trove_report`)

**`report`** — V1
- `id:UUID` PK, `user_id:UUID` N
- `report_type:VARCHAR(20)` N CHECK ∈ {MONTHLY_SUMMARY, ANNUAL_SUMMARY, TAX_EXPORT, CUSTOM}
- `format:VARCHAR(5)` N CHECK ∈ {CSV, PDF}
- `status:VARCHAR(12)` N `('PENDING')` CHECK ∈ {PENDING, GENERATING, COMPLETE, FAILED}
- `period_start`/`period_end:DATE` (nullable)
- `file_key:TEXT` (nullable — MinIO key today, → local path)
- `file_size_bytes:INTEGER` (nullable), `error_message:TEXT` (nullable)
- `requested_filters:JSONB` (nullable)
- `created_at`/`updated_at:TIMESTAMPTZ` N
- Index `idx_report_user_id(user_id, created_at DESC)`; partial
  `idx_report_status(status) WHERE status IN ('PENDING','GENERATING')`

**Important cross-check** (source, not migration): `ReportType.TAX_EXPORT` already exists
and `CsvReportBuilder.headersFor(TAX_EXPORT)`
(`services/report-service/src/main/java/finance/trove/report/generation/CsvReportBuilder.java:58`)
emits exactly `date, merchant, description, amount, currency, gst_amount, category,
deductible_reason` — i.e. **this table and code path is already the "tax-time CSV export
of categorised transactions" the owner's decision calls for.** See §8 for why this
changes the "report artefacts" drop recommendation, and §11 for a real bug in that same
builder (`gst_amount` is a *computed* number, and `currency` is hardcoded to the literal
string `"CAD"` — both need fixing to match the decision, not just carrying forward).

### subscription-detector (`trove_subscription`)

**`detected_subscription`** — V1
- `id:UUID` PK, `user_id:UUID` N, `merchant_name:VARCHAR(200)` N
- `category_id:UUID` (nullable, logical ref → categorisation-service `category`, cross-DB)
- `normalized_amount:NUMERIC(12,2)` N, `currency:VARCHAR(3)` N `('CAD')`
- `billing_cycle:VARCHAR(12)` N CHECK ∈ {WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY, ANNUAL}
- `next_charge_date:DATE` (nullable)
- `signal:VARCHAR(6)` N `('GREEN')` CHECK ∈ {GREEN, AMBER, RED}
- `price_changed:BOOLEAN` N `(false)`, `previous_amount:NUMERIC(12,2)` (nullable)
- `first_seen_date`/`last_seen_date:DATE` N
- `transaction_ids:UUID[]` N `('{}')` (array of logical refs → `transaction`, cross-DB,
  and arrays can never be real FKs even inside the collapsed schema — see §5/§10)
- `is_cancelled:BOOLEAN` N `(false)`, `created_at`/`updated_at:TIMESTAMPTZ` N
- Indexes: `idx_subscription_user_id(user_id)`; partial
  `idx_subscription_user_active(user_id, is_cancelled) WHERE is_cancelled = false`;
  partial `idx_subscription_next_charge(user_id, next_charge_date) WHERE is_cancelled = false`

**`subscription_alert`** — V2
- `id:UUID` PK, `user_id:UUID` N
- `subscription_id:UUID` N FK → `detected_subscription(id)` ON DELETE CASCADE
- `alert_type:VARCHAR(20)` N CHECK ∈ {PRICE_INCREASE, UPCOMING, PAYMENT_FAILED}
- `alert_message:TEXT` N, `is_read:BOOLEAN` N `(false)`, `created_at:TIMESTAMPTZ` N
- Index `idx_sub_alert_user_id(user_id, created_at DESC)`; partial
  `idx_sub_alert_unread(user_id, is_read) WHERE is_read = false`

### tax-engine (`trove_tax`) — **entire module cut per owner decision**

**`tax_profile`** — V1
- `id:UUID` PK, `user_id:UUID` N
- `jurisdiction:VARCHAR(2)` N CHECK ∈ {CA, US} (V6 widened to add GB)
- `tax_year:INTEGER` N, `filing_status:VARCHAR(30)` N, `province_or_state:VARCHAR(3)` (nullable)
- `employment_type:VARCHAR(15)` N `('EMPLOYED')` CHECK ∈ {EMPLOYED, SELF_EMPLOYED, BOTH}
- `created_at`/`updated_at:TIMESTAMPTZ` N; UNIQUE `(user_id, jurisdiction, tax_year)`
- Index `idx_tax_profile_user_id(user_id)`

**`tax_deduction`** — V2
- `id:UUID` PK, `user_id:UUID` N, `tax_profile_id:UUID` N FK → `tax_profile(id)` ON DELETE CASCADE
- `deduction_type:VARCHAR(50)` N, `description:TEXT` N, `amount:NUMERIC(12,2)` N
- `currency:VARCHAR(3)` N `('CAD')`, `transaction_id:UUID` (nullable, cross-DB logical ref)
- `document_url:TEXT` (nullable), `jurisdiction:VARCHAR(2)` N, `created_at:TIMESTAMPTZ` N
- Indexes: `idx_deduction_profile_id`, `idx_deduction_user_id`

**`tax_slip`** — V3, altered V6
- `id:UUID` PK, `user_id:UUID` N, `tax_profile_id:UUID` N FK → `tax_profile(id)` ON DELETE CASCADE
- `slip_type:VARCHAR(15)` N CHECK — V3: {T4, T4A, T5, RRSP, W2, 1099, 1099INT, 1099DIV};
  V6 added GB slip types {P60, P11D, SA100}
- `tax_year:INTEGER` N, `issuer:VARCHAR(200)` N, `amount:NUMERIC(12,2)` N
- `currency:VARCHAR(3)` N `('CAD')`, `document_url:TEXT` (nullable), `created_at:TIMESTAMPTZ` N
- Indexes: `idx_slip_profile_id`, `idx_slip_user_year(user_id, tax_year)`

**`rrsp_tfsa_fhsa_room`** — V4
- `id:UUID` PK, `user_id:UUID` N, `tax_year:INTEGER` N
- `rrsp_room`/`rrsp_used`/`tfsa_room`/`tfsa_used`/`fhsa_room`/`fhsa_used:NUMERIC(12,2)` N `(0)` each
- `updated_at:TIMESTAMPTZ` N; UNIQUE `(user_id, tax_year)`; index `idx_room_user_year(user_id, tax_year DESC)`

**`tax_calculation_cache`** — V5
- `id:UUID` PK, `user_id:UUID` N, `tax_profile_id:UUID` N FK → `tax_profile(id)` ON DELETE CASCADE
- `calculated_at:TIMESTAMPTZ` N
- `total_income`/`total_deductions`/`taxable_income`/`federal_tax`/`provincial_state_tax`/
  `total_tax`/`tax_owing:NUMERIC(12,2)` N each
- `effective_rate`/`marginal_rate:NUMERIC(5,4)` N each
- `currency:VARCHAR(3)` N `('CAD')`, `calculation_input_hash:VARCHAR(64)` N
- UNIQUE `(tax_profile_id)` — this is literally the computed-tax-numbers cache the owner's
  decision explicitly forbids shipping

---

## 3. Collisions

Exactly **one** exact table-name collision across the 10 databases: **`shedlock`**.

- `services/model-manager/src/main/resources/db/migration/V7__create_shedlock.sql`
- `services/notification-service/src/main/resources/db/migration/V5__create_shedlock.sql`

Both are **byte-for-byte identical** DDL: `name VARCHAR(64) PK`, `lock_until TIMESTAMP
WITH TIME ZONE NOT NULL`, `locked_at TIMESTAMP WITH TIME ZONE NOT NULL`, `locked_by
VARCHAR(255) NOT NULL`. This is the standard `net.javacrumbs.shedlock` schema — safe to
collapse to one table. Checked for lock-name collisions between the two schedulers that
use it:
`services/model-manager/src/main/java/finance/trove/modelmanager/insight/WeeklyInsightScheduler.java:21`
(`name = "weekly-insight"`),
`services/notification-service/src/main/java/finance/trove/notification/digest/WeeklyDigestScheduler.java:46`
(`name = "weekly-digest"`), and
`services/notification-service/src/main/java/finance/trove/notification/webhook/WebhookRetryScheduler.java:20`
(`name = "webhook-retry"`) — all three names are distinct, so a single shared `shedlock`
table is safe with no renaming needed.

No other table name is reused across services. (`audit_log` in api-gateway and
`ai_audit_log` in model-manager are conceptually similar but not a name collision — see
the index-name collision note under model-manager in §2, which *would* bite if both
tables land in one schema without renaming their indexes.)

---

## 4. User-id columns

Every one of the following is `UUID`, and **none of them is a foreign key to anything** —
there is no `users` table anywhere in the 10 services. Confirmed by repo-wide search:
no `CREATE TABLE users`, no JPA `@Entity` class named `User`. Identity is Authentik-only:
`services/api-gateway/src/main/java/finance/trove/gateway/auth/AuthentikUserInfo.java`
holds the OIDC `sub` claim (Authentik's own UUID), and
`services/api-gateway/src/main/java/finance/trove/gateway/auth/UserClaims.java` carries
`UUID userId` forward into the JWT/ValKey refresh-token store — never into Postgres.
Every `user_id` column below is just "whatever UUID the gateway put in the JWT," trusted
by every downstream service. This is the central §9 finding: the monolith needs one real
`users` table these can all point at.

| Table (service) | Column | Nullable | Indexed |
|---|---|---|---|
| `audit_log` (gateway) | `user_id` | yes | `idx_audit_log_user_id` |
| `user_preferences` (gateway) | `user_id` | PK | yes (PK, + redundant explicit index) |
| `budget_period` (budget) | `user_id` | no | yes (leading col, compound) |
| `envelope` (budget) | `user_id` | no | yes (`idx_envelope_user_id`, `idx_envelope_category`) |
| `savings_goal` (budget) | `user_id` | no | yes |
| `net_worth_snapshot` (budget) | `user_id` | no | yes (leading col, compound) |
| `asset` (budget) | `user_id` | no | yes |
| `liability` (budget) | `user_id` | no | yes |
| `category_override` (categorisation) | `user_id` | no | leading col of UNIQUE only, no standalone index |
| `connected_account` (ingestion) | `user_id` | no | yes (2 indexes) |
| `transaction` (ingestion) | `user_id` | no | yes |
| `sync_log` (ingestion) | `user_id` | no | **no** — only `account_id` is indexed |
| `ai_audit_log` (model-manager) | `user_id` | yes | yes |
| `coach_conversation` (model-manager) | `user_id` | no | yes (2 indexes) |
| `coach_insight` (model-manager) | `user_id` | no | yes (partial) |
| `notification_preference` (notification) | `user_id` | no | yes (UNIQUE + explicit index) |
| `notification_log` (notification) | `user_id` | no | yes |
| `in_app_notification` (notification) | `user_id` | no | yes (2 partial indexes) |
| `webhook_subscription` (notification) | `user_id` | no | yes |
| `receipt` (receipt-processor) | `user_id` | no | yes (2 indexes, 1 partial) |
| `report` (report) | `user_id` | no | yes |
| `detected_subscription` (subscription) | `user_id` | no | yes (3 indexes) |
| `subscription_alert` (subscription) | `user_id` | no | yes |
| ~~`tax_profile`~~ / ~~`tax_deduction`~~ / ~~`tax_slip`~~ / ~~`rrsp_tfsa_fhsa_room`~~ / ~~`tax_calculation_cache`~~ | `user_id` | — | — | all DROPPED (§8) |

21 surviving tables carry a `user_id`. In the collapsed schema every one of these becomes
`user_id UUID NOT NULL REFERENCES users(id)`, and `sync_log` should pick up a standalone
index it never had.

---

## 5. Cross-service logical references (today unenforceable; become real FKs post-collapse)

These are the joins the system already performs in application code across a network
call or in-memory join, with no database-level integrity today:

| Column | Points at | Today | Post-collapse |
|---|---|---|---|
| `envelope.category_id` (budget) | `category.id` (categorisation) | UUID, no FK | real FK |
| `transaction.category_id` (ingestion) | `category.id` (categorisation) | UUID, no FK | real FK |
| `detected_subscription.category_id` (subscription) | `category.id` (categorisation) | UUID, no FK | real FK |
| `category_override.transaction_id` (categorisation) | `transaction.id` (ingestion) | UUID, no FK | real FK |
| `asset.account_id` (budget) | `connected_account.id` (ingestion) | UUID, nullable, no FK | real FK |
| `liability.account_id` (budget) | `connected_account.id` (ingestion) | UUID, nullable, no FK | real FK |
| `receipt.matched_transaction_id` (receipt-processor) | `transaction.id` (ingestion) | UUID, nullable, no FK | real FK |
| `detected_subscription.transaction_ids[]` (subscription) | `transaction.id` (ingestion), many | `UUID[]`, no FK | **still can't be a native FK** — Postgres has no array-FK; recommend normalizing into a join table `subscription_transaction(subscription_id, transaction_id)` with two real FKs (§10) so it finally gets referential integrity, or leave as an array with an app-level check if the normalization isn't worth the migration risk |
| `coach_conversation.session_id` (model-manager) | *(nothing)* | UUID, nullable | **no FK possible even post-collapse** — refresh-token sessions live only in ValKey (`RefreshTokenStore`, per `UserClaims.java`'s javadoc); either persist a `sessions` table (§9) and point this at it, or accept it stays a bare UUID correlation id |

Ones that are already real (same-DB) FKs today and stay that way: `envelope.period_id`,
`merchant_category_map.category_id`, `category.parent_id`, `webhook_delivery_log.subscription_id`,
`line_item.receipt_id`, `subscription_alert.subscription_id`, `tax_deduction.tax_profile_id` /
`tax_slip.tax_profile_id` / `tax_calculation_cache.tax_profile_id` (all three moot — dropped).

---

## 6. Money and currency

**Precision/scale is inconsistent across three different values**, none reconciled:

| Precision | Used by |
|---|---|
| `DECIMAL(19,4)` | `transaction.amount` only (ingestion) |
| `NUMERIC(12,2)` | everywhere else that holds a monetary amount: `budget_period.total_income`, `envelope.{allocated,spent,rollover_amount}`, `savings_goal.{target_amount,current_amount}`, `net_worth_snapshot.{total_assets,total_liabilities,net_worth}`, `asset.value`, `liability.balance`, `receipt.{extracted_amount,gst_amount,pst_amount,hst_amount}`, `detected_subscription.{normalized_amount,previous_amount}`, and the (dropped) tax-engine tables |
| `NUMERIC(10,2)` | `line_item.{unit_price,total_price}` only (receipt-processor) |

`transaction.amount` is the only column with sub-cent precision (4 decimal places —
plausibly there for FX/crypto headroom), but every aggregate downstream of it
(`envelope.spent`, `budget_period.total_income`, `net_worth_snapshot`'s generated column)
only carries 2 decimals. A transaction posted with a fractional-cent amount will silently
round when it flows into any budget/net-worth aggregate. Recommend standardizing the
collapsed schema on one scale — `NUMERIC(19,4)` everywhere money is stored is the safer
direction (widen the 2-decimal columns, not narrow the 4-decimal one) since no production
data exists to conflict.

**Currency column is present almost everywhere** (`VARCHAR(3)`), always `DEFAULT 'CAD'`,
with two exceptions:
- `line_item` has **no** currency column at all (inherits the parent `receipt`'s).
- `receipt.extracted_currency` is the **only** currency column anywhere that is nullable
  with **no default** — every other currency column defaults to `'CAD'`.

Given the owner's decision that currency is a per-user setting chosen at onboarding (CAD
or USD, no longer just CAD), **the blanket `DEFAULT 'CAD'` on ~15 columns is now wrong**
for any USD user whose application code ever relies on the column default rather than
passing currency explicitly. This surfaced concretely in
`services/report-service/src/main/java/finance/trove/report/generation/CsvReportBuilder.java:66-73`,
which hardcodes the **literal string `"CAD"`** into every CSV row regardless of the
source transaction's actual currency — a code bug, not a schema bug, but the schema's
own blanket default primed exactly this class of mistake. Recommend: drop the
`DEFAULT 'CAD'` in the collapsed schema (force every insert to pass currency explicitly,
sourced from `users.currency`), and fix `CsvReportBuilder` to use `tx.currency()`.

Also in that same file: the `TAX_EXPORT` CSV computes `gst_amount = amount * 5%` in Java
(`GST_RATE = new BigDecimal("0.05")`, `CsvReportBuilder.java:29,66`) — this **is** a
computed tax number, which the owner's decision explicitly rules out
("no computed tax numbers"). Needs to be replaced with a plain categorised-transaction
export before this ships (see §11).

---

## 7. Time columns

Overwhelmingly consistent: every `created_at`/`updated_at`/event-timestamp column across
all 34 migrations is `TIMESTAMPTZ` (or, in the two `shedlock` migrations, the equivalent
spelled-out `TIMESTAMP WITH TIME ZONE` — same type, cosmetic difference only, no bug).
**No plain (non-tz) `TIMESTAMP` column exists anywhere in the schema** — this is a
genuine strength to preserve, not a defect to fix.

`DATE` columns (calendar dates, correctly typed, no time-of-day or zone semantics
needed): `budget_period.month`, `savings_goal.deadline`, `net_worth_snapshot.snapshot_date`,
`transaction.date`, `detected_subscription.{next_charge_date,first_seen_date,last_seen_date}`,
`report.{period_start,period_end}`, `receipt.extracted_date`. `tax_profile.tax_year` /
`tax_slip.tax_year` / `rrsp_tfsa_fhsa_room.tax_year` are `INTEGER`, not `DATE` — correct
choice for a bare year (moot, dropped).

One deliberate two-timestamp pattern worth carrying forward as-is:
`transaction.date:DATE` (the transaction's calendar date, user-facing) vs
`transaction.posted_at:TIMESTAMPTZ` (nullable — the bank's posting instant, when known).
These are different concepts and both should survive the collapse unchanged.

---

## 8. Drop / keep

**DROP entirely** (whole tables, per the owner's decisions):
- `tax_profile`, `tax_deduction`, `tax_slip`, `rrsp_tfsa_fhsa_room`, `tax_calculation_cache`
  (tax-engine, 5 tables) — the calculators and `Sa100PdfExporter` these fed are cut; there
  is nothing left to write or read this data. `tax_calculation_cache` in particular stores
  exactly the "computed tax numbers" the replacement CSV export is defined to avoid.
- `model_registration` (model-manager) — the hardware/model registry (aliases, providers,
  health-check state, context windows) is replaced by one user-configured
  OpenAI-compatible endpoint; nothing in the collapsed design has multiple aliases to
  register.

**KEEP, unmodified in shape:** `category`, `merchant_category_map`, `category_override`
(categorisation); `connected_account`, `transaction`, `sync_log` (ingestion — simplefin/ofx
become packages, the tables they write to don't change); `budget_period`, `envelope`,
`savings_goal`, `net_worth_snapshot`, `asset`, `liability` (budget); `notification_preference`,
`notification_log`, `in_app_notification`, `webhook_subscription`, `webhook_delivery_log`
(notification — nothing in the decisions touches notifications); `detected_subscription`,
`subscription_alert` (subscription); `line_item` (receipt-processor); `rate_limit_config`,
`audit_log` (gateway — both explicitly in scope: "rate limits... security headers are in
scope").

**KEEP, with a semantic change (columns survive, meaning shifts):**
- `receipt` — keep every column; `image_key`/`thumbnail_key` stop being MinIO object keys
  and become local-disk paths (still `TEXT`, no schema change needed); the `EMAIL` value
  in the `source` CHECK becomes unreachable dead enum (IMAP ingestion cut) but costs
  nothing to leave in the CHECK — or drop it for cleanliness, either is fine since nothing
  reads it.
- `coach_conversation`, `coach_insight` — keep as-is (chat history the client reads); the
  dangling `session_id` (no FK target, see §5) either gets a real `sessions` table to
  point at or stays an unenforced correlation id.
- `ai_audit_log` — keep, low priority; rename its indexes before merging into one schema
  to avoid the name collision with gateway's `audit_log` noted in §2/§3. Consider folding
  into the general `audit_log` table instead of keeping two audit logs (optional
  simplification, not required).
- `user_preferences` — **do not keep as a separate table.** Its only field with ongoing
  meaning is `onboarding_completed` — fold that into `users` (§9/§10). `deployment`
  ("cloud"/"self-hosted"/"local", per the entity's own javadoc,
  `services/api-gateway/src/main/java/finance/trove/gateway/preferences/UserPreferences.java:20`)
  is vestigial once there is exactly one deployment shape (self-hosted monolith, public
  internet). `ai_mode` ("sonnet"/"haiku"/"local-llm") is superseded by the new
  `ai_settings` table's `enabled` flag and configured model id.
- `report` — **keep, and reconsider the earlier "report artefacts" drop
  suggestion.** On inspection this table is not legacy cruft to remove: `ReportType.TAX_EXPORT`
  plus `CsvReportBuilder`'s `TAX_EXPORT` column layout
  (`date, merchant, description, amount, currency, gst_amount, category,
  deductible_reason`) is already, almost exactly, the "tax-time CSV/PDF export of
  categorised transactions" the owner's decision describes. Keep the table, keep
  `report_type ∈ {MONTHLY_SUMMARY, ANNUAL_SUMMARY, TAX_EXPORT, CUSTOM}` and
  `format ∈ {CSV, PDF}` as-is, just fix the two bugs in §6/§11 (hardcoded `"CAD"`,
  computed `gst_amount`) and repurpose `file_key` as a local path. This also means the
  task's suggested new `tax_export_runs` table (§9) is very likely redundant with this
  existing table — see the note there.

---

## 9. New tables the monolith needs

- **`users`** — the table that doesn't exist today. `id UUID PK`, `email VARCHAR(320)
  UNIQUE NOT NULL`, `display_name VARCHAR(200)`, `currency VARCHAR(3) NOT NULL` (CAD/USD,
  chosen at onboarding — this is the per-user setting the owner's decision calls for, and
  it's the value every other table's currency default should have come from), `is_active
  BOOLEAN NOT NULL DEFAULT true`, `onboarding_completed BOOLEAN NOT NULL DEFAULT false`
  (folded in from the old `user_preferences`), `created_at`/`updated_at TIMESTAMPTZ NOT NULL`.
- **`credentials`** — optional 1:1 with `users` (a user may be OIDC-only and have zero
  rows here). `user_id UUID PK REFERENCES users(id)`, `password_hash TEXT NOT NULL`
  (argon2id), `updated_at TIMESTAMPTZ NOT NULL`.
- **`totp_credential`** — `user_id UUID PK REFERENCES users(id)`, `secret_encrypted TEXT
  NOT NULL`, `enabled BOOLEAN NOT NULL DEFAULT false`, `recovery_codes_hashed TEXT[]`,
  `created_at TIMESTAMPTZ NOT NULL`.
- **`oidc_link`** — `id UUID PK`, `user_id UUID NOT NULL REFERENCES users(id)`,
  `provider VARCHAR(50) NOT NULL`, `provider_sub VARCHAR(255) NOT NULL`,
  `linked_at TIMESTAMPTZ NOT NULL`, `UNIQUE (provider, provider_sub)` — supports the
  "optional OIDC" half of the decision without requiring Authentik.
- **`refresh_token`** (the "sessions" table) — `id UUID PK`, `user_id UUID NOT NULL
  REFERENCES users(id)`, `token_hash VARCHAR(128) NOT NULL`, `issued_at TIMESTAMPTZ NOT
  NULL`, `expires_at TIMESTAMPTZ NOT NULL`, `revoked_at TIMESTAMPTZ`, `ip_address
  VARCHAR(45)`, `user_agent VARCHAR(500)`. Persisting this (rather than ValKey-only, as
  today) is what lets `coach_conversation.session_id` (§5) finally become a real FK, and
  survives a ValKey restart. If ValKey is kept as a fast path that's fine too — this table
  can be the durable backing store either way.
- **`ai_settings`** — `user_id UUID PK REFERENCES users(id)`, `endpoint_url TEXT`,
  `api_key_encrypted TEXT`, `chat_model_id VARCHAR(200)`, `vision_model_id VARCHAR(200)`,
  `enabled BOOLEAN NOT NULL DEFAULT false`, `created_at`/`updated_at TIMESTAMPTZ NOT
  NULL`. Replaces `model_registration` (§8) and the dead `ai_mode` field: one
  user-configured OpenAI-compatible endpoint, off until configured, exactly per the
  decision — the vision model id doubles as the receipt-OCR endpoint since the decision
  says OCR reuses the same vision-capable endpoint.
- **`tax_export_runs`** — requested by this task, but **verify before building it**: given
  §8's finding that `report`/`ReportType.TAX_EXPORT`/`CsvReportBuilder` already implement
  this exact feature, the pragmatic move is very likely to keep using `report` (rename to
  `export_run` if a more general name is wanted) rather than stand up a parallel table
  that does the same job. Only add a dedicated table if the tax-time export needs fields
  `report` doesn't have (e.g. `tax_year INTEGER`, `jurisdiction VARCHAR(2)`) that don't
  fit naturally into `requested_filters JSONB`, which they do fit into.
- **`receipt_images`** — requested by this task, but similarly **check against existing
  columns first**: `receipt.image_key`/`receipt.thumbnail_key` already hold one
  original + one thumbnail path per receipt. A dedicated `receipt_images(id, receipt_id
  FK, file_path, kind, created_at)` table is only worth adding if the collapsed product
  wants multiple photos per receipt (e.g. multi-page receipts); if one image is enough,
  reusing the two existing columns (repointed at local disk, §8) is simpler and loses
  nothing.
- **`audit_log`** — already exists (gateway, §2); no new table needed, just carry it
  forward as the shared security audit log.

---

## 10. Proposed single-schema migration list (V1..Vn)

One Postgres schema, one Flyway history. Ordered so every `REFERENCES` target exists
before the table that uses it. "Derives from" cites the source file(s); "changes" lists
renames/new-FKs/dropped-defaults relative to that source. No production data exists, so
this is a clean rewrite, not an ALTER chain.

| # | Table | Derives from | Key changes vs. source |
|---|---|---|---|
| V1 | *(extension)* `pgcrypto` | (repeated across V1s) | one `CREATE EXTENSION IF NOT EXISTS "pgcrypto"` instead of 6 |
| V2 | `users` | *new* (§9) | — |
| V3 | `credentials` | *new* (§9) | FK → `users` |
| V4 | `totp_credential` | *new* (§9) | FK → `users` |
| V5 | `oidc_link` | *new* (§9) | FK → `users` |
| V6 | `refresh_token` | *new* (§9), supersedes ValKey-only sessions | FK → `users` |
| V7 | `category` | categorisation V1 | unchanged (self-FK stays) |
| V8 | `merchant_category_map` | categorisation V2 | unchanged |
| V9 | `connected_account` | ingestion V1 | `user_id` FK → `users` (new) |
| V10 | `transaction` | ingestion V2 | **rename `source_id` → `provider_transaction_id`** (§6.f/O7 — same partial UNIQUE `(account_id, provider_transaction_id) WHERE provider_transaction_id IS NOT NULL`, index renamed to match); `tags_json TEXT` → `tags JSONB` (align with `receipt.extracted_line_items`/`report.requested_filters`); `user_id`/`category_id` become real FKs; **drop `DEFAULT 'CAD'`** on `currency` (§6) |
| V11 | `sync_log` | ingestion V3 + V4 merged | `user_id` FK → `users`; **add** `idx_sync_log_user_id` (missing today, §4) |
| V12 | `category_override` | categorisation V3 | `transaction_id` becomes real FK → `transaction`; `user_id` FK → `users` |
| V13 | `budget_period` | budget V1 | `user_id` FK → `users`; drop `DEFAULT 'CAD'` |
| V14 | `envelope` | budget V2 | `category_id` becomes real FK → `category`; `user_id` FK → `users`; drop `DEFAULT 'CAD'` |
| V15 | `savings_goal` | budget V3 | `user_id` FK; drop `DEFAULT 'CAD'` |
| V16 | `net_worth_snapshot` | budget V4 | `user_id` FK; drop `DEFAULT 'CAD'`; generated column unchanged |
| V17 | `asset` | budget V5 | `user_id` FK; `account_id` becomes real FK → `connected_account`; drop `DEFAULT 'CAD'` |
| V18 | `liability` | budget V6 | same as `asset` |
| V19 | `receipt` | receipt-processor V1 + V3 merged | `user_id` FK; `matched_transaction_id` becomes real FK → `transaction`; `image_key`/`thumbnail_key` repurposed as local paths (no type change); drop `DEFAULT 'CAD'` is moot (never had one) |
| V20 | `line_item` | receipt-processor V2 | unchanged (FK already real) |
| V21 | `detected_subscription` | subscription V1 | `user_id` FK; `category_id` becomes real FK; drop `DEFAULT 'CAD'`; **optionally** replace `transaction_ids UUID[]` with a join table `subscription_transaction(subscription_id FK, transaction_id FK, PRIMARY KEY(subscription_id, transaction_id))` at V21a for real referential integrity (§5) |
| V22 | `subscription_alert` | subscription V2 | `user_id` FK; `subscription_id` FK unchanged |
| V23 | `notification_preference` | notification V1 | `user_id` FK |
| V24 | `notification_log` | notification V2 | `user_id` FK |
| V25 | `in_app_notification` | notification V3 | `user_id` FK |
| V26 | `webhook_subscription` | notification V4 (first half) | `user_id` FK |
| V27 | `webhook_delivery_log` | notification V4 (second half) | `subscription_id` FK unchanged |
| V28 | `ai_settings` | *new* (§9) | `user_id` FK, replaces `model_registration` + `user_preferences.ai_mode` |
| V29 | `coach_conversation` | model-manager V3+V4+V5 merged (final shape only — collapse the role-case fix and column churn into one CREATE) | `user_id` FK; `session_id` becomes real FK → `refresh_token` (or drop the FK and keep it a bare correlation id if sessions are meant to be ephemeral/ValKey-only) |
| V30 | `coach_insight` | model-manager V6 | `user_id` FK |
| V31 | `ai_audit_log` | model-manager V2 | `user_id` FK (nullable, since some calls may be unauthenticated system jobs); **rename indexes** to avoid colliding with `audit_log`'s (§2/§3) |
| V32 | `report` | report-service V1 | `user_id` FK; `file_key` repurposed as local path; **fix in application code, not schema:** `CsvReportBuilder` must stop hardcoding `"CAD"` and stop computing `gst_amount` (§6/§11) |
| V33 | `rate_limit_config` | gateway V2 + V4 merged (final seed values only) | unchanged |
| V34 | `audit_log` | gateway V1 + V3 merged (final `ip_address VARCHAR(45)` shape) | `user_id` FK (nullable — some entries predate auth, e.g. failed login attempts) |
| V35 | `shedlock` | model-manager V7 / notification V5 (identical, take either) | one copy (§3) |
| — | `user_preferences` | gateway V5 | **retired** — `onboarding_completed` merged into `users` (V2); `deployment`/`ai_mode` dropped (§8) |
| — | `model_registration` | model-manager V1 | **dropped**, replaced by `ai_settings` (§8/§9) |
| — | `tax_profile`, `tax_deduction`, `tax_slip`, `rrsp_tfsa_fhsa_room`, `tax_calculation_cache` | tax-engine V1-V5 | **dropped entirely** (§8) |
| — | `tax_export_runs`, `receipt_images` | *requested by this task* | **not created** — `report` and `receipt.image_key`/`thumbnail_key` already cover the need; revisit only if a concrete gap shows up (§9) |

---

## 11. Risks

1. **The currency-hardcoding bug in `CsvReportBuilder` is a correctness defect that
   directly contradicts the owner's decisions**, not just a schema cleanliness issue: it
   ignores `tx.currency()` and writes the literal `"CAD"` into every exported row
   (`services/report-service/src/main/java/finance/trove/report/generation/CsvReportBuilder.java:66,71,73`),
   and its `TAX_EXPORT` branch computes a GST amount in code
   (`CsvReportBuilder.java:29,66`) — the exact "computed tax number" the decision says
   the replacement export must not produce. Both need an application-code fix as part of
   the collapse, not just a table carry-forward; flagging so it isn't missed because the
   *table* looks fine.
2. **Blanket `DEFAULT 'CAD'` on ~15 money-adjacent columns** silently miscategorizes any
   USD user's data the moment application code relies on the column default instead of
   passing currency explicitly from `users.currency`. Recommend dropping every such
   default in the collapsed schema so a missing currency fails loudly (NOT NULL, no
   default) instead of silently defaulting to the wrong one.
3. **Three different money precisions in one system** (`DECIMAL(19,4)` on `transaction`,
   `NUMERIC(12,2)` almost everywhere, `NUMERIC(10,2)` on `line_item`) risk silent rounding
   when a sub-cent transaction amount flows into a 2-decimal aggregate (`envelope.spent`,
   `net_worth_snapshot`). No production data exists to force a hard choice now — pick one
   scale (recommend widening everything to match `transaction`'s `NUMERIC(19,4)`) before
   any real money moves through the collapsed system.
4. **`coach_conversation.session_id` has never had a real target** (sessions are
   ValKey-only) — deciding whether the monolith persists sessions in Postgres (§9's
   `refresh_token`) or keeps them ephemeral needs to happen before V29/V6 are written, or
   this column either gets a home or should be dropped rather than carried forward as a
   permanently-dangling FK-shaped column.
5. **Index name collisions** if tables are merged into one schema without renaming:
   `ai_audit_log` and `audit_log` both define `idx_audit_log_user_id` /
   `idx_audit_log_created_at` (Postgres index names are unique per-schema); the mechanical
   collapse script needs to rename one set, not just concatenate the DDL.
6. **`subscription-detector`'s `transaction_ids UUID[]`** can never be a native FK even
   post-collapse; either accept it stays unenforced or spend the migration budget on the
   join-table normalization sketched in V21a. Low urgency (no production data, feature
   still works either way), but worth a deliberate decision rather than defaulting to
   "leave it" by omission.
7. **`sourceId`/`provider_transaction_id` rename (§6.f, O7) touches four normalizer
   classes plus `DeduplicationService` plus ADR-0009**, not just the migration — the
   database and the `UNIQUE (account_id, source_id) WHERE source_id IS NOT NULL` index
   were correct all along per O7's finding; this is a Java-layer rename to match the DB's
   (and the provider's) always-correct semantics, and the collapse is a natural point to
   do it, but it is not a database bug to "fix" in the schema — the schema doesn't change
   shape, only the column's name.
8. **`infra/full-stack/postgres-init/01-create-databases.sql` is missing `trove_receipt`**
   from its `CREATE DATABASE` list (confirmed by diff against `docker/postgres-init.sql`,
   which has all 11). Moot the moment the collapse lands (one DB, not eleven), but if
   anyone stands up `infra/full-stack/` before the collapse ships, receipt-processor's
   Flyway will fail with "database does not exist."
9. **This report's `report`/`ai_settings`/`refresh_token` recommendations assume the
   owner is fine with *not* building the literal `tax_export_runs` and `receipt_images`
   tables the task described**, on the grounds that verified source
   (`ReportType`/`CsvReportBuilder`, and `receipt.image_key`/`thumbnail_key`) already
   covers the need. Flagging explicitly as a place this report diverges from the literal
   task wording, with the source citations to check the reasoning against.
