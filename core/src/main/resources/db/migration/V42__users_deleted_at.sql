-- V42 — users.deleted_at / users.deleted_by (collapse step 18; gap row 16's seven-day grace).
--
-- WHY A SECOND COLUMN AND NOT A SECOND FLAG. `users.is_active BOOLEAN NOT NULL DEFAULT true`
-- already exists (V3) and is ALREADY honoured on every path that matters: `AuthService` filters it
-- twice, `OidcAccountLinker` twice, `LocalAccountService.authenticate`, `WeeklyDigestJob`,
-- `NotificationEventListener`, `WebhookEventListener` and `InsightRecipients`' SQL. So the soft
-- delete IS `is_active = false` plus a stamp, and this migration deliberately does NOT add a
-- second liveness flag or a status enum that could disagree with the first one.
--
-- What `deleted_at` adds is the ONE thing `is_active` cannot say: the difference between a member
-- an administrator merely disabled and a member who is inside the deletion grace. Only the second
-- may be erased, so `deleted_at` is the only column the purge job selects on. A disabled member
-- with a null `deleted_at` is never touched by it.
--
-- `deleted_by` is ON DELETE SET NULL rather than CASCADE, which is the shape §3's own enumeration
-- uses for every "who did this" column (V14/V15 `created_by`, and the ten household-scoped
-- `user_id` columns). CASCADE here would be actively wrong: an administrator deleting their own
-- account afterwards would take with them every row that records what they did.
--
-- The index is PARTIAL. The purge job's only query is "rows stamped before a cutoff", and on any
-- real install almost every row has a null stamp for ever; a full index would be almost entirely
-- dead entries that every INSERT and UPDATE of `users` still has to maintain.

ALTER TABLE users
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ADD COLUMN deleted_by UUID NULL REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX users_deleted_at_idx ON users (deleted_at) WHERE deleted_at IS NOT NULL;
