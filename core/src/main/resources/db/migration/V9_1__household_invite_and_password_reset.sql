-- V9.1 — household_invite and password_reset_token.
--
-- NEITHER TABLE IS IN THE COLLAPSE PLAN'S §3 V-LIST. That is a gap in the plan, not a deviation
-- from it: §1.5 and §4.1 require single-use 72-hour invite links (gap P2-7) and admin-generated
-- single-use password-reset links (gap P2-8), and §3 gives neither of them a table. They are
-- numbered V9.1 rather than V10 so that collapse step 4 can still land §3's V10 (user_preferences)
-- through V13 unchanged, and so that the ordering constraint (both FK `users`, created in V3) is
-- obvious from the number.
--
-- Both tables store only a SHA-256 hash of the token. The raw token exists once, in the response
-- an admin copies, and never again — the same reason refresh_token (V7) stores a hash. An operator
-- with a database dump can revoke an invite; they cannot use one.

CREATE TABLE household_invite (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id     UUID         NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    -- Optional: an invite may be addressed to one address, or be a generic link the admin pastes
    -- into a channel of their choosing. When set, registration must use this exact address.
    email            VARCHAR(320),
    role             VARCHAR(10)  NOT NULL DEFAULT 'MEMBER',
    -- VARCHAR, not CHAR: Postgres CHAR(n) blank-pads on retrieval, and Hibernate's
    -- ddl-auto: validate maps a length-bounded String to varchar. A CHAR column here
    -- fails schema validation at boot, which is the check doing its job.
    token_hash       VARCHAR(64)  NOT NULL UNIQUE,
    created_by       UUID         REFERENCES users(id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    accepted_at      TIMESTAMPTZ,
    accepted_user_id UUID         REFERENCES users(id) ON DELETE SET NULL,
    revoked_at       TIMESTAMPTZ,

    CONSTRAINT household_invite_role_check CHECK (role IN ('MEMBER', 'ADMIN'))
);

CREATE INDEX household_invite_household_id_idx ON household_invite (household_id);
CREATE INDEX household_invite_expires_at_idx ON household_invite (expires_at);

COMMENT ON TABLE household_invite IS
    'Single-use, time-boxed invitation to join one household. No SMTP required: the admin copies the URL (gap P2-7).';

CREATE TABLE password_reset_token (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    -- Who issued it: an ADMIN for the no-SMTP path, NULL for a self-service email request.
    created_by   UUID        REFERENCES users(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ
);

CREATE INDEX password_reset_token_user_id_idx ON password_reset_token (user_id);

COMMENT ON TABLE password_reset_token IS
    'One-hour, single-use reset link. A successful reset revokes every refresh_token for that user (§1.5, gap P2-8).';
