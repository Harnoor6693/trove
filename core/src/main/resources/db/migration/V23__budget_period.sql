-- Collapse step 8 (docs/recovery/06-COLLAPSE-PLAN.md §3 "V21" row, §1.5, §2.5, §4.6,
-- §6 "D1 — budgets spent"; docs/recovery/07-READINESS-GAPS.md P2-14, P2-15).
--
-- V-NUMBER RE-BASING. §3's table numbers this table V21 under the plan's ORIGINAL numbering.
-- Only its COLUMNS AND CONSTRAINTS are §3's; the number is not. Flyway needs a collision-free
-- history in landing order, and this wave assigns step 8 = V23-V25, step 9 = V26-V28 (already
-- landed), step 10 = V29-V30. Never renumber or edit a migration that exists.
--
-- WHAT CHANGES FROM budget-service V1__create_budget_period.sql, and why:
--   * `household_id UUID NOT NULL` FK is THE SCOPE (§1.5, owner decision: one shared household
--     ledger per install). `user_id` survives only as CREATED-BY, for audit, and is nullable with
--     ON DELETE SET NULL so removing a member does not delete the household's budgets.
--   * UNIQUE (household_id, month) REPLACES the source's UNIQUE (user_id, month). Under one
--     shared ledger two members must not each own a January: with the source's constraint both
--     would succeed, and the household would then have two contradictory budgets for one month
--     with nothing to say which one an envelope belongs to.
--   * `month` stays a DATE normalised to day 1 (BudgetPeriodService normalises before every
--     write, and the UNIQUE above is only meaningful because it does).
--   * `total_income NUMERIC(19,4)`, widened from the source's (12,2) per §9 risk 6.
--     SchemaDefaultsIT asserts every money column is exactly (19,4).
--   * `currency VARCHAR(3) NOT NULL` with NO DEFAULT. The source column defaulted to the literal
--     'CAD' (finding F1: twelve executable CAD sites in that module). Currency now comes from the
--     request or from the creating member's `users.currency`, never from a literal or a default.
--     SchemaDefaultsIT fails the build on a DEFAULT on any column named `currency`.
--   * `budget_type` CHECK IN ('FLEXIBLE','ROLLOVER','ZERO_BASED'), unchanged in value set.
--   * `rolled_over_at TIMESTAMPTZ NULL` is NEW, and it is what makes the rollover job idempotent
--     and catch-up safe (§5.6, gap P2-39). The source's RolloverScheduler fired once a month on
--     the 1st and decided what to do from `LocalDate.now()`: a container that was down at 01:00
--     UTC on the 1st silently cost every household that month's carry-forward, for ever. The job
--     now runs daily and decides from THIS COLUMN — a period whose month has ended for its
--     creator and whose `rolled_over_at` is still NULL is work outstanding, whatever the date is
--     — so a week of downtime costs nothing and a second run in the same window changes nothing.
--
-- INDEX NAMES carry the table prefix (§3's global rule): the source named them idx_budget_* in a
-- per-service catalogue and every table now shares one.

CREATE TABLE budget_period (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id   UUID          NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id        UUID          NULL REFERENCES users(id) ON DELETE SET NULL,
    month          DATE          NOT NULL,
    total_income   NUMERIC(19,4) NOT NULL,
    currency       VARCHAR(3)    NOT NULL,
    budget_type    VARCHAR(10)   NOT NULL
                   CHECK (budget_type IN ('FLEXIBLE', 'ROLLOVER', 'ZERO_BASED')),
    rolled_over_at TIMESTAMPTZ   NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT budget_period_month_is_first_of_month CHECK (EXTRACT(DAY FROM month) = 1),
    CONSTRAINT budget_period_household_month_unique UNIQUE (household_id, month)
);

CREATE INDEX budget_period_household_month_idx ON budget_period (household_id, month DESC);
CREATE INDEX budget_period_user_id_idx ON budget_period (user_id);

-- The rollover job's work list: ROLLOVER periods that have not been carried forward yet. Partial,
-- because the answer is almost always "none" and a full index over a table that grows twelve rows
-- per household per year would still have to be scanned.
CREATE INDEX budget_period_rollover_pending_idx ON budget_period (month)
    WHERE budget_type = 'ROLLOVER' AND rolled_over_at IS NULL;
