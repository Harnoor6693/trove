-- V3 — users (collapse plan §3 V3, §1.5 tenancy).
--
-- Every user belongs to exactly one household (owner decision 6: one shared household ledger per
-- install). household_id is set once at registration, invite-accept or first-run bootstrap and is
-- never user-editable; §1.5 is explicit that a member cannot move themselves between households.
--
-- currency/country/timezone/locale live HERE and not on user_preferences (V10). They are
-- identity-level: five other packages (DashboardController, ParsingStage, TaxSplitStage,
-- ChatController, WeeklyInsightScheduler) read users.currency/users.country directly, and the
-- collapse plan's own DATABASE review finding 9 moved them onto this table precisely because
-- leaving them on user_preferences pointed those reads at columns that would not exist.
--
-- onboarding_completed is here for the same reason: the web AuthGuard reads it on every route
-- change (§6), so it belongs on the row every request already loads rather than behind a second
-- table and a second query. V10's user_preferences is therefore redundant at collapse step 4:
-- every field it was going to hold (region, locale, timezone, onboarding_completed) now lives
-- here. See the step-3 notes in docs/recovery/06-PROGRESS.md.

CREATE TABLE users (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id         UUID         NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    role                 VARCHAR(10)  NOT NULL DEFAULT 'ADMIN',
    email                VARCHAR(320) NOT NULL,
    display_name         VARCHAR(100) NOT NULL,
    currency             VARCHAR(3)   NOT NULL,
    country              VARCHAR(2)   NOT NULL,
    timezone             VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    locale               VARCHAR(10)  NOT NULL DEFAULT 'en-CA',
    region               VARCHAR(3),
    onboarding_completed BOOLEAN      NOT NULL DEFAULT false,
    is_active            BOOLEAN      NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT users_role_check     CHECK (role IN ('MEMBER', 'ADMIN')),
    -- The closed set of finance.trove.common.domain.CurrencyCode / Jurisdiction (owner
    -- decision 3). No DEFAULT of any kind: §9 risk 5 is that a defaulted currency column is how
    -- 'CAD' silently becomes every row's answer.
    CONSTRAINT users_currency_check CHECK (currency IN ('CAD', 'USD')),
    CONSTRAINT users_country_check  CHECK (country IN ('CA', 'US')),
    CONSTRAINT users_locale_check   CHECK (locale IN ('en-CA', 'en-US')),
    CONSTRAINT users_email_not_blank CHECK (btrim(email) <> ''),
    CONSTRAINT users_display_name_not_blank CHECK (btrim(display_name) <> '')
);

-- Case-insensitive uniqueness. Email is matched with lower(email) = lower(?) everywhere, so the
-- index is both the constraint and the lookup path (§1.5 enumeration-safe login does exactly one
-- indexed probe whether or not the user exists).
CREATE UNIQUE INDEX users_email_lower_idx ON users (lower(email));
CREATE INDEX users_household_id_idx ON users (household_id);

COMMENT ON COLUMN users.role IS
    'Household-scoped role, MEMBER or ADMIN. At least one ADMIN per household is enforced in the service layer on demote and on delete.';
COMMENT ON COLUMN users.region IS
    'Optional sub-national reporting region (a province or state code). Nullable: most installs never set it, and nothing computes a tax number from it (owner decision 2).';
COMMENT ON COLUMN users.currency IS
    'Reporting currency, set once at registration. Every historical transaction, budget and export assumes it is stable.';

-- Self-serve signup policy (§4.2 GET/PUT /v1/admin/settings {registrationOpen}). The collapse
-- plan names the endpoint but never gives the flag a column; under one-household-per-install the
-- household row is where it belongs, and putting it here keeps the whole auth story inside
-- V3-V9 rather than borrowing a number from a later step. Seeded from TROVE_REGISTRATION_OPEN by
-- FirstRunBootstrap, flipped at runtime by an ADMIN.
ALTER TABLE households ADD COLUMN registration_open BOOLEAN NOT NULL DEFAULT false;
