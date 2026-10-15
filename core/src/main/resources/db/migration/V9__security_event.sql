-- V9 — security_event (collapse plan §3 V9, gap P2-17).
--
-- Append-only. The application's database role is granted INSERT and SELECT only; nothing in the
-- code issues an UPDATE or a DELETE against this table, which is what makes it usable as evidence
-- after an incident rather than merely as telemetry.
--
-- user_id is the SUBJECT of the event, actor_id whoever caused it. They differ exactly when an
-- admin acts on a member — clearing their TOTP, unlocking them, issuing a reset link — and that
-- distinction is the point: "the second factor was removed from this account" and "this account
-- removed its own second factor" are different stories. Both are ON DELETE SET NULL so deleting a
-- user redacts the reference without erasing the history.
--
-- The type list is §3's fourteen values plus the four this step actually needs and §3 did not
-- name: INVITE_CREATED / INVITE_ACCEPTED (finer than §3's single ADMIN_INVITE),
-- REFRESH_REUSE_DETECTED (§1.5's family-revoke signal, which had nowhere to be recorded) and
-- PASSWORD_RESET (distinct from a voluntary PASSWORD_CHANGE).
--
-- metadata is JSONB for the small event-specific bag (which session, which invite, which family).
-- It must never carry a credential, a token, or a raw request URI (§2.1: route templates only).

CREATE TABLE security_event (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    actor_id   UUID REFERENCES users(id) ON DELETE SET NULL,
    type       VARCHAR(40)  NOT NULL,
    ip         INET,
    ua_summary VARCHAR(200),
    metadata   JSONB,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT security_event_type_check CHECK (type IN (
        'LOGIN_OK', 'LOGIN_FAIL', 'LOCKOUT',
        'TOTP_ENROL', 'TOTP_DISABLE',
        'PASSWORD_CHANGE', 'PASSWORD_RESET',
        'SESSION_REVOKE', 'REFRESH_REUSE_DETECTED',
        'BANK_CONNECTION_ADD', 'BANK_CONNECTION_REMOVE',
        'EXPORT', 'ACCOUNT_DELETE',
        'ADMIN_INVITE', 'INVITE_CREATED', 'INVITE_ACCEPTED',
        'KEY_ROTATE', 'EGRESS_SETTING_CHANGE'))
);

CREATE INDEX security_event_user_id_created_at_idx ON security_event (user_id, created_at DESC);
CREATE INDEX security_event_type_created_at_idx ON security_event (type, created_at DESC);
