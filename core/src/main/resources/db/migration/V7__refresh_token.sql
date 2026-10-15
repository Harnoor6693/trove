-- V7 — refresh_token (collapse plan §3 V7; replaces the Valkey RefreshTokenStore).
--
-- Refresh is per-USER, not per-household: household_id is deliberately absent. A session is an
-- individual member's, and a household admin does not inherit a member's sessions.
--
-- token_hash is SHA-256 hex of the opaque token — 64 characters inside the VARCHAR(128) §3
-- specifies. The raw token exists only in the response body and the client's cookie/keystore, so a
-- database dump yields nothing that can be replayed.
--
-- family_id groups every token descended from one login. Rotation on every use means a stolen
-- token is only useful until the legitimate client refreshes once; the theft is then detectable,
-- because the stale token comes back and no honest client would ever send it. Detecting that
-- revokes the whole family (§1.5) and writes a REFRESH_REUSE_DETECTED security_event.
--
-- replaced_by is intentionally an unconstrained UUID rather than a self-FK: §3 specifies the
-- column without REFERENCES, and a self-referencing FK would make the daily purge of expired rows
-- order-dependent for no benefit — the value is forensic, not navigational.
--
-- last_used_at drives the idle timeout that runs alongside the absolute lifetime (§1.5): a session
-- dies either 30 days after it started or 14 days after it was last used, whichever comes first.

CREATE TABLE refresh_token (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash   VARCHAR(128) NOT NULL UNIQUE,
    family_id    UUID         NOT NULL,
    issued_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    replaced_by  UUID,
    ip_address   VARCHAR(45),
    user_agent   VARCHAR(500)
);

CREATE INDEX refresh_token_user_id_expires_at_idx ON refresh_token (user_id, expires_at);
CREATE INDEX refresh_token_family_id_idx ON refresh_token (family_id);

COMMENT ON COLUMN refresh_token.family_id IS
    'All tokens descended from one login. Reuse of a rotated token revokes the whole family.';
