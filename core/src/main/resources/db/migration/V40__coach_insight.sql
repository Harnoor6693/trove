-- Collapse step 13 — the weekly coach insight
-- (docs/recovery/06-COLLAPSE-PLAN.md §3's V39 row, §2.10, §4.11, §5 item 6, §6;
--  source: services/model-manager V6__coach_insight.sql).
--
-- RE-BASED V-NUMBER: §3 prints this table as V39 under the plan's ORIGINAL numbering. Only its
-- columns and constraints come from that row; the number is the next free one at landing time
-- (§3's re-basing note). Flyway runs with out-of-order=false: applied migrations are never edited
-- or renumbered.
--
-- PER MEMBER, like coach_conversation (V39) and ai_settings (V16), and for the same reason: the
-- note names what one member spent on, and no household mate — administrator included — reads
-- another's. `CoachInsightRepository` extends `UserOwnedRepository`.
--
-- `insight_text` BECOMES `content_encrypted`, for the reason V39's messages are encrypted
-- (readiness gap row 44, second half): a sentence naming what a member spent on is that member's
-- ledger, rendered into prose. The column holds `FieldEncryptionService` ciphertext with its `v1:`
-- prefix and there is no plaintext copy beside it.
--
-- `week_start` AND ITS UNIQUE ARE NEW, AND THEY ARE THE IDEMPOTENCE GUARD. The weekly job holds a
-- ShedLock (`weekly-insight`, §5 item 6), but `lockAtMostFor` expiry is precisely the case where
-- two nodes both run it: the lock is released by time rather than by the holder finishing, and the
-- holder is still working. Without a key to conflict on, that run doubles every member's inbox.
-- With one, `INSERT ... ON CONFLICT (user_id, week_start) DO NOTHING` makes the second run free —
-- the same reasoning collapse step 11 applies to its notification dedup key.
--
-- THE WEEK IS THE RECIPIENT'S, NOT THE CONTAINER'S (readiness gap row 15). The trigger is a UTC
-- cron because the container clock is UTC by policy (§1.6, §5.6); the DATE stored here is the
-- Monday of the recipient's own week, computed from `users.timezone` through `UserClock`. Two
-- members of one household in America/Vancouver and America/Toronto get different `week_start`
-- values from the same run, which is the whole point of the column being a DATE rather than a
-- timestamp: it is a calendar fact about somebody's week, not an instant.
--
-- `delivered` / `delivered_at` say whether the note has been handed to the member anywhere beyond
-- `GET /v1/insights`. Reading that route does NOT set them — a GET stays safe — so the flag is the
-- seam collapse step 11's notification channel writes when it delivers one. Until that step lands
-- nothing sets it, and the partial index below is the query it will use.
--
-- The source's partial index survives, with the table prefix §3's naming rule requires
-- (`idx_coach_insight_user_undelivered` -> `coach_insight_undelivered_idx`) and without the
-- redundant `delivered` column in the key, which the WHERE clause already fixes to false.
--
-- No money or numeric column here (the amounts a note discusses are inside the encrypted prose),
-- so `SchemaDefaultsIT` needs no allowlist entry for this table.

CREATE TABLE coach_insight (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    week_start        DATE        NOT NULL,
    content_encrypted TEXT        NOT NULL,
    delivered         BOOLEAN     NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at      TIMESTAMPTZ NULL,

    CONSTRAINT coach_insight_user_week_key UNIQUE (user_id, week_start)
);

-- What a delivery channel asks for: this member's undelivered notes, newest first.
CREATE INDEX coach_insight_undelivered_idx
    ON coach_insight (user_id, created_at DESC)
    WHERE delivered = false;

COMMENT ON TABLE coach_insight IS
    'One weekly coach note per member per week. UNIQUE(user_id, week_start) is the idempotence '
    'guard for the weekly-insight job: a second run after a ShedLock lockAtMostFor expiry inserts '
    'nothing rather than doubling every member''s inbox.';
COMMENT ON COLUMN coach_insight.week_start IS
    'The Monday of the RECIPIENT''s week, from users.timezone via UserClock — never the '
    'container''s week (gap row 15).';
COMMENT ON COLUMN coach_insight.content_encrypted IS
    'AES-GCM ciphertext from FieldEncryptionService, with its v1: prefix (gap row 44). A sentence '
    'naming what a member spent on is that member''s ledger.';
