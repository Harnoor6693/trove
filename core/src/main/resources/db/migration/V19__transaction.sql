-- Collapse step 6 (docs/recovery/06-COLLAPSE-PLAN.md §3 V17-row, §2.2, §4.4, §6 M0/D1/D4;
-- docs/recovery/07-READINESS-GAPS.md P2-12, P2-14, P2-15).
--
-- WHAT CHANGES FROM ingestion-service V2__transaction.sql, and why:
--   * `source_id` -> `provider_transaction_id`. The rename is the visible half of finding O7/M0:
--     every normaliser put the PLUGIN NAME literal into RawTransaction.sourceId and the real
--     per-transaction id into a slot nothing read, so with the unique index below in place at
--     most ONE transaction per account could ever exist. The column now says what it holds. The
--     partial UNIQUE (account_id, provider_transaction_id) WHERE NOT NULL is kept — it is the
--     exact-match dedup key.
--   * `type` CHECK WIDENS from ('DEBIT','CREDIT') to all seven values of
--     finance.trove.common.plugin.TransactionType. The shared enum always carried seven; the
--     narrow CHECK would reject a legitimate FEE, INTEREST or REFUND from any source, and D1's
--     budget aggregate is written against the full set (it nets a REFUND against DEBIT spend).
--   * `tags_json TEXT` -> `tags JSONB`. A text column holding JSON is a string that only looks
--     like data; JSONB is checked on write and indexable if a later step needs it.
--   * `category_id` gains a real FK to `category` ON DELETE SET NULL. It had none, which is how
--     the column came to hold ids of rows that no longer existed.
--   * `manual BOOLEAN NOT NULL DEFAULT false` (gap P2-13): a hand-entered row a sync must never
--     overwrite and an import undo must never remove. It is also the only kind of row
--     DELETE /v1/transactions/{id} will remove.
--   * `transfer_group_id UUID NULL` + index (gap P2-14): both legs of an internal transfer carry
--     the same value, so every spend and income aggregate can exclude them instead of counting a
--     credit-card payment as both spending and income.
--   * `import_batch_id UUID NULL` (gap P2-12). The FK CANNOT be declared here — `import_batch`
--     does not exist until V22 — so V22 adds it with an ALTER, the same way §3's V27 row adds
--     `transaction.receipt_id`.
--   * `household_id` NOT NULL FK is the scope (§1.5); `user_id` means created-by, nullable with
--     ON DELETE SET NULL so removing a member does not delete the household's ledger.
--   * `amount NUMERIC(19,4)` (was DECIMAL(19,4) — the same type, spelled the way §9 risk 6 and
--     SchemaDefaultsIT state it) and `currency VARCHAR(3) NOT NULL` with NO DEFAULT. The old
--     DEFAULT 'CAD' is half of finding F4: a row whose currency was never set became a Canadian
--     dollar amount silently. Currency comes from the source row or the account.
--
-- THE TRIGRAM INDEX. Gap P2-15 gives GET /v1/transactions a `q` search over merchant,
-- description and notes. The GIN index below is over exactly the expression the query uses, so
-- an ILIKE '%…%' can be served by the index instead of by a sequential scan of the whole ledger.
-- pg_trgm is created by V1__extensions.sql, so this needs no pom change and no configuration.
-- The expression coalesces because a NULL anywhere in a concatenation makes the whole string
-- NULL, which would silently drop every row with no notes from the search.

CREATE TABLE transaction (
    id                      UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id            UUID          NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    account_id              UUID          NOT NULL
                            REFERENCES connected_account(id) ON DELETE CASCADE,
    user_id                 UUID          NULL REFERENCES users(id) ON DELETE SET NULL,
    provider_transaction_id VARCHAR(255)  NULL,
    amount                  NUMERIC(19,4) NOT NULL,
    currency                VARCHAR(3)    NOT NULL,
    type                    VARCHAR(10)   NOT NULL
                            CHECK (type IN ('DEBIT', 'CREDIT', 'TRANSFER', 'FEE', 'INTEREST',
                                            'REFUND', 'OTHER')),
    description             TEXT          NULL,
    merchant_name           VARCHAR(255)  NULL,
    date                    DATE          NOT NULL,
    posted_at               TIMESTAMPTZ   NULL,
    pending                 BOOLEAN       NOT NULL DEFAULT false,
    manual                  BOOLEAN       NOT NULL DEFAULT false,
    category_id             UUID          NULL REFERENCES category(id) ON DELETE SET NULL,
    transfer_group_id       UUID          NULL,
    import_batch_id         UUID          NULL,
    tags                    JSONB         NULL,
    notes                   TEXT          NULL,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX transaction_household_id_idx     ON transaction (household_id);
CREATE INDEX transaction_household_date_idx   ON transaction (household_id, date DESC);
CREATE INDEX transaction_account_id_idx       ON transaction (account_id);
CREATE INDEX transaction_user_id_idx          ON transaction (user_id);
CREATE INDEX transaction_category_id_idx      ON transaction (category_id);
CREATE INDEX transaction_transfer_group_idx   ON transaction (transfer_group_id);
CREATE INDEX transaction_import_batch_id_idx  ON transaction (import_batch_id);

CREATE UNIQUE INDEX transaction_provider_id_unique
    ON transaction (account_id, provider_transaction_id)
    WHERE provider_transaction_id IS NOT NULL;

CREATE INDEX transaction_search_trgm_idx ON transaction
    USING GIN ((coalesce(merchant_name, '') || ' ' || coalesce(description, '') || ' '
                || coalesce(notes, '')) gin_trgm_ops);
