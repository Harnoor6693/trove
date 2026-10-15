-- Collapse step 13 — the AI coach's conversations
-- (docs/recovery/06-COLLAPSE-PLAN.md §3's V38 row, §2.10, §4.11, §6 "AI settings + coach";
--  source: services/model-manager V3 + V4 + V5, normalised).
--
-- RE-BASED V-NUMBER: §3 prints this table as V38 under the plan's ORIGINAL numbering. Only its
-- columns and constraints come from that row; the number is the next free one at landing time
-- (§3's re-basing note). Flyway runs with out-of-order=false, so an applied migration is never
-- edited or renumbered — only new files are added. V10 (user_preferences) is REDUNDANT and stays
-- UNUSED: preferences live on `users` (V3).
--
-- TWO TABLES IN ONE FILE, following V9_1, V22, V29 and V34: the split below is one design and
-- either half alone is meaningless. The source had ONE table holding one row per MESSAGE with a
-- bare `session_id` bolted on by V4; that shape has no place to put anything that belongs to the
-- conversation rather than to a turn, and no foreign key to keep the two in step. It is
-- normalised here into the conversation and its messages.
--
-- THE CONVERSATION ID IS THE SESSION ID. §3 records the decision that a chat session is a bare
-- correlation id and deliberately not a refresh-token family, and §4.11's wire contract carries it
-- in the `X-Session-Id` response header. That header's value is `coach_conversation.id`. There is
-- therefore NO second `session_id` column: two identifiers for one thing can disagree, and the
-- source's could — `session_id` was added by V4 with no NOT NULL, while the entity declared one
-- (inventory §4).
--
-- THESE ROWS ARE PER MEMBER, NOT PER HOUSEHOLD (§1.5, §3's user_id enumeration). Every ledger
-- table in this schema carries `household_id` because the owner's decision is one shared ledger;
-- this one is the deliberate opposite, like `ai_settings` (V16) and `tax_export_run` (V35). The
-- FIGURES a coach answer discusses come from the shared household ledger; the conversation about
-- them belongs to one member, and a household mate reading another member's coach thread is the
-- leak this table is shaped to prevent. `CoachConversationRepository` therefore extends
-- `UserOwnedRepository`, and every route that can name a conversation answers 404 — never 403 —
-- for one that is not the caller's.
--
-- THE CONTENT COLUMN IS ENCRYPTED AT REST (readiness gap row 44, second half, handed to this step
-- by name in collapse step 17's report). `content_encrypted` holds `FieldEncryptionService`
-- ciphertext with its `v1:` prefix, exactly as `ai_settings.api_key_encrypted` (V16),
-- `totp_credential.secret_encrypted` (V5) and the household invite token (V9_2) do. A member's
-- question names what they bought, where, and for how much; a database dump of this table must not
-- be a transcript of that.
--
--   There is NO plaintext column beside it. No plaintext preview, no plaintext title derived by
--   copying the first message, and no trigram index, LIKE search or full-text search over
--   conversations — none of those can exist over ciphertext, and keeping a cleartext copy to make
--   one work would undo the whole column. That lost search IS the price of the encryption and is
--   recorded as such rather than quietly bought back.
--
--   `title` is nullable and is a name a MEMBER may one day give a conversation. Nothing in this
--   step writes it, precisely because the obvious way to fill it — the first line of the first
--   question — is the plaintext copy the paragraph above forbids.
--
-- DROPPED from the source, deliberately, rather than carried:
--   * `privacy_tier VARCHAR(10)` — the four-value source `PrivacyTier` and its
--     `PrivacyEnforcementAspect` are DELETED with `libs/ai-gateway-client` (§2.10; findings M5/O1
--     called the privacy engine theatre). The merged two-value
--     `finance.trove.ai.PrivacyTier{LOCAL_ONLY, CLOUD_OK}` is a property of the MEMBER's endpoint
--     and lives on `ai_settings` (V16), not on every message. A per-message copy would be a
--     snapshot of a setting that can change, disagreeing with the live one from the moment it was
--     written.
--   * `model_alias` — the source's own V5 already dropped it, together with the multi-provider
--     router that gave it meaning.
--   * The source's lower-case/upper-case role migration (V3 wrote 'user'/'assistant', V5
--     uppercased the data and rewrote the CHECK). No production data exists, so the final CHECK is
--     written once, here, in one CREATE TABLE.

CREATE TABLE coach_conversation (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title      VARCHAR(200) NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The history route's only query: this member's conversations, most recently used first.
CREATE INDEX coach_conversation_user_id_idx ON coach_conversation (user_id, updated_at DESC);

CREATE TABLE coach_message (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id   UUID         NOT NULL
                      REFERENCES coach_conversation (id) ON DELETE CASCADE,
    role              VARCHAR(9)   NOT NULL
                      CHECK (role IN ('SYSTEM', 'USER', 'ASSISTANT')),
    content_encrypted TEXT         NOT NULL,
    model_used        VARCHAR(100) NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Every read of a message is "the turns of this conversation, oldest first". There is no index on
-- a bare message id because there is no route that names one: a message is reached through the
-- conversation whose ownership the caller has already proved.
CREATE INDEX coach_message_conversation_idx ON coach_message (conversation_id, created_at ASC);

COMMENT ON TABLE coach_conversation IS
    'One AI-coach conversation, owned by one member. Deliberately NOT household-scoped: a '
    'household mate must not read, continue or delete another member''s thread. The id IS the '
    'X-Session-Id of the §4.11 wire contract.';
COMMENT ON COLUMN coach_conversation.title IS
    'A name a member may give a conversation. Never derived from message content — that would be '
    'the plaintext copy content_encrypted exists to avoid.';
COMMENT ON TABLE coach_message IS
    'One turn of a coach conversation. Reached only through its conversation; there is no route '
    'that names a message id.';
COMMENT ON COLUMN coach_message.content_encrypted IS
    'AES-GCM ciphertext from FieldEncryptionService, with its v1: prefix (gap row 44). There is no '
    'plaintext column, preview or search index over this content, by design.';
