-- V8 — login_attempt (collapse plan §3 V8, gap P2-5).
--
-- The evidence table behind the account lockout and (from collapse step 4) the per-IP bucket.
--
-- email_hash, not email: this table is written on every failed login, including logins for
-- addresses that do not exist on the install. Storing the raw address would turn the brute-force
-- log into a list of every address anyone has ever guessed at this server. SHA-256 hex of the
-- lowercased address is enough to count attempts per account without keeping the account.
--
-- ip is INET rather than text so a future per-subnet query is an index range rather than a LIKE.
-- The value always comes from request.getRemoteAddr() after the trusted-proxy filter — raw
-- X-Forwarded-For is never parsed by hand (§1.5, §5.1); a client that appends its own hop cannot
-- move itself into a different bucket.
--
-- Written through JdbcTemplate, not JPA: the table is append-only, is queried only by aggregate,
-- and INET has no clean Hibernate mapping that survives ddl-auto: validate.

CREATE TABLE login_attempt (
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email_hash CHAR(64)    NOT NULL,
    ip         INET,
    success    BOOLEAN     NOT NULL,
    at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX login_attempt_email_hash_at_idx ON login_attempt (email_hash, at);
CREATE INDEX login_attempt_ip_at_idx ON login_attempt (ip, at);
