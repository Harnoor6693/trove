-- V41 — data_export_run (collapse step 18; gap row 16; §4.2, §6 "Local accounts + households").
--
-- The plan's §3 drafts NO table for this step: it predates the full-data-export row entirely.
-- This table is designed here from gap row 16 and the merged schema, and it is deliberately a
-- LINE-BY-LINE SIBLING of V35__tax_export_run.sql — same status vocabulary, same file_key /
-- file_size_bytes / error_message / created_at / updated_at, same index shape — so a reviewer can
-- check it by diffing the two rather than by reading both. Only three things differ, and each has
-- a reason below.
--
-- THIS TABLE IS USER-SCOPED, NOT HOUSEHOLD-SCOPED, for exactly the reason V35 is: the archive is
-- a copy of the CALLER'S OWN account and a household mate must not see, download or delete it. So
-- `user_id` is the SCOPE, not a "created by" audit column, and `DataExportRunRepository` extends
-- `UserOwnedRepository` rather than `UserScopedRepository`. The ROWS inside the archive still come
-- from the household's shared ledger (§1.5); only the run does not.
--
-- DIFFERENCE 1 — `household_id` is on the row and V35's is not. It is NOT a second scope and no
-- finder may key on it. It is here because `LocalDiskStorage.put(area, householdId, ...)` takes
-- the household id as its second argument and generates a THREE-segment key
-- `exports/<householdId>/<uuid>.zip` — there is no per-user segment and one may not be added,
-- because `pathOf` refuses any key that is not three segments and that refusal IS the traversal
-- guard. V35 gets away without the column because its generation is handed the household id as a
-- parameter and nothing later needs it; this table's SWEEP and the account purge both have to
-- resolve a row to its file with no request in hand, so the row has to carry it.
--
-- DIFFERENCE 2 — `expires_at`. Gap row 16 asks for "a single 24-hour download link", and this
-- step ENFORCES it rather than deferring it: a download after this instant answers the same 409
-- as an unfinished run, and `DataExportSweep` deletes the file from the exports area and clears
-- `file_key` and `file_size_bytes`. An archive of somebody's whole financial life must not sit on
-- a volume indefinitely because nobody fetched it. It is NULL until the run completes, because
-- until then there is nothing to expire.
--
-- DIFFERENCE 3 — no `tax_year`, `jurisdiction` or `format`. There is one archive shape and it
-- covers the member's whole ledger history, so there is nothing for a caller to choose and
-- nothing to stamp. The one-per-day cap is enforced in the service against `created_at` over the
-- member's OWN local day (`UserClock`), not in the database and not in the rate limiter, which
-- buckets by path and client address and therefore cannot express "per member".
--
-- ON DELETE CASCADE on both foreign keys: when the purge job erases a member the run rows go with
-- them. The FILES do not — no cascade reaches a filesystem — so `AccountPurgeJob` reads each
-- row's `file_key` and calls `LocalDiskStorage.delete` BEFORE the row is deleted. That ordering is
-- the whole reason this comment exists.
--
-- No money column: `file_size_bytes` is a byte count, so `SchemaDefaultsIT` needs no allowlist
-- entry for this table.

CREATE TABLE data_export_run (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    household_id    UUID          NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    status          VARCHAR(12)   NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'GENERATING', 'COMPLETE', 'FAILED')),
    file_key        TEXT          NULL,
    file_size_bytes BIGINT        NULL,
    error_message   TEXT          NULL,
    expires_at      TIMESTAMPTZ   NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- The list route's only query, and the one-per-day cap's: this member's runs, newest first.
CREATE INDEX data_export_run_user_id_idx ON data_export_run (user_id, created_at DESC);

-- The expiry sweep's query: completed runs whose link has lapsed and whose file is still there.
-- Partial, because the sweep never looks at a run that has no file, and that is most rows once a
-- volume has been running for a while.
CREATE INDEX data_export_run_expiry_idx ON data_export_run (expires_at)
    WHERE file_key IS NOT NULL;

-- A FLOOD ceiling for the route, seeded here for the reason V12's own header gives for seeding
-- rules ahead of the steps that serve them: "those steps cannot land an expensive endpoint with
-- no ceiling on it". Without a row of its own this route falls through to V12's catch-all
-- ('/api/**', 600, 1200), which is the ceiling for a cheap read.
--
-- It is NOT the one-per-day cap and cannot be. RateLimitFilter buckets by endpoint pattern and
-- client address, so it cannot say "per member" at all: it would refuse a household sharing one
-- connection and let one member with two networks through twice. The cap is enforced in
-- DataExportRunStarter behind a household-row lock. This row is the other control, against the
-- other attack -- a client hammering the route -- and the two are layered on purpose, exactly as
-- V12 layers the login flood ceiling over the per-address lockout.
--
-- The pattern is the exact collection path, so it covers POST (start a run) and GET (list them)
-- and deliberately not `/v1/me/export/{id}` or its download: polling a run is cheap, and a poll
-- ceiling low enough to matter would break the very clients the 202 pattern asks to poll. 30 a
-- minute with a burst of 60 is the number `/api/v1/receipts/upload` already carries, the nearest
-- comparable authenticated write.
INSERT INTO rate_limit_config (endpoint_pattern, requests_per_min, burst_capacity) VALUES
    ('/api/v1/me/export', 30, 60);
