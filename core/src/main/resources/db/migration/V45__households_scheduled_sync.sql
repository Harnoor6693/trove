-- V45 — the scheduled bank sync switch (C-01r).
--
-- One install-wide flag, on the household row beside registration_open (V3) and for the same
-- reason: with one household per install the household IS the install, and an admin's runtime
-- decision must survive a restart rather than revert to whatever an environment file says.
--
-- FALSE by default, and for existing installs too. A server that starts calling a third party on
-- its own schedule is something an admin turns on, never something an upgrade does to them.
-- ScheduledSyncJob reads it on every run; PUT /v1/admin/settings is the only writer.
ALTER TABLE households ADD COLUMN scheduled_sync_enabled BOOLEAN NOT NULL DEFAULT FALSE;
