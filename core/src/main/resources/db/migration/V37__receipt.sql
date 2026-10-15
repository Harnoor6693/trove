-- Collapse step 14 (docs/recovery/06-COLLAPSE-PLAN.md §3 V27-V29, §2.6, §4.10, §6 "Receipts").
--
-- Re-based V-numbers: §3 prints these three tables as V27, V28 and V29 under the plan's ORIGINAL
-- numbering. Only their columns and constraints come from those rows; the number is the next free
-- one at landing time (§3's re-basing note). V10 (user_preferences, step 4) is REDUNDANT and stays
-- UNUSED — preferences live on `users` (V3), which is where this step reads `country`, `region`
-- and `currency` from, not from the table §2.6's prose still names.
--
-- THREE TABLES IN ONE FILE, the precedent step 11's V34, step 10's V29, step 6's V22 and step 3's
-- V9_1 already set: `receipt`, `receipt_image` and `line_item` are one design. A receipt without
-- its stored image is a row pointing at nothing, and a line item without its receipt is not a
-- thing at all — both are reached only through the receipt aggregate, never by a bare id, and
-- both cascade with it.
--
-- TENANCY (§1.5, §3's tenancy note). These are ORDINARY household ledger rows, the opposite of
-- what steps 11, 13 and 15 do: `household_id` is the SCOPE and `user_id` means "uploaded by".
-- A household mate can see, download and delete a receipt somebody else in the household
-- photographed — that is what a shared ledger is — while household B cannot reach it at all.
-- Getting this backwards would hide a member's own receipts from their own household.
--
-- CHANGED from the source (services/receipt-processor V1 + V3):
--   * `household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE` added; `user_id`
--     kept, gains its FK, and is now the uploader rather than the scope (§3's `user_id`
--     enumeration lists `receipt (owner)`).
--   * status CHECK loses BOTH `QUEUED` and `OCR`. `QUEUED` was assigned by no code path that ever
--     existed (inventory D7) and `OCR` left with PaddleOCR (§2.6). This is load-bearing rather
--     than tidy: `ReceiptPipelineOrchestrator` sets the status from `stage.stageName()`, so the
--     vision stage's name must be exactly `EXTRACTING`, an existing value, or every AI-path
--     receipt would fail this CHECK on its first write.
--   * `source` CHECK narrowed to ('UPLOAD','SCAN'). The source's `EMAIL` has no ingress path in
--     this design, and a value nothing can produce is not worth a constraint slot.
--   * `image_key` and `thumbnail_key` DROPPED — they become `receipt_image` rows below, so a
--     receipt can carry more than two files and each one can carry its own size and digest.
--   * `matched_transaction_id UUID NULL REFERENCES transaction(id) ON DELETE SET NULL` — a real
--     foreign key, which the source lacked because the transaction lived in another service's
--     database. V38 adds the other half, `transaction.receipt_id`. The two are kept consistent by
--     construction: exactly ONE service method (`ReceiptLinkService#link`) writes either column,
--     it writes both in one transaction, and nothing else in the tree writes either.
--   * every money column is NUMERIC(19,4), not the source's (12,2) — `extracted_amount`,
--     `gst_amount`, `pst_amount`, `hst_amount`. `SchemaDefaultsIT` scans information_schema across
--     the whole database and fails any numeric column that is not (19,4) unless it is named in its
--     NON_MONEY_NUMERICS map, so these four need no allowlist entry.
--   * `confidence` is NUMERIC(4,3), matching what step 5's `merchant_category_map.confidence`
--     already uses, not the source's (3,2). It is a score in [0.000, 1.000] and not an amount, so
--     it is the ONE entry this step adds to `SchemaDefaultsIT.NON_MONEY_NUMERICS`.
--   * `extracted_currency VARCHAR(3)` with NO default. The source defaulted it to 'CAD' (finding
--     D3), which is how a receipt containing nothing but a '$' became a Canadian one. The pipeline
--     sets it from the uploader's own `users.currency` unless an explicit ISO code is on the
--     receipt, and `SchemaDefaultsIT` fails any `%currency%` column that carries a default.
--   * indexes renamed with the table prefix (§3's global rule, which exists because the source
--     tree had four different `idx_*` names colliding in one catalog) and re-scoped to the
--     household.
--
-- KEPT from the source: `review_required`, `error_message`, `ocr_text`, `extracted_line_items`,
-- `upload_size_bytes` and both processing timestamps. `ocr_text` survives PaddleOCR's deletion
-- because the vision model returns text and the heuristic parser still runs over it.

CREATE TABLE receipt (
    id                       UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id             UUID          NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id                  UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status                   VARCHAR(15)   NOT NULL DEFAULT 'UPLOADED'
                             CHECK (status IN (
                               'UPLOADED','VALIDATING','PARSING','EXTRACTING',
                               'MATCHING','TAX_SPLIT','SCORING','COMPLETE','FAILED'
                             )),
    source                   VARCHAR(10)   NOT NULL DEFAULT 'UPLOAD'
                             CHECK (source IN ('UPLOAD','SCAN')),
    upload_size_bytes        BIGINT        NOT NULL DEFAULT 0,
    ocr_text                 TEXT,
    extracted_merchant       VARCHAR(200),
    extracted_amount         NUMERIC(19,4),
    extracted_currency       VARCHAR(3),
    extracted_date           DATE,
    extracted_line_items     JSONB,
    gst_amount               NUMERIC(19,4),
    pst_amount               NUMERIC(19,4),
    hst_amount               NUMERIC(19,4),
    confidence               NUMERIC(4,3),
    matched_transaction_id   UUID          REFERENCES transaction(id) ON DELETE SET NULL,
    review_required          BOOLEAN       NOT NULL DEFAULT false,
    error_message            VARCHAR(500),
    processing_started_at    TIMESTAMPTZ,
    processing_completed_at  TIMESTAMPTZ,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- The list route's only ordering: this household's receipts, newest first.
CREATE INDEX receipt_household_id_idx ON receipt (household_id, created_at DESC);

-- KEPT, and it is really queried: `ReceiptOrphanSweep` (§5 item 6) runs hourly across every
-- household looking for receipts stuck in a non-terminal status, which is exactly this predicate.
-- It is deliberately NOT household-scoped — the sweep is an install-wide job, not a request.
CREATE INDEX receipt_active_idx ON receipt (status)
    WHERE status NOT IN ('COMPLETE','FAILED');

-- The review queue, scoped to the household that reads it.
CREATE INDEX receipt_review_idx ON receipt (household_id, review_required)
    WHERE review_required = true;

CREATE INDEX receipt_user_id_idx ON receipt (user_id);
CREATE INDEX receipt_matched_transaction_id_idx ON receipt (matched_transaction_id);

-- `receipt_image` — NEW, owner-requested (§3 V28). It replaces the source's two `*_key` columns
-- on `receipt`.
--
-- `file_key` is a `LocalDiskStorage` key of the form `receipts/<householdId>/<uuid>.<ext>`. It is
-- NOT a MinIO object name (MinioConfig/MinioProperties/MinioStorageService are deleted, not
-- moved) and it is NOT an absolute path: `LocalDiskStorage` generates every key itself, refuses to
-- address anything outside its three areas, and re-checks the key against its root on the way back
-- in. Nothing derived from an uploaded filename ever reaches a path.
--
-- `sha256` is VARCHAR(64), not the §3 row's CHAR(64), and the deviation is deliberate: Postgres
-- `char(n)` is blank-padded, JPA schema validation reports it as `bpchar` and refuses a String
-- field mapped over it, and the merged tree already stores 64-character hex hashes as VARCHAR(64)
-- (V9_1's two `token_hash` columns). A digest is exactly 64 characters, so the fixed width buys
-- nothing that the length does not.
--
-- `page_no INT NOT NULL DEFAULT 0` rather than a nullable column, deliberately (§3's NULLS NOT
-- DISTINCT paragraph): a plain UNIQUE treats two NULLs as distinct, so a nullable `page_no` would
-- let a receipt collect any number of ORIGINAL rows. A non-null default sidesteps that entirely —
-- every ORIGINAL and THUMBNAIL row shares the value 0 and is caught by the constraint — without
-- needing NULLS NOT DISTINCT here at all.
CREATE TABLE receipt_image (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    receipt_id    UUID          NOT NULL REFERENCES receipt(id) ON DELETE CASCADE,
    kind          VARCHAR(9)    NOT NULL CHECK (kind IN ('ORIGINAL','THUMBNAIL','PAGE')),
    page_no       INT           NOT NULL DEFAULT 0,
    file_key      TEXT          NOT NULL,
    mime_type     VARCHAR(100)  NOT NULL,
    size_bytes    BIGINT        NOT NULL,
    sha256        VARCHAR(64),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT receipt_image_receipt_kind_page_key UNIQUE (receipt_id, kind, page_no)
);

CREATE INDEX receipt_image_receipt_id_idx ON receipt_image (receipt_id);

-- `line_item` — from the source V2, with NUMERIC(19,4) throughout (the source had (8,2), (10,2)
-- and (10,2), all three of which SchemaDefaultsIT would fail) and its index renamed with the
-- table prefix. `quantity` is money-shaped rather than money; it is (19,4) because the schema rule
-- is about the column type, not about what the number means, and an allowlist entry per receipt
-- line would be three exemptions bought for nothing.
CREATE TABLE line_item (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    receipt_id    UUID          NOT NULL REFERENCES receipt(id) ON DELETE CASCADE,
    description   TEXT          NOT NULL,
    quantity      NUMERIC(19,4) NOT NULL DEFAULT 1,
    unit_price    NUMERIC(19,4) NOT NULL DEFAULT 0,
    total_price   NUMERIC(19,4) NOT NULL DEFAULT 0,
    is_taxable    BOOLEAN       NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX line_item_receipt_id_idx ON line_item (receipt_id);
