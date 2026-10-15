-- V2 — households (collapse plan §3, owner decision 6: one shared household ledger per install).
--
-- This is the scoping root of the whole schema. Every ledger-owned table added from V16 onward
-- carries household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE, and every
-- repository over such a table filters by household_id rather than user_id. It is created here,
-- at the very bottom of the history, precisely because retrofitting tenancy later is expensive
-- (§9 risk 29).
--
-- users.household_id (V3) is what binds a member to a household; there is no membership table.

CREATE TABLE households (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT households_name_not_blank CHECK (btrim(name) <> '')
);

COMMENT ON TABLE households IS
    'Scoping root for all ledger data. One row per install under the shared-household model.';
