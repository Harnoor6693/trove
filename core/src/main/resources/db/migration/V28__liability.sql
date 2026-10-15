-- Collapse step 9 (docs/recovery/06-COLLAPSE-PLAN.md §3 "V26" row, §1.5, §2.5, §4.7,
-- §6 "Net worth"). See V26__net_worth_snapshot.sql's header for the wave's V-number re-basing
-- note — only the columns and constraints below are §3's, not the number.
--
-- TENANCY (§1.5). `household_id` is the scope; `user_id` is CREATED-BY only, nullable with
-- ON DELETE SET NULL so removing a member does not remove the household's liabilities.
--
-- `account_id`: same treatment as `asset.account_id` (see V27) —
-- `NULL REFERENCES connected_account(id) ON DELETE SET NULL`.
--
-- `currency VARCHAR(3) NOT NULL` with NO default (§9 risks 5 and 6; finding F1: the source
-- defaulted every liability to `'CAD'`). The value comes from the request or the creating
-- member's `users.currency`, never from the column.
--
-- `interest_rate` is a PERCENTAGE, not money, but is declared `NUMERIC(19,4)` rather than added
-- to `SchemaDefaultsIT.NON_MONEY_NUMERICS`: four decimal places comfortably holds a rate like
-- 4.2500%, the column needs no test edit at all this way, and `SchemaDefaultsIT` is a step-6
-- file two other concurrent steps (8, 10) might also need to touch this same wave. It keeps its
-- own `DEFAULT 0` (not a currency column, so §9 risk 5's no-default rule does not apply) so an
-- omitted `interestRate` in the create request means zero-interest debt, matching the source's
-- behaviour.
--
-- `balance NUMERIC(19,4)`, widened from the source's `(12,2)` per §9 risk 6.

CREATE TABLE liability (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id   UUID          NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id        UUID          NULL REFERENCES users(id) ON DELETE SET NULL,
    name           VARCHAR(200)  NOT NULL,
    liability_type VARCHAR(20)   NOT NULL
                   CHECK (liability_type IN
                          ('MORTGAGE', 'CAR_LOAN', 'STUDENT_LOAN', 'CREDIT_CARD', 'OTHER')),
    balance        NUMERIC(19,4) NOT NULL,
    interest_rate  NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency       VARCHAR(3)    NOT NULL,
    account_id     UUID          NULL REFERENCES connected_account(id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX liability_household_id_idx ON liability (household_id);
CREATE INDEX liability_user_id_idx ON liability (user_id);
CREATE INDEX liability_account_id_idx ON liability (account_id);
