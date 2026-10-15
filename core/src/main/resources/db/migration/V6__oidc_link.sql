-- V6 — oidc_link (collapse plan §3 V6, §1.5 optional OIDC).
--
-- (provider, provider_sub) is the identity an IdP asserts; it is unique across the install, so
-- one IdP account can never be linked to two Trove users.
--
-- email_at_link records the address the ID token carried at link time. It is deliberately NOT the
-- lookup key: auto-linking on email alone is an account takeover — anyone who controls an IdP
-- account whose (unverified) email matches a household member logs in as that member with full
-- ledger access. The handler therefore requires email_verified=true before it will auto-link, and
-- this column exists so an operator can later see which address was trusted (§1.5, §9 risk 27).

CREATE TABLE oidc_link (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider      VARCHAR(50)  NOT NULL,
    provider_sub  VARCHAR(255) NOT NULL,
    email_at_link VARCHAR(320),
    linked_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT oidc_link_provider_sub_key UNIQUE (provider, provider_sub)
);

CREATE INDEX oidc_link_user_id_idx ON oidc_link (user_id);
