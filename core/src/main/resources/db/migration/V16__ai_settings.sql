-- V16 — ai_settings (collapse plan §3's V37 row, renumbered).
--
-- RENUMBERING NOTE: §3 drafted this table as V37 and ai_audit_log as V40, under a numbering that
-- assumed collapse steps 6-11 had already taken V16-V36. In this wave they have not: step 4 holds
-- V11-V13, step 5 holds V14-V15, and Flyway runs with out-of-order=false, so the numbers have to
-- be dense and collision-free at merge time. This step therefore takes V16 and V17. §3's V-list
-- and step 6's V16-V20 range must be re-based above V17.
--
-- NOTE on V10: the plan's V10 (user_preferences) is REDUNDANT and stays UNUSED — preferences live
-- on `users` (V3). Nothing here depends on it.
--
-- WHY THE PRIMARY KEY IS user_id AND THERE IS NO household_id.
-- Every ledger table in this schema is scoped by household_id, because the owner's decision is one
-- shared ledger per install. This table is the deliberate opposite. The AI endpoint, its API key
-- and the privacy tier are configured by each member individually (owner decision 9), so a
-- household mate — administrator included — must not be able to read or write another member's
-- row. Making user_id the primary key rather than a foreign key beside a surrogate id is what
-- makes that structural: there is no id by which a row can be addressed without naming its owner,
-- and the repository extends UserKeyedRepository, whose every finder takes a user id.
--
-- api_key_enc is AES-GCM ciphertext from FieldEncryptionService, carrying its `v1:` version
-- prefix — never the raw key. A database dump of this table must not be a set of working API
-- credentials. The column is write-only across the whole API surface: no response schema in
-- openapi/trove-core.yaml mentions it, and the settings response carries `has_api_key` as a
-- boolean instead.
--
-- allow_private_endpoint is this member's OWN opt-in to a private, loopback or link-local target.
-- It is one of two keys: TROVE_EGRESS_ALLOW_PRIVATE is the install's "is a LAN target allowed here
-- at all" switch, and OutboundUrlPolicy permits a private destination only when both are true
-- (§1.6, §6, gap P2-10). A LAN Ollama is the primary AI story, so a blanket block would be wrong;
-- the endpoint is per-user, so an instance-wide switch alone would be too coarse.
--
-- privacy_tier is the member's statement about how far their own data may travel: LOCAL_ONLY means
-- the endpoint is treated as a private model and payloads classified as sensitive may be sent to
-- it, CLOUD_OK means the member accepts it is somebody else's server and those payloads are held
-- back. It governs what this server SENDS; it is not a claim about what the endpoint does.
--
-- The timeouts are per user because a local model on modest hardware legitimately needs longer
-- than a cloud one. They are what was ASKED for; the effective value is capped at call time by
-- TROVE_HTTP_CONNECT_TIMEOUT_MS and by a bounded multiple of TROVE_HTTP_READ_TIMEOUT_MS, so a
-- stored value can never hold a request thread longer than the install allows.

CREATE TABLE ai_settings (
    user_id                UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    enabled                BOOLEAN      NOT NULL DEFAULT false,
    endpoint_url           TEXT,
    api_key_enc            TEXT,
    model                  VARCHAR(200),
    vision_model           VARCHAR(200),
    allow_private_endpoint BOOLEAN      NOT NULL DEFAULT false,
    privacy_tier           VARCHAR(16)  NOT NULL DEFAULT 'CLOUD_OK',
    connect_timeout_ms     INTEGER      NOT NULL DEFAULT 5000,
    read_timeout_ms        INTEGER      NOT NULL DEFAULT 120000,
    last_test_at           TIMESTAMPTZ,
    last_test_ok           BOOLEAN,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ai_settings_privacy_tier_check
        CHECK (privacy_tier IN ('LOCAL_ONLY', 'CLOUD_OK')),

    -- Enabling a feature that cannot run is a configuration error, and the database is the last
    -- place it can be caught: the service refuses it first with a 400, but a future caller that
    -- writes this table by another path must not be able to leave a member with AI "on" and
    -- nothing to call.
    CONSTRAINT ai_settings_enabled_requires_endpoint_and_model
        CHECK (enabled = false OR (endpoint_url IS NOT NULL AND model IS NOT NULL)),

    CONSTRAINT ai_settings_connect_timeout_range
        CHECK (connect_timeout_ms BETWEEN 100 AND 60000),
    CONSTRAINT ai_settings_read_timeout_range
        CHECK (read_timeout_ms BETWEEN 1000 AND 600000)
);

COMMENT ON TABLE ai_settings IS
    'Per-user AI endpoint configuration. Deliberately NOT household-scoped: a household mate must '
    'not be able to read or write another member''s endpoint, key or privacy tier (owner decision 9).';
COMMENT ON COLUMN ai_settings.api_key_enc IS
    'AES-GCM ciphertext from FieldEncryptionService, with its v1: version prefix. Write-only: no '
    'API response ever carries it, in any form.';
COMMENT ON COLUMN ai_settings.allow_private_endpoint IS
    'This member''s own opt-in to a private/loopback/link-local target. Effective only when the '
    'install also sets TROVE_EGRESS_ALLOW_PRIVATE; OutboundUrlPolicy requires both.';
