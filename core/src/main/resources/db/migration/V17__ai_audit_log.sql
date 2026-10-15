-- V17 — ai_audit_log (collapse plan §3's V40 row, renumbered to V17; see the note in V16).
--
-- Moved from services/model-manager V2 with three changes §3 asks for:
--   * user_id is a real, nullable foreign key with ON DELETE SET NULL. In the source it was a bare
--     UUID column with no constraint, so a deleted account left rows pointing at nothing and a
--     typo pointed at nobody. Nullable rather than CASCADE because the egress record is the thing
--     that outlives the account: "this install sent data to this host" stays true after the member
--     who configured it is gone, and the row keeps its host and its timestamp without keeping the
--     identity.
--   * endpoint_host replaces the source's provider/alias pair. There is exactly one
--     OpenAI-compatible client now and no multi-provider router, so "which provider" is not a
--     question this schema can be asked; "which host did my data go to" is the question owner
--     decision 9 says a per-user egress log must answer.
--   * the indexes are named ai_audit_log_* rather than idx_audit_log_*, which collided by name
--     with the audit_log indexes of another service once both live in one catalog.
--
-- WHAT MUST NEVER BE IN THIS TABLE: the API key, the prompt, the completion, the receipt image, or
-- any part of a response body. This is a record THAT a call happened, not OF what it carried.
-- error_message holds the typed slug and a short reason the server itself composed — never the
-- endpoint's own response body, which can quote the prompt back.

CREATE TABLE ai_audit_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID REFERENCES users (id) ON DELETE SET NULL,
    operation     VARCHAR(20)  NOT NULL,
    endpoint_host VARCHAR(255) NOT NULL,
    model_id      VARCHAR(200),
    privacy_tier  VARCHAR(16)  NOT NULL,
    prompt_tokens INTEGER,
    output_tokens INTEGER,
    latency_ms    BIGINT       NOT NULL,
    success       BOOLEAN      NOT NULL DEFAULT true,
    error_message TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ai_audit_log_operation_check
        CHECK (operation IN ('CHAT', 'CHAT_STREAM', 'VISION', 'MODELS', 'TEST')),
    CONSTRAINT ai_audit_log_privacy_tier_check
        CHECK (privacy_tier IN ('LOCAL_ONLY', 'CLOUD_OK'))
);

CREATE INDEX ai_audit_log_user_id_created_at_idx ON ai_audit_log (user_id, created_at DESC);
CREATE INDEX ai_audit_log_created_at_idx ON ai_audit_log (created_at DESC);

COMMENT ON TABLE ai_audit_log IS
    'One row per outbound AI call: which member, which host, which model, how long, and whether it '
    'worked. Never the prompt, the completion, the image or the API key.';
