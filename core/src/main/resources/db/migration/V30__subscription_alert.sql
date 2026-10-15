-- Collapse step 10 (docs/recovery/06-COLLAPSE-PLAN.md §3 "V32" row, §1.5, §2.7, §4.8;
-- docs/recovery/inventory/subscription-detector.md §4). See V26's header for the wave's V-number
-- re-basing note — only the columns and constraints below are §3's, not the number.
--
-- WHAT CHANGES FROM subscription-detector V2__create_subscription_alert.sql:
--   * `household_id` NOT NULL FK is the SCOPE (§1.5); the source had `user_id` only, so one
--     member's price-increase alert was invisible to the household mate paying the same bill.
--     `user_id` stays as CREATED-BY — the member the detection run was attributed to — nullable
--     with ON DELETE SET NULL so removing a member does not delete the household's alerts.
--   * `subscription_id` keeps its FK ON DELETE CASCADE (the source's one and only FK) and gains
--     NOT NULL explicitly. No route in this version deletes a detected_subscription —
--     DELETE /v1/subscriptions/{id} is a soft delete — so in practice the cascade fires only when
--     a household is removed.
--   * Index names carry their table prefix (§3's global rule). The source named them
--     `idx_sub_alert_*`, which was unique inside a private catalog and is not any more.
--
-- Alerts are append-only in this step: detection inserts them, PATCH .../read sets is_read, and
-- nothing ever deletes one. They are the durable record of a price change, which is why the
-- AMBER signal on the subscription itself may come and go while this row does not.
--
-- The CHECK admits all three of the source's alert types, but this step WRITES ONLY
-- PRICE_INCREASE. `UPCOMING` is served instead by the summary's next14Days — a row per upcoming
-- charge would be a notification, and notifications are step 11 — and `PAYMENT_FAILED` needs a
-- failure signal no source reports today. They are kept in the constraint so that adding either
-- later is code, not a migration.

CREATE TABLE subscription_alert (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id    UUID        NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id         UUID        NULL REFERENCES users(id) ON DELETE SET NULL,
    subscription_id UUID        NOT NULL
                    REFERENCES detected_subscription(id) ON DELETE CASCADE,
    alert_type      VARCHAR(20) NOT NULL
                    CHECK (alert_type IN ('PRICE_INCREASE', 'UPCOMING', 'PAYMENT_FAILED')),
    alert_message   TEXT        NOT NULL,
    is_read         BOOLEAN     NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The alert feed: newest first within a household.
CREATE INDEX subscription_alert_household_created_idx
    ON subscription_alert (household_id, created_at DESC);

-- GET /v1/subscriptions/alerts reads only the unread ones, and on a healthy install they are a
-- small minority of the table — a partial index keeps that read proportional to what it returns.
CREATE INDEX subscription_alert_household_unread_idx
    ON subscription_alert (household_id, is_read) WHERE is_read = false;

CREATE INDEX subscription_alert_subscription_id_idx ON subscription_alert (subscription_id);
CREATE INDEX subscription_alert_user_id_idx ON subscription_alert (user_id);
