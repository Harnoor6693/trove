-- V32 — notification_log (collapse plan §3's V34 row, renumbered; source: notification V2).
--
-- What this table is FOR: one row per delivery attempt, carrying the REAL outcome. The
-- pre-collapse dispatcher (NotificationDispatcher.java:87-109, finding D3) initialised
-- status="SENT" before it attempted anything and only downgraded inside a catch — while none of
-- its three channels ever threw (EmailChannel.java:60-64, ApnsChannel.java:141-147,
-- FcmChannel.java:113-125 all log-and-return, as does the dispatcher's own default branch for an
-- unknown channel). So `status` was permanently SENT, `error_message` permanently null, and both
-- FAILED and the schema's own PENDING were unreachable. The fix is a channel that RETURNS a
-- DeliveryResult{SENT|FAILED, error} rather than one that cannot fail, and this table is where
-- that result lands.
--
-- CHANNEL. ('EMAIL','IN_APP') only. The source's PUSH_APNS and PUSH_FCM go with the owner's
-- 2026-09-03 decision: no push until a direct APNs/FCM path exists, no Expo relay. EMAIL stays in
-- the CHECK even though the channel ships disabled, because widening a CHECK later is a migration
-- and keeping it now is free.
--
-- TYPE IS VARCHAR(40), NOT THE SOURCE'S VARCHAR(20), and that is a bug fix rather than a
-- preference. 'SUBSCRIPTION_PRICE_CHANGE' is 25 characters. CREATE TABLE would have succeeded and
-- every insert of that type would then have failed with `value too long for character
-- varying(20)`, silently dropping every price-change notification — §3's V34 row is explicit
-- about it.
--
-- The CHECK list is exactly the set this build's producers can write, and nothing else:
--   BUDGET_THRESHOLD          — an envelope crossed a member's own alert threshold
--   SUBSCRIPTION_PRICE_CHANGE — a detected recurring charge changed price
--   SYNC_FAILED               — an account sync failed or partially failed
--   WEEKLY_DIGEST             — the Monday digest
-- TAX_REMINDER goes with the tax engine (owner decision 2, defect D-N3) and CUSTOM goes with the
-- /internal/notifications/send route that no longer exists. RECEIPT_PROCESSED is deliberately
-- ABSENT: ReceiptProcessedEvent's publisher is collapse step 14, which has not run, and a CHECK
-- value no producer can write is a promise this schema cannot keep. Step 14 widens it.
--
-- THE PARTIAL UNIQUE INDEX IS THE POINT OF THIS TABLE.
-- Deduplication is a KEY, not a timer. Each notification type states its own dedup_key with its
-- window encoded IN the key — an envelope+month, a subscription+new amount, an account+the
-- recipient's own local date, an ISO week — and this index is what makes two listeners that
-- really can run at once unable to both send. A "have we sent one in the last N hours?" query
-- instead would be a check-then-act across two transactions, which is not a defence at all.
--
-- It is PARTIAL (`WHERE dedup_key IS NOT NULL`) rather than `NULLS NOT DISTINCT`. A NULL
-- dedup_key means "this notification is not deduplicated"; NULLS NOT DISTINCT would treat every
-- one of those as equal to every other and collapse them into a single row per (user, type),
-- which is the opposite of what NULL means here.
--
-- ONE ROW IS THE GATE, THE REST ARE RECORDS. NotificationService claims a notification by
-- inserting the IN_APP row with the dedup key and `ON CONFLICT DO NOTHING`: zero rows inserted
-- means this notification has already happened and the call does nothing else. A second channel's
-- row (EMAIL) carries dedup_key NULL, because the gate has already been taken and this index
-- admits exactly one row per key.
--
-- PENDING IS REACHABLE HERE, unlike in the source. The claim row is inserted PENDING and is
-- rewritten to SENT or FAILED when the channel answers. The source's
-- `idx_notification_log_pending` index was dead (inventory §4: no row ever carried PENDING); it
-- is NOT recreated, because PENDING is now a state that lasts microseconds inside one
-- transaction rather than a queue anything scans.
--
-- The source had no foreign key. ON DELETE CASCADE is added.

CREATE TABLE notification_log (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    channel       VARCHAR(12) NOT NULL,
    type          VARCHAR(40) NOT NULL,
    subject       VARCHAR(500),
    body          TEXT,
    status        VARCHAR(8)  NOT NULL,
    sent_at       TIMESTAMPTZ,
    error_message TEXT,
    dedup_key     VARCHAR(200),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT notification_log_channel_check
        CHECK (channel IN ('EMAIL', 'IN_APP')),
    CONSTRAINT notification_log_type_check
        CHECK (type IN ('BUDGET_THRESHOLD', 'SUBSCRIPTION_PRICE_CHANGE', 'SYNC_FAILED',
                        'WEEKLY_DIGEST')),
    CONSTRAINT notification_log_status_check
        CHECK (status IN ('PENDING', 'SENT', 'FAILED')),

    -- A FAILED row without a reason is exactly the lie D3 was: the schema refuses it.
    CONSTRAINT notification_log_failed_has_a_reason
        CHECK (status <> 'FAILED' OR error_message IS NOT NULL)
);

CREATE INDEX notification_log_user_id_idx ON notification_log (user_id, created_at DESC);

CREATE UNIQUE INDEX notification_log_dedup_idx
    ON notification_log (user_id, type, dedup_key)
    WHERE dedup_key IS NOT NULL;

COMMENT ON TABLE notification_log IS
    'One row per delivery attempt with its REAL outcome (finding D3). User-scoped, not '
    'household-scoped: a notification is addressed to one person.';
COMMENT ON COLUMN notification_log.dedup_key IS
    'The type''s own key with its window encoded in it, or NULL for a one-off. The partial unique '
    'index over (user_id, type, dedup_key) is what makes two concurrent producers unable to both '
    'send; NULL means "not deduplicated" and is deliberately not covered by it.';
