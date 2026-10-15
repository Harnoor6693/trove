-- V33 — in_app_notification (collapse plan §3's V35 row, renumbered; source: notification V3).
--
-- THE POINT OF THIS MIGRATION IS THAT SOMETHING NOW WRITES THIS TABLE. Finding D3's second half:
-- the pre-collapse service exposed five read endpoints over this table and had NO WRITER anywhere
-- — a notification centre that was permanently empty on every install, with a bootstrap javadoc
-- describing itself as "a stub pending full implementation". NotificationService.notify() inserts
-- one row here on every notification, unconditionally and before any channel is attempted, so the
-- inbox is the durable record of what this server told a member even when every channel failed.
--
-- USER-SCOPED, NOT HOUSEHOLD-SCOPED (§1.5). The shared ledger is shared; an inbox is not. A
-- household mate reading another member's notification by id is 404, and the repository extends
-- UserOwnedRepository so that there is no unscoped finder to reach for.
--
-- Changes from the source:
--   * the real foreign key it lacked, with ON DELETE CASCADE (it was a separate database);
--   * `type` widened VARCHAR(30) -> VARCHAR(40) and CHECKed against the same four values as
--     notification_log, so the inbox and the delivery log cannot drift into two vocabularies;
--   * both partial indexes RENAMED with the table prefix. §3's global rule is that an index name
--     carries its table: `idx_in_app_user_active` and `idx_in_app_user_unread_count` were unique
--     inside a private per-service catalogue and are not any more, now that one database holds
--     every table.
--
-- The two indexes are kept because both queries are real and both are hot: the inbox listing
-- (`user_id, created_at DESC` over undismissed rows) and the badge count (`user_id` over unread,
-- undismissed rows), the second of which the mobile client polls on every foreground because
-- there is no push channel to tell it otherwise.
--
-- `read` is a reserved word in some dialects but not in Postgres, and it is the column name the
-- source used and both clients already read. It is quoted nowhere and needs to be nowhere.

CREATE TABLE in_app_notification (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type         VARCHAR(40)  NOT NULL,
    title        VARCHAR(300) NOT NULL,
    body         TEXT         NOT NULL,
    action_url   TEXT,
    read         BOOLEAN      NOT NULL DEFAULT false,
    dismissed    BOOLEAN      NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at      TIMESTAMPTZ,
    dismissed_at TIMESTAMPTZ,

    CONSTRAINT in_app_notification_type_check
        CHECK (type IN ('BUDGET_THRESHOLD', 'SUBSCRIPTION_PRICE_CHANGE', 'SYNC_FAILED',
                        'WEEKLY_DIGEST'))
);

-- Covers GET /v1/notifications: this member's undismissed rows, newest first.
CREATE INDEX in_app_notification_active_idx
    ON in_app_notification (user_id, created_at DESC) WHERE dismissed = false;

-- Covers GET /v1/notifications/unread-count, which mobile polls on every foreground.
CREATE INDEX in_app_notification_unread_idx
    ON in_app_notification (user_id) WHERE read = false AND dismissed = false;

COMMENT ON TABLE in_app_notification IS
    'The member''s notification inbox — the one channel that is always written, so that the '
    'record survives a channel failure (finding D3). User-scoped, not household-scoped.';
