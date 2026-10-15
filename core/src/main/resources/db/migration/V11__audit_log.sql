-- V11 — audit_log (collapse plan §3 V11, §4.2, gap P2-17).
--
-- V10 IS DELIBERATELY UNUSED AND WILL STAY THAT WAY. §3 reserved V10 for a `user_preferences`
-- table carried over from the api-gateway. Collapse step 3 merged those columns onto `users`
-- (V3) instead, because a one-row-per-user side table that is always read with its owner buys a
-- join and nothing else. Flyway runs with out-of-order=false, so the number is simply skipped
-- rather than reused: a V10 added later would be refused on every install that already ran V11,
-- which is exactly the protection wanted. Do not write a V10.
--
-- One row per MUTATING request (POST/PUT/PATCH/DELETE). Reads are not recorded: they would
-- dominate the table by an order of magnitude and the question §4.2 asks of it is "what changed,
-- and who changed it".
--
-- WHAT THIS TABLE MUST NEVER HOLD, and why each column is shaped the way it is:
--
--   path   is the matched ROUTE TEMPLATE ('/api/v1/admin/users/{id}'), never the raw URI. Two
--          routes on this application carry a live credential in the path — the anonymous invite
--          preview above all — and a raw URI here would be a second, permanent, plaintext copy of
--          every invitation token the install has ever issued, defeating the property V9_2's
--          encrypted token column exists to preserve. Same rule as security_event.metadata and
--          the request log (§2.1). No body, no headers, no query string, no token, ever.
--
--   user_id is an UNCONSTRAINED, indexed UUID — no foreign key, deliberately (§10 finding 15).
--          The row is written after the response has been sent, on a bounded background executor;
--          a hard FK would drop the audit row precisely when the token's subject no longer
--          resolves (a deleted account, a stale token), which is the one moment the evidence
--          matters most. Reconciliation belongs in a nightly integrity check, not in a live
--          constraint on the write path.
--
--   household_id is unconstrained for the same reason, and it is what makes GET /v1/audit
--          household-scoped: an admin reads their own household's log and no other's.
--
--   client_ip is INET, and always comes from request.getRemoteAddr() after the trusted-proxy
--          filter (§1.5, §5.1) — raw X-Forwarded-For is never parsed by hand, so a client that
--          appends its own hop cannot forge the address recorded against its own request.
--
-- RETENTION. Rows are swept after one year by the `audit-log-purge` job (PurgeJobs), on the same
--          ShedLock-guarded 03:xx schedule as the other four sweeps. Without it this table would
--          be the fifth append-only table on the install that grows for ever, which is the exact
--          problem that job class was written to fix — and `GET /v1/audit` pages over it with a
--          count(*), so the read path degrades with the same growth.
--
-- Written through JdbcTemplate, not JPA, for the same reasons as security_event and
-- login_attempt: append-only, never updated, and INET has no clean Hibernate mapping that
-- survives ddl-auto: validate.

CREATE TABLE audit_log (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID,
    household_id UUID,
    method       VARCHAR(10)  NOT NULL,
    path         VARCHAR(500) NOT NULL,
    status       INTEGER      NOT NULL,
    duration_ms  BIGINT       NOT NULL,
    client_ip    INET,
    request_id   UUID         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The read path of GET /v1/audit: one household, newest first, paged.
CREATE INDEX audit_log_household_id_created_at_idx ON audit_log (household_id, created_at DESC);
-- §3 V11 names these two by hand; "everything I did" and "everything since" are the two
-- questions an incident review asks that the composite index above does not answer.
CREATE INDEX audit_log_user_id_idx ON audit_log (user_id);
CREATE INDEX audit_log_created_at_idx ON audit_log (created_at DESC);
