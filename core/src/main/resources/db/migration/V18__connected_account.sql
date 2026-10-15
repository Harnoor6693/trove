-- Collapse step 6 (docs/recovery/06-COLLAPSE-PLAN.md §3 V16-row, §2.2, §4.3, §6 M0).
--
-- NUMBERING. §3 prints this table's row as "V16" and handoff 11 §8 says step 6 starts at V19.
-- Both are stale: the merged tree already carries V14/V15 (step 5) and V16/V17 (step 12), so the
-- first free number is V18 and the five tables of this step take V18-V22. The COLUMNS and
-- CONSTRAINTS below are §3's; only the numbers differ. Flyway runs with out-of-order=false, so
-- nothing here renumbers or edits an existing migration.
--
-- V10 (user_preferences) is REDUNDANT and stays UNUSED: preferences live on `users` (V3). Noted
-- here because step 4's instruction was to record it in the first migration of a later step and
-- V14 already did so — repeated for the reader who starts at this file.
--
-- TENANCY (§1.5). `household_id` is the scope: one shared ledger per household, so every member
-- sees and may change every account. `user_id` means CREATED-BY and is for audit only; it is
-- nullable with ON DELETE SET NULL so that removing a member does not remove the household's
-- bank connections along with them.
--
-- WHAT CHANGES FROM ingestion-service V1__connected_account.sql, and why:
--   * `source_plugin VARCHAR(50)` becomes `source_kind` with a CHECK over the four kinds the
--     ingestion SPI knows (SIMPLEFIN, OFX, CSV, MANUAL). A free-text plugin id let a typo create
--     an account nothing could ever sync.
--   * `account_type` CHECK gains CREDIT_CARD and loses CREDIT: the Java enum has always spelled
--     it CREDIT_CARD, so every credit-card account the old service tried to insert violated its
--     own CHECK.
--   * `sync_status` CHECK gains SYNCING. That value is not decorative — it is the CLAIM
--     SyncOrchestrator takes with a conditional UPDATE (§6 M0), and a CHECK that rejected it
--     would make two overlapping syncs impossible to prevent.
--   * `config_encrypted` becomes NULLABLE, and ConnectedAccount's @Column(nullable = false)
--     changes with it in this same commit. A MANUAL, CSV or OFX account has no credential blob
--     to encrypt; with the NOT NULL in place, `ddl-auto: validate` throws on boot and no manual
--     account can be inserted at all (gap P2-13).
--   * `currency` keeps NOT NULL and LOSES its 'CAD' DEFAULT (§9 risks 5 and 6). A default
--     currency is how a USD account silently becomes a CAD one; the value comes from the request
--     or from the creating member's `users.currency`, never from the column.
--   * `external_account_id` is new, with UNIQUE (household_id, source_kind, external_account_id)
--     NULLS NOT DISTINCT. Without it a SimpleFIN re-exchange or a repeat OFX/CSV upload (step 7)
--     has nothing to look up and re-use, so it creates a second account and duplicates every
--     transaction on it — past anything the per-account dedup key can catch (§6 M0, blocker).
--
-- NULLS NOT DISTINCT, AND WHAT IT MEANS FOR HAND-CREATED ACCOUNTS. Nulls compare EQUAL in this
-- constraint, so two rows of the same household and kind may not both leave `external_account_id`
-- null. That is exactly what makes the key work for a PROVIDER account — a re-exchange or a repeat
-- upload finds the existing row instead of creating a second one — but on its own it would also
-- cap a household at one account per kind, which gap P2-14 (transfers between the household's OWN
-- accounts) rules out immediately, and which is wrong anyway: "Cash" and "Cheques" are two
-- ordinary manual accounts. So AccountService stores the row's own primary key as the external id
-- for every account created through POST /v1/accounts. Such an account is a placeholder somebody
-- typed in, with no provider identity to be looked up by; the rows step 7 creates from a real
-- bridge or file DO carry the provider's id and are still held to one per household per kind.

CREATE TABLE connected_account (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id        UUID         NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id             UUID         NULL REFERENCES users(id) ON DELETE SET NULL,
    source_kind         VARCHAR(20)  NOT NULL
                        CHECK (source_kind IN ('SIMPLEFIN', 'OFX', 'CSV', 'MANUAL')),
    external_account_id VARCHAR(255) NULL,
    display_name        VARCHAR(200) NOT NULL,
    institution         VARCHAR(200) NULL,
    account_type        VARCHAR(20)  NOT NULL
                        CHECK (account_type IN
                               ('CHECKING', 'SAVINGS', 'CREDIT_CARD', 'INVESTMENT', 'OTHER')),
    currency            VARCHAR(3)   NOT NULL,
    config_encrypted    TEXT         NULL,
    last_synced_at      TIMESTAMPTZ  NULL,
    sync_status         VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                        CHECK (sync_status IN ('PENDING', 'SYNCING', 'OK', 'ERROR')),
    error_message       TEXT         NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT connected_account_external_unique
        UNIQUE NULLS NOT DISTINCT (household_id, source_kind, external_account_id)
);

CREATE INDEX connected_account_household_id_idx ON connected_account (household_id);
CREATE INDEX connected_account_household_status_idx
    ON connected_account (household_id, sync_status);
CREATE INDEX connected_account_user_id_idx ON connected_account (user_id);
