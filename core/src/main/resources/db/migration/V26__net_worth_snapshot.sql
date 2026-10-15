-- Collapse step 9 (docs/recovery/06-COLLAPSE-PLAN.md §3 "V24" row, §1.5, §2.5, §4.7,
-- §6 "Net worth"; 07-READINESS-GAPS.md row 40 deferred — see the step-9 report's openIssues).
--
-- NUMBERING. §3's table prints this migration as "V24" under the plan's ORIGINAL numbering.
-- Handoff 11 §8 re-bases wave-A landings but this table pre-dates wave B: at wave-B landing
-- time (this step's launch) the next free number was V26, because this wave's step 8
-- (budgets/envelopes/goals/rollover) takes V23-V25 and this wave's step 10 (subscriptions)
-- takes V29-V30, both in their own concurrent worktrees. Only the COLUMNS and CONSTRAINTS
-- below are §3's; the number is this step's own.
--
-- V10 (user_preferences) is REDUNDANT and stays UNUSED: preferences live on `users` (V3).
-- Repeated here per the standing convention (each step's first migration says so) for the
-- reader who starts at this file.
--
-- TENANCY (§1.5). `household_id` is the scope — one shared household ledger, so every member
-- sees the same snapshots. `user_id` is CREATED-BY only, nullable with ON DELETE SET NULL so
-- removing a member does not remove the household's history with them.
--
-- F2 FIX — the reason this step exists. The source table's unique key was
-- `(user_id, snapshot_date)` with a single blended `net_worth`: a household holding CAD assets
-- and a USD liability had its two currencies summed as though they were one unit
-- (01-FINDINGS F2; inventory §10). The unique key below adds `currency` as a THIRD column: a
-- snapshot is now ONE ROW PER CURRENCY PRESENT, so a household with both CAD and USD positions
-- gets two rows on the same date, never one invented number. There is no FX rate anywhere in
-- this system (07-READINESS-GAPS.md row 40, deferred): two currencies are two rows, always.
--
-- `net_worth` stays a GENERATED (STORED) column, kept from the source, so Postgres — not the
-- application — is the one place it can never drift from `total_assets - total_liabilities`.
-- `NetWorthSnapshot` maps it `insertable = false, updatable = false`; `ddl-auto: validate`
-- accepts it because the generation expression is not part of what validate compares, only the
-- column's name, type and nullability are.
--
-- `currency VARCHAR(3) NOT NULL` with NO default (§9 risks 5 and 6): the snapshot's currency
-- always comes from the assets/liabilities it summed, never from a column default.

CREATE TABLE net_worth_snapshot (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id      UUID          NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id           UUID          NULL REFERENCES users(id) ON DELETE SET NULL,
    snapshot_date     DATE          NOT NULL,
    total_assets      NUMERIC(19,4) NOT NULL,
    total_liabilities NUMERIC(19,4) NOT NULL,
    net_worth         NUMERIC(19,4)
                      GENERATED ALWAYS AS (total_assets - total_liabilities) STORED,
    currency          VARCHAR(3)    NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT net_worth_snapshot_household_date_currency_unique
        UNIQUE (household_id, snapshot_date, currency)
);

CREATE INDEX net_worth_snapshot_household_id_idx ON net_worth_snapshot (household_id);
CREATE INDEX net_worth_snapshot_household_date_idx
    ON net_worth_snapshot (household_id, snapshot_date DESC);
CREATE INDEX net_worth_snapshot_user_id_idx ON net_worth_snapshot (user_id);
