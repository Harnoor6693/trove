-- Collapse step 6 (docs/recovery/06-COLLAPSE-PLAN.md §3 V18-row, §2.2, §6 M0;
-- docs/recovery/inventory/ingestion-service.md D4).
--
-- D4, in one column. The source declares `status VARCHAR(10)` and the orchestrator writes
-- 'PARTIAL_FAILURE' into it — fifteen characters into a ten-character column. Today that error
-- is survivable only because the whole sync is non-transactional and the exception is swallowed
-- at the end; the moment the batch became transactional it would roll back every ingested row
-- with it. The column is VARCHAR(20) here and carries a CHECK naming the four values the code is
-- allowed to write.
--
-- The values themselves are renamed with the column, not around it: the source writes 'OK' and
-- 'ERROR', and SyncOrchestrator's three setStatus call sites become
-- RUNNING / SUCCESS / PARTIAL_FAILURE / FAILED (§2.2). SyncCompletedEvent.Outcome spells the
-- failure case FAILURE rather than FAILED; that mismatch is deliberate and is MAPPED in
-- SyncOrchestrator. Neither name is changed to suit the other — the event is a published
-- contract other steps consume, and the column is what an operator reads in SQL.
--
-- `household_id` is the scope (§1.5) so a sync log is readable by every member of the household
-- that owns the account, and `user_id` records which member triggered the run.
-- `sync_log_user_id_idx` is the index §3's row names.

CREATE TABLE sync_log (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id         UUID        NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    account_id           UUID        NOT NULL REFERENCES connected_account(id) ON DELETE CASCADE,
    user_id              UUID        NULL REFERENCES users(id) ON DELETE SET NULL,
    source_kind          VARCHAR(20) NULL
                         CHECK (source_kind IN ('SIMPLEFIN', 'OFX', 'CSV', 'MANUAL')),
    status               VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
                         CHECK (status IN ('RUNNING', 'SUCCESS', 'PARTIAL_FAILURE', 'FAILED')),
    transactions_fetched INT         NOT NULL DEFAULT 0,
    transactions_new     INT         NOT NULL DEFAULT 0,
    transactions_updated INT         NOT NULL DEFAULT 0,
    transactions_skipped INT         NOT NULL DEFAULT 0,
    transactions_failed  INT         NOT NULL DEFAULT 0,
    error_message        TEXT        NULL,
    started_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at         TIMESTAMPTZ NULL
);

CREATE INDEX sync_log_account_id_idx   ON sync_log (account_id);
CREATE INDEX sync_log_household_id_idx ON sync_log (household_id);
CREATE INDEX sync_log_user_id_idx      ON sync_log (user_id);
CREATE INDEX sync_log_account_started_idx ON sync_log (account_id, started_at DESC);
