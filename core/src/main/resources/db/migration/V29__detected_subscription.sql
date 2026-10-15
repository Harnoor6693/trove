-- Collapse step 10 (docs/recovery/06-COLLAPSE-PLAN.md §3 "V30"/"V31" rows, §1.5, §2.7, §4.8,
-- §6 "D2 — subscription scan"; docs/recovery/inventory/subscription-detector.md §4, §10 SD-3,
-- SD-5). See V26's header for the wave's V-number re-basing note — only the columns and
-- constraints below are §3's, not the number.
--
-- TWO OBJECTS IN ONE FILE, DELIBERATELY. §3 drafts `detected_subscription` and
-- `subscription_transaction` as separate migrations, but the join table is a child that cannot
-- exist before its parent and has no meaning without it, so it ships with it — the same shape as
-- V9_1 (household_invite + password_reset) and V22 (import_batch + the deferred ALTER on
-- transaction).
--
-- WHAT CHANGES FROM subscription-detector V1__create_detected_subscription.sql, and why:
--   * `household_id` NOT NULL FK is the SCOPE (§1.5). The source table had `user_id` only, which
--     under the shared-ledger decision would hide one member's subscriptions from their
--     household mate on the same ledger. `user_id` stays as CREATED-BY, for audit, nullable with
--     ON DELETE SET NULL so removing a member does not delete the household's detections.
--   * UNIQUE (household_id, merchant_name) — the constraint the source table NEVER HAD (SD-5).
--     Uniqueness there was a read-then-write in SubscriptionDetectionService.java:124-138, which
--     was safe only because one sequential scheduler was the sole caller. This step adds a manual
--     trigger (POST /v1/subscriptions/scan) and an event listener, so the same household really
--     can be scanned twice at once and the duplicate-insert race becomes reachable. This
--     constraint is also the exact ON CONFLICT target §6's D2 upsert needs. Per HOUSEHOLD, not
--     per user, because the ledger is shared (§1.5).
--   * `transaction_ids uuid[]` is DROPPED ENTIRELY in favour of `subscription_transaction` below.
--     A Postgres array of ids referenced nothing: it could hold ids of transactions that no
--     longer existed, and no join could use it. `transaction` is V19 in this same catalog now, so
--     the link is finally a real referential one.
--   * `normalized_amount` and `previous_amount` are NUMERIC(19,4), widened from the source's
--     (12,2) per §9 risk 6; SchemaDefaultsIT asserts the precision of every money column.
--   * `currency VARCHAR(3) NOT NULL` with NO DEFAULT. The source column was DEFAULT 'CAD' and
--     nothing in its src/main ever overrode it (finding F1 / SD-3), so every detected
--     subscription on every install was a Canadian dollar amount whether or not it was one. The
--     value now always comes from the transactions the row was detected from.
--   * `category_id` gains a real FK to `category` (V14) ON DELETE SET NULL. It had none.
--   * Index names carry their table prefix (§3's global rule). The source named these
--     `idx_subscription_*`, which was unique inside a private catalog and is not any more.
--
-- `is_cancelled` is a SOFT DELETE: DELETE /v1/subscriptions/{id} sets it and the row survives,
-- because a hard delete would simply be re-detected by the next scan and the member would have to
-- dismiss the same merchant every night. The scan keeps a cancelled row's figures current but
-- never un-cancels it.

CREATE TABLE detected_subscription (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id      UUID          NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id           UUID          NULL REFERENCES users(id) ON DELETE SET NULL,
    merchant_name     VARCHAR(200)  NOT NULL,
    category_id       UUID          NULL REFERENCES category(id) ON DELETE SET NULL,
    normalized_amount NUMERIC(19,4) NOT NULL,
    previous_amount   NUMERIC(19,4) NULL,
    currency          VARCHAR(3)    NOT NULL,
    billing_cycle     VARCHAR(12)   NOT NULL
                      CHECK (billing_cycle IN ('WEEKLY', 'BIWEEKLY', 'MONTHLY', 'QUARTERLY',
                                               'ANNUAL')),
    next_charge_date  DATE          NULL,
    signal            VARCHAR(6)    NOT NULL
                      CHECK (signal IN ('GREEN', 'AMBER', 'RED')),
    price_changed     BOOLEAN       NOT NULL DEFAULT false,
    first_seen_date   DATE          NOT NULL,
    last_seen_date    DATE          NOT NULL,
    is_cancelled      BOOLEAN       NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT detected_subscription_household_merchant_key
        UNIQUE (household_id, merchant_name)
);

-- The list route reads (household_id, is_cancelled = false) ORDER BY merchant_name; the partial
-- index answers the filter and the order in one scan. The unique constraint above cannot: it
-- covers cancelled rows too, so the ordering would still need a sort over them.
CREATE INDEX detected_subscription_household_active_idx
    ON detected_subscription (household_id, merchant_name) WHERE is_cancelled = false;

-- The summary's next-fourteen-days window.
CREATE INDEX detected_subscription_household_next_charge_idx
    ON detected_subscription (household_id, next_charge_date) WHERE is_cancelled = false;

CREATE INDEX detected_subscription_user_id_idx ON detected_subscription (user_id);
CREATE INDEX detected_subscription_category_id_idx ON detected_subscription (category_id);

-- The replacement for `transaction_ids uuid[]`: which transactions a detection was built from.
-- (subscription_id, transaction_id) and nothing else — it carries no household_id of its own
-- because it has no independent existence; both parents are household-scoped and either cascade
-- removes the link. Nothing in this step exposes it over HTTP; it is the referential record, and
-- the coach (step 13) is its first reader.
CREATE TABLE subscription_transaction (
    subscription_id UUID NOT NULL REFERENCES detected_subscription(id) ON DELETE CASCADE,
    transaction_id  UUID NOT NULL REFERENCES transaction(id) ON DELETE CASCADE,
    PRIMARY KEY (subscription_id, transaction_id)
);

-- The primary key already serves subscription -> transactions. This one serves the reverse
-- ("which subscription is this transaction part of") and keeps a transaction delete from
-- sequentially scanning the join table to find the rows to cascade.
CREATE INDEX subscription_transaction_transaction_id_idx
    ON subscription_transaction (transaction_id);
