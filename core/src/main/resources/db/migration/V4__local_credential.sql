-- V4 — local_credential (collapse plan §3 V4, gaps P2-4 / P2-5).
--
-- One row per user that has a password. A user who signs in only through OIDC has no row here,
-- which is also what makes "this account cannot be password-brute-forced" expressible.
--
-- password_hash is a full argon2id PHC string ($argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>), so
-- the cost parameters travel with each hash and a future re-tune re-hashes on the owner's next
-- successful login instead of invalidating every password (P2-4).
--
-- failed_attempts / locked_until are the ACCOUNT side of the brute-force control. The per-IP
-- bucket is collapse step 4's RateLimitFilter; login_attempt (V8) is the evidence table both read.

CREATE TABLE local_credential (
    user_id             UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    password_hash       TEXT        NOT NULL,
    password_updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    failed_attempts     INT         NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,

    CONSTRAINT local_credential_failed_attempts_check CHECK (failed_attempts >= 0)
);

COMMENT ON COLUMN local_credential.locked_until IS
    'Soft lock. The login response never distinguishes locked-out from wrong-password (§1.5).';
