-- Collapse step 15 (docs/recovery/06-COLLAPSE-PLAN.md §3 V41, §2.9, §4.12, §6 "Tax export").
--
-- Re-based V-number: §3 prints this table as V41 under the plan's ORIGINAL numbering. Only the
-- columns and constraints come from that row; the number is the next free one at landing time
-- (§3's re-basing note). V10 (user_preferences, step 4) is REDUNDANT and stays UNUSED —
-- preferences live on `users` (V3).
--
-- THIS TABLE IS USER-SCOPED, NOT HOUSEHOLD-SCOPED, and that is the deliberate exception to the
-- rule every other ledger table follows (§1.5, §3, §6): a tax filing is individual even inside a
-- shared household ledger, so `user_id` is the SCOPE here, not a "created by" audit column. A
-- household mate must not see, download or delete another member's run — the opposite of every
-- route steps 6-10 added, and the reason `TaxExportRunRepository` extends `UserOwnedRepository`
-- rather than `UserScopedRepository`. The ROWS inside the file still come from the household's
-- shared ledger; only the run does not.
--
-- DROPPED from the source table (services/report-service V1__report.sql):
--   * `report_type` and the whole `ReportType` enum — MONTHLY/ANNUAL/CUSTOM are out of scope
--     (§2.9 deletes all three plus the AI narrative). The only export this table records is the
--     tax-year one.
--   * `requested_filters JSONB` — a column nothing ever wrote (inventory report-service §4,
--     defect RS-8), together with the `ReportFilters` record that was supposed to fill it.
--   * `period_start` / `period_end` — the tax year IS the period. `tax_year` plus
--     `LocalDate.of(year, 1, 1) .. LocalDate.of(year, 12, 31)` reproduces them exactly, and two
--     columns that can disagree with the year they were derived from cannot.
--
-- CHANGED from the source:
--   * `file_size_bytes` is BIGINT, not the source's INTEGER: a full-year PDF for a busy ledger is
--     not bounded by 2 GB in any argued way, and the entity field is a `long`.
--   * `file_key` holds a `LocalDiskStorage` key of the form `exports/<householdId>/<uuid>.<ext>`,
--     NOT a MinIO object name — MinioConfig/MinioStorageService are deleted, not moved (RS-7:
--     presigned URLs signed against internal Docker DNS were unreachable from any browser). The
--     column name is unchanged so the shape stays recognisable. The second path segment is the
--     HOUSEHOLD id because that is what `LocalDiskStorage.usage(householdId)` accounts against;
--     ownership of the run lives on `user_id` here, which is what the download route checks.
--
-- NOT ADDED: the source's partial index on `status`. Nothing queries by bare status (inventory
-- §4 records it as dead), and the only thing that would — an export-retention sweep — is
-- deliberately out of scope for this step (§5 item 6 lists no ShedLock name for it). An index
-- nobody reads is a write cost with no reader; the sweep's step adds it with its own query.
--
-- No money column: `file_size_bytes` is a byte count, not currency, so `SchemaDefaultsIT` needs
-- no allowlist entry for this table.

CREATE TABLE tax_export_run (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tax_year        INTEGER       NOT NULL,
    jurisdiction    VARCHAR(2)    NOT NULL CHECK (jurisdiction IN ('CA', 'US')),
    format          VARCHAR(5)    NOT NULL CHECK (format IN ('CSV', 'PDF')),
    status          VARCHAR(12)   NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'GENERATING', 'COMPLETE', 'FAILED')),
    file_key        TEXT          NULL,
    file_size_bytes BIGINT        NULL,
    error_message   TEXT          NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- The list route's only query: this member's runs, newest first.
CREATE INDEX tax_export_run_user_id_idx ON tax_export_run (user_id, created_at DESC);
