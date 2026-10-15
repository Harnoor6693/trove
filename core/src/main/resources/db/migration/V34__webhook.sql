-- V34 — webhook_subscription + webhook_delivery_log (collapse plan §3's V36 row, renumbered;
-- source: notification V4). Two tables in one file, the precedent step 10's V29, step 6's V22 and
-- step 3's V9_1 already set: they are one feature and neither is meaningful without the other.
--
-- USER-SCOPED, NOT HOUSEHOLD-SCOPED (§1.5, §3). A webhook points at a URL one member controls and
-- is signed with a secret only that member holds. A household mate's subscription is 404 by id.
--
-- THE SECRET IS ENCRYPTED AT REST, and that is a change from the source, not a restatement of it.
-- The source stored it as plaintext `VARCHAR(128)` — a shared HMAC secret sitting in the clear in
-- every database backup. It goes through the step-2 FieldEncryptionService with its `v1:` version
-- prefix, exactly as step 3 encrypts invite tokens (V9_2) and step 12 the per-user AI key (V16),
-- so the column is TEXT rather than VARCHAR(128): AES-GCM ciphertext plus base64 plus the prefix
-- is longer than its plaintext. It is write-only across the whole API — no response schema in
-- openapi/trove-core.yaml mentions it, and WebhookResponse carries `hasSecret` instead.
--
-- WHAT THE DELIVERY LOG'S STATUS SET SAYS, and why IN_FLIGHT is new.
-- §6 D3: deliver() is NOT one transaction wrapped around the outbound HTTP call. The source's was
-- (WebhookDeliveryService.java:49-75), which pins a database connection for up to twenty seconds
-- per delivery and, on a batch of slow receivers, blows the retry job's own lockAtMostFor. The
-- shape here is three steps: CLAIM due rows in a short transaction (`SELECT ... FOR UPDATE SKIP
-- LOCKED`, set status IN_FLIGHT), call out OUTSIDE any transaction, persist the outcome in a
-- second short transaction. IN_FLIGHT is the state the claim step sets, and it is what keeps a
-- second scheduler instance — or the same one 30 seconds later — from picking up a row whose
-- HTTP call is still running.
--
-- A row can therefore be left IN_FLIGHT if the process dies mid-call. That is deliberate and is
-- the safe direction: the alternative is re-POSTing a payment-shaped event to somebody's endpoint
-- twice. The due-index below covers PENDING and FAILED only, so an orphaned IN_FLIGHT row is
-- visible in the table and never silently redelivered.
--
-- INDEX NAMES carry their table, per §3's global rule: the source's `idx_webhook_log_pending` and
-- `idx_webhook_sub_user` were unique inside a private catalogue and are not any more.

CREATE TABLE webhook_subscription (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target_url       TEXT        NOT NULL,
    secret_encrypted TEXT        NOT NULL,
    enabled          BOOLEAN     NOT NULL DEFAULT true,
    event_types      TEXT[]      NOT NULL DEFAULT '{}',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX webhook_subscription_user_id_idx ON webhook_subscription (user_id);

COMMENT ON COLUMN webhook_subscription.secret_encrypted IS
    'AES-GCM ciphertext from FieldEncryptionService, carrying its v1: version prefix. The source '
    'stored this in the clear. Write-only: no API response carries it, in any form.';

CREATE TABLE webhook_delivery_log (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id  UUID        NOT NULL
                                 REFERENCES webhook_subscription (id) ON DELETE CASCADE,
    event_type       VARCHAR(50) NOT NULL,
    payload          TEXT        NOT NULL,
    status           VARCHAR(10) NOT NULL,
    attempt_count    INT         NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ,
    last_status_code INT,
    error_message    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT webhook_delivery_log_status_check
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'DELIVERED', 'FAILED', 'EXHAUSTED'))
);

-- The retry loop's only query. PENDING and FAILED are the two retryable states; IN_FLIGHT is
-- deliberately outside it, so a row whose HTTP call is still running is never claimed twice.
CREATE INDEX webhook_delivery_log_due_idx
    ON webhook_delivery_log (status, next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX webhook_delivery_log_sub_idx
    ON webhook_delivery_log (subscription_id, created_at DESC);

COMMENT ON TABLE webhook_delivery_log IS
    'One row per event queued for one subscription. IN_FLIGHT is set by the claim step of the '
    'three-transaction deliver() shape (§6 D3) and is what stops a second claim while the '
    'outbound call is still running. No user_id column: the row is reached only through its '
    'subscription, whose user_id is the scoping column.';
