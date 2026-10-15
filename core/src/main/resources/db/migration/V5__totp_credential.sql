-- V5 — totp_credential (collapse plan §3 V5, gap P2-6).
--
-- RFC 6238, SHA-1, 6 digits, 30-second step, +/-1 step of drift.
--
-- secret_encrypted is AES-GCM ciphertext produced by FieldEncryptionService with
-- TROVE_ENCRYPTION_KEY — never the raw base32 secret, because a database dump would otherwise
-- hand over every second factor in the install.
--
-- recovery_codes_hashed holds SHA-256 hashes of the ten single-use codes. A code is consumed by
-- removing its hash from the array, so "single-use" is a property of the data rather than of a
-- flag someone has to remember to set.
--
-- enabled stays false until one live code has been verified (verify-before-enable, P2-6): the
-- failure mode this prevents is a user who scans a QR badly, enables TOTP, and locks themselves
-- out of a finance server with no reset path.
--
-- last_used_step is the replay guard P2-6 asks for: the same 6 digits inside one 30-second window
-- are accepted exactly once, so a code shoulder-surfed or captured from a proxy cannot be
-- resubmitted while it is still numerically valid.

CREATE TABLE totp_credential (
    user_id               UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    secret_encrypted      TEXT        NOT NULL,
    enabled               BOOLEAN     NOT NULL DEFAULT false,
    recovery_codes_hashed TEXT[]      NOT NULL DEFAULT '{}',
    last_used_step        BIGINT,
    confirmed_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT totp_credential_enabled_requires_confirmation
        CHECK (enabled = false OR confirmed_at IS NOT NULL)
);

COMMENT ON COLUMN totp_credential.last_used_step IS
    'Highest RFC 6238 time step already accepted for this user; blocks replay inside one window.';
