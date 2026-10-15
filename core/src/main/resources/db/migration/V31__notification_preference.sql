-- V31 — notification_preference (collapse plan §3's V33 row, renumbered).
--
-- RENUMBERING NOTE. §3 drafted this table as V33 under a numbering that assumed collapse steps
-- 6-10 had taken V16-V32. They took different numbers, and Flyway runs with out-of-order=false,
-- so the history has to be dense and collision-free at merge time. This step therefore takes
-- V31-V34. Source: services/notification-service/src/main/resources/db/migration/V1.
--
-- NOTE on V10: the plan's V10 (user_preferences) is REDUNDANT and stays UNUSED — preferences live
-- on `users` (V3). Nothing here depends on it.
--
-- WHY THE PRIMARY KEY IS user_id AND THERE IS NO household_id, AND NO SURROGATE id.
-- Every ledger table in this schema is scoped by household_id, because the owner's decision is
-- one shared ledger per install (§1.5). The four tables this step adds are the deliberate
-- opposite: a notification is addressed to one person. A household mate — administrator
-- included — must not read another member's preferences, inbox or webhooks, and making the user
-- id the primary key rather than a foreign key beside a surrogate id is what makes that
-- structural: there is no id by which this row can be addressed without naming its owner. The
-- repository extends UserKeyedRepository, whose every finder takes a user id, and ArchUnit fails
-- the build on a repository that does not extend one of the three scoped bases.
--
-- The source had `id UUID PRIMARY KEY` plus `user_id UUID NOT NULL UNIQUE` plus
-- `idx_notification_pref_user_id` — a surrogate key nobody needed, and an index that exactly
-- duplicated the unique constraint's own. Both are dropped: the primary key indexes user_id.
--
-- THE SOURCE HAD NO FOREIGN KEY AT ALL. It was a separate service with a separate database, so
-- a deleted account left its preferences behind for ever. ON DELETE CASCADE is added.
--
-- WHAT IS DROPPED RATHER THAN MOVED, and why each is a decision:
--   * push_enabled, apns_token, fcm_token — owner decision, 2026-09-03: no push notifications
--     until a direct APNs/FCM path exists, and no Expo relay. §3's V33 row says "no push-token
--     column of any kind". There is no push channel, no device route and no client-visible push
--     field anywhere in this design; a column here would be the first step back towards one.
--   * tax_reminder_enabled — inventory defect D-N3. The tax ENGINE is cut (owner decision 2);
--     what ships is a tax EXPORT with no reminder to give. The source carried this field in six
--     places (entity, request DTO, response DTO, this column, the notification_log type CHECK,
--     and the dispatcher's Thymeleaf allowlist plus its template); all six are deleted.
--
-- email_enabled DEFAULTS TO FALSE, and that is not the source's value. The source defaulted it
-- true, which on an install with no SMTP configured means every member is opted in to a channel
-- that cannot deliver. The channel is implemented and unit-tested here but is gated off by
-- trove.notifications.smtp-enabled (default false) until an operator configures spring.mail.*,
-- so the honest default is off.
--
-- THE BUDGET THRESHOLD IS A WHOLE PERCENT, NOT A RATIO, and that is the interesting choice.
-- The source column was NUMERIC(3,2) DEFAULT 0.90. This schema's standing rule (§9 risk 6) is
-- that every numeric column is NUMERIC(19,4) — money — and SchemaDefaultsIT scans
-- information_schema across the whole database to enforce it, with a named allowlist for the
-- exceptions. A ratio here would need an allowlist entry in a file three concurrent steps share.
-- SMALLINT needs none: an integer percent is not a numeric column at all, cannot silently become
-- a double, and reads the same way in a settings form ("tell me at 90%"). The CHECK allows up to
-- 200 so that a member who wants to hear only once they are well over budget can say so; 1 is the
-- floor because 0 would fire on the first transaction of every month.

CREATE TABLE notification_preference (
    user_id                        UUID        PRIMARY KEY
                                               REFERENCES users (id) ON DELETE CASCADE,
    in_app_enabled                 BOOLEAN     NOT NULL DEFAULT true,
    email_enabled                  BOOLEAN     NOT NULL DEFAULT false,
    email_address                  VARCHAR(320),
    weekly_digest_enabled          BOOLEAN     NOT NULL DEFAULT true,
    subscription_alert_enabled     BOOLEAN     NOT NULL DEFAULT true,
    sync_failure_alert_enabled     BOOLEAN     NOT NULL DEFAULT true,
    budget_alert_threshold_percent SMALLINT    NOT NULL DEFAULT 90,
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT notification_preference_threshold_range
        CHECK (budget_alert_threshold_percent BETWEEN 1 AND 200)
);

COMMENT ON TABLE notification_preference IS
    'Per-user notification settings. Deliberately NOT household-scoped: a household mate must not '
    'be able to read or write another member''s notification settings (§1.5). No push column of '
    'any kind — owner decision, 2026-09-03.';
COMMENT ON COLUMN notification_preference.in_app_enabled IS
    'Whether a new notification announces itself. The in_app_notification row is written either '
    'way — it is the durable record of what this server told this member — but with this off the '
    'row arrives already read, so it never reaches the unread badge.';
COMMENT ON COLUMN notification_preference.email_enabled IS
    'Off by default: the email channel cannot deliver until an operator configures SMTP, and '
    'opting members in to a channel that silently drops everything is the defect D3 is about.';
COMMENT ON COLUMN notification_preference.budget_alert_threshold_percent IS
    'Whole percent of an envelope''s allocation, 1-200. A SMALLINT rather than the source''s '
    'NUMERIC(3,2) so that it needs no entry in SchemaDefaultsIT.NON_MONEY_NUMERICS and cannot be '
    'mistaken for money.';
