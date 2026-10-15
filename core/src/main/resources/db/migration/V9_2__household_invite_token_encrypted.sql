-- V9.2 — household_invite.token_encrypted.
--
-- WHY A SECOND COPY OF A TOKEN WE DELIBERATELY ONLY HASHED.
--
-- V9.1 stores the SHA-256 hash and nothing else, so `GET /v1/admin/invites` could never return
-- `token` or `url`: the raw value existed once, in the 201 body, and was gone. That is the right
-- default for a credential, and it is wrong for this one, because the shipped web admin UI never
-- reads the creation response — `apps/web/src/pages/Settings/index.tsx:166-169` discards it and
-- `:215` renders `invite.url` from the GET listing. The result is an invite feature that cannot
-- produce a usable link from the only UI that exists, which is why finding 6 is a blocker.
--
-- So the raw token is kept, but encrypted at rest with TROVE_ENCRYPTION_KEY (AES-GCM, the same
-- FieldEncryptionService that protects totp_credential.secret), and the hash stays the lookup
-- key. The properties that mattered are preserved:
--
--   * a database dump on its own still yields no working invitation — the key lives in the
--     environment, not in the database;
--   * the lookup path is unchanged: redemption still finds the row by SHA-256 hash, so the
--     ciphertext is never a credential the server accepts;
--   * the plaintext is returned only to an ADMIN of the invite's own household, and only while
--     the invite is still usable. InviteService nulls the column the moment an invite is
--     accepted, revoked or expired, so a redeemed invite's token is not recoverable from the
--     listing or from the row.
--
-- TEXT rather than a bounded VARCHAR: the ciphertext is `v1:` plus base64 of iv ‖ ct ‖ tag, and
-- pinning a length here would have to be re-migrated the first time the key format changes.

ALTER TABLE household_invite
    ADD COLUMN token_encrypted TEXT;

COMMENT ON COLUMN household_invite.token_encrypted IS
    'AES-GCM ciphertext of the raw invite token, so the admin listing can render the link the web UI needs. NULLed on accept/revoke; expired rows are never decrypted. The SHA-256 token_hash remains the only lookup key.';
