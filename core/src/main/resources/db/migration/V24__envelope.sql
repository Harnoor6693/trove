-- Collapse step 8 (docs/recovery/06-COLLAPSE-PLAN.md §3 "V22" row, §1.5, §2.5, §4.6,
-- §6 "D1 — budgets spent"; docs/recovery/01-FINDINGS.md D1). See V23's header for the
-- V-number re-basing note — only the columns and constraints below are §3's, not the number.
--
-- THIS TABLE IS WHERE DEFECT D1 LIVED. `spent` was declared, defaulted to zero, and NEVER
-- WRITTEN by any code path in the platform (`grep -rn setSpent services/budget-service/src/main`
-- returns nothing, and §6 of that module's inventory shows it had no inbound integration at all).
-- Every health figure was therefore computed against zero, and RolloverService carried the FULL
-- allocation forward every month, compounding without bound. The column is unchanged here; what
-- changes is that EnvelopeSpendService now REWRITES it from the ledger — never `+=` — so
-- recategorising a transaction or re-running a sync cannot double-count (§9 risk 4).
--
-- WHAT CHANGES FROM budget-service V2__create_envelope.sql, and why:
--   * `household_id UUID NOT NULL` FK is the scope (§1.5). The source denormalised `user_id` onto
--     the envelope "so ownership can be verified without joining to the parent period"; the same
--     shortcut is kept, but with the column that actually scopes a shared ledger.
--   * `category_id` gains a REAL FOREIGN KEY to `category(id)` — the first time this reference has
--     been enforceable, because `category` has lived in the same catalogue since V14 (§3's
--     "FKs that become enforceable" list). It is declared ON DELETE RESTRICT rather than CASCADE
--     or SET NULL: an envelope with no category budgets nothing, and silently deleting a
--     household's budget line because somebody tidied up a category is worse than refusing.
--     That is the choice step 5 already made for merchant_category_map.category_id. The refusal
--     surfaces as DataIntegrityViolationException, which GlobalExceptionHandler already maps to
--     409 `conflict` — so DELETE /v1/categories/{id} answers 409 for an in-use category whether
--     the use is a merchant rule (CategoryService's own pre-check) or an envelope (this FK), and
--     never a 500. EnvelopeIT covers the envelope half.
--   * The source's denormalised `category_name VARCHAR(100) NOT NULL` is DROPPED. It was a copy
--     of `category.name` taken at creation time, so renaming a category left every envelope
--     showing the old label for ever. The name is joined on read instead — which is only possible
--     now that the two tables share one database.
--   * `allocated`, `spent`, `rollover_amount` all NUMERIC(19,4), widened from (12,2) (§9 risk 6).
--   * `currency VARCHAR(3) NOT NULL` with NO DEFAULT (finding F1; the source defaulted to 'CAD').
--     It defaults in code to the PERIOD's currency. An envelope only ever sums transactions of
--     its own currency, so a household budgeting one category in CAD and another in USD in the
--     same month is a normal case, not an error.
--   * `period_id` keeps ON DELETE CASCADE: an envelope has no meaning outside the month it
--     budgets. Transactions are untouched by either delete — an envelope is an aggregate over the
--     ledger, never a part of it.
--   * UNIQUE (period_id, category_id) kept: one envelope per category per month. Two would each
--     be rewritten to the same ledger total and the period would double-count itself.
--
-- INDEX NAMES carry the table prefix (§3's global rule; the source named them idx_envelope_*).
-- (household_id, category_id) is the index EnvelopeSpendService's writes go through, and
-- (period_id) is the one the listing and the ZERO_BASED allocation sum go through.

CREATE TABLE envelope (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    UUID          NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    period_id       UUID          NOT NULL REFERENCES budget_period(id) ON DELETE CASCADE,
    category_id     UUID          NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
    allocated       NUMERIC(19,4) NOT NULL,
    spent           NUMERIC(19,4) NOT NULL DEFAULT 0,
    rollover_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency        VARCHAR(3)    NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT envelope_period_category_unique UNIQUE (period_id, category_id)
);

CREATE INDEX envelope_period_id_idx ON envelope (period_id);
CREATE INDEX envelope_household_category_idx ON envelope (household_id, category_id);
CREATE INDEX envelope_category_id_idx ON envelope (category_id);
