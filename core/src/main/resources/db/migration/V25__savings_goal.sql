-- Collapse step 8 (docs/recovery/06-COLLAPSE-PLAN.md §3 "V23" row, §1.5, §2.5, §4.6).
-- See V23's header for the V-number re-basing note — only the columns and constraints below
-- are §3's, not the number.
--
-- WHAT CHANGES FROM budget-service V3__create_savings_goal.sql, and why:
--   * `household_id UUID NOT NULL` FK is the scope (§1.5): a savings goal is household money
--     under the shared-ledger decision, so every member sees it and may contribute to it.
--     `user_id` is CREATED-BY only, nullable with ON DELETE SET NULL, so a member leaving does
--     not take the household's goals with them.
--   * `target_amount` / `current_amount` NUMERIC(19,4), widened from (12,2) (§9 risk 6).
--   * `currency VARCHAR(3) NOT NULL` with NO DEFAULT (finding F1; the source defaulted to 'CAD'
--     in both the column and the entity). It comes from the request or from the creating
--     member's `users.currency`.
--   * `is_achieved BOOLEAN NOT NULL DEFAULT false` kept. It is DERIVED, not client-supplied:
--     GoalService sets it from `current_amount >= target_amount` on every write, so a goal cannot
--     be reported met while it is short, or left unmet after the contribution that completed it.
--     It is a stored column rather than a GENERATED one because `target_amount` is itself
--     writable and Postgres generated columns may not reference a column an UPDATE changes in the
--     same statement in every supported version — and the invariant is cheap to hold in one place.
--   * `deadline DATE NULL` kept: null means an open-ended goal. No index — a household has a
--     handful of goals and every query already filters on household_id.
--   * No UNIQUE constraint, as in the source: two goals may legitimately share a name
--     ("Vacation" twice, for two different trips).
--
-- INDEX NAMES carry the table prefix (§3's global rule).

CREATE TABLE savings_goal (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id   UUID          NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id        UUID          NULL REFERENCES users(id) ON DELETE SET NULL,
    name           VARCHAR(200)  NOT NULL,
    target_amount  NUMERIC(19,4) NOT NULL,
    current_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    deadline       DATE          NULL,
    currency       VARCHAR(3)    NOT NULL,
    is_achieved    BOOLEAN       NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX savings_goal_household_id_idx ON savings_goal (household_id);
CREATE INDEX savings_goal_user_id_idx ON savings_goal (user_id);
