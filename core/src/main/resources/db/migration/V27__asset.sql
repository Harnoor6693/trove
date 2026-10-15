-- Collapse step 9 (docs/recovery/06-COLLAPSE-PLAN.md §3 "V25" row, §1.5, §2.5, §4.7,
-- §6 "Net worth"). See V26's header for the wave's V-number re-basing note — only the columns
-- and constraints below are §3's, not the number.
--
-- TENANCY (§1.5). `household_id` is the scope; `user_id` is CREATED-BY only, nullable with
-- ON DELETE SET NULL so removing a member does not remove the household's assets.
--
-- `account_id` links an asset to a `connected_account` (V18, this wave's step 6) and is one of
-- the FKs §3 calls out as "finally enforceable" once that table exists:
-- `NULL REFERENCES connected_account(id) ON DELETE SET NULL`, so disconnecting the linked
-- account leaves the asset as a plain manual entry rather than deleting it or leaving a
-- dangling id — the source column had no FK at all.
--
-- `currency VARCHAR(3) NOT NULL` with NO default (§9 risks 5 and 6; finding F1: the source
-- defaulted every asset to `'CAD'` in both the column and the entity). The value now always
-- comes from the request or the creating member's `users.currency`
-- (`NetWorthAssetService#create`), never from the column.
--
-- `value NUMERIC(19,4)`, widened from the source's `(12,2)` per §9 risk 6.

CREATE TABLE asset (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id UUID          NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id      UUID          NULL REFERENCES users(id) ON DELETE SET NULL,
    name         VARCHAR(200)  NOT NULL,
    asset_type   VARCHAR(20)   NOT NULL
                 CHECK (asset_type IN ('CASH', 'INVESTMENT', 'REAL_ESTATE', 'VEHICLE', 'OTHER')),
    value        NUMERIC(19,4) NOT NULL,
    currency     VARCHAR(3)    NOT NULL,
    account_id   UUID          NULL REFERENCES connected_account(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX asset_household_id_idx ON asset (household_id);
CREATE INDEX asset_user_id_idx ON asset (user_id);
CREATE INDEX asset_account_id_idx ON asset (account_id);
