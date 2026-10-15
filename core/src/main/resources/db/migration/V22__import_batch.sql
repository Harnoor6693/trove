-- Collapse step 6 (docs/recovery/06-COLLAPSE-PLAN.md §3 V20-row;
-- docs/recovery/07-READINESS-GAPS.md P2-12).
--
-- The table and the column, not the endpoints. `POST /v1/imports`, its preview, its commit and
-- its undo are step 7's; this step creates what they will write to, because `transaction`
-- (V19) needs the `import_batch_id` column and could not carry a forward FK to a table that did
-- not exist yet.
--
-- The ALTER at the bottom is that FK. It is deferred to this migration for the same reason §3's
-- V27 row defers `transaction.receipt_id`: Flyway runs forward-only and in order, so a
-- circular dependency between two CREATE TABLEs is resolved by adding the second constraint
-- afterwards rather than by reordering either table.
--
-- ON DELETE SET NULL, not CASCADE: deleting an import batch is an administrative act about the
-- IMPORT, and it must never take a household's transactions with it. Undoing an import removes
-- the rows it created deliberately and one at a time (step 7), which is a different operation
-- with a different safety rule — it leaves any row a member has since edited alone.
--
-- `source_kind` is CSV or OFX only: SIMPLEFIN is fetched over the network and MANUAL is typed in,
-- so neither has a file to batch.

CREATE TABLE import_batch (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id UUID        NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    account_id   UUID        NOT NULL REFERENCES connected_account(id) ON DELETE CASCADE,
    source_kind  VARCHAR(20) NOT NULL CHECK (source_kind IN ('CSV', 'OFX')),
    mapping_json JSONB       NULL,
    row_count    INT         NOT NULL DEFAULT 0,
    new_count    INT         NOT NULL DEFAULT 0,
    dup_count    INT         NOT NULL DEFAULT 0,
    status       VARCHAR(20) NOT NULL
                 CHECK (status IN ('PREVIEW', 'COMMITTED', 'UNDONE')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX import_batch_household_id_idx ON import_batch (household_id);
CREATE INDEX import_batch_account_id_idx   ON import_batch (account_id);

ALTER TABLE transaction
    ADD CONSTRAINT transaction_import_batch_id_fkey
    FOREIGN KEY (import_batch_id) REFERENCES import_batch(id) ON DELETE SET NULL;
