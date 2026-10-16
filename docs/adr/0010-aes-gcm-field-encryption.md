# ADR-0010: AES-256-GCM Field-Level Encryption for Sensitive Credentials

**Date**: 2026-04-27  
**Status**: accepted (amended 2026-10-16, see note)

> Update 2026-10-16: ciphertext is now stored as `v1:` + Base64(IV || ciphertext || tag); unprefixed values and other versions are rejected. The key is 64 hex characters or base64 of 32 bytes, checked at startup. Changing `TROVE_ENCRYPTION_KEY` does NOT rotate: existing values become undecryptable until a re-encrypt migration exists (the version prefix only prepares for one). `FieldEncryptionService` now also protects the AI API key, coach conversation and insight content, TOTP secrets, webhook subscription secrets, household invite tokens, the SimpleFIN setup exchange and the OIDC state cookie, so decryption is not limited to `SyncOrchestrator`. `config_encrypted` is nullable. There are no separate services sharing the key. The original text below is kept, except that its two claims about key rotation (marked "corrected 2026-10-16") are fixed in place.  

**Deciders**: Security team

## Context

Trove stores sensitive plugin credentials (API keys, IMAP passwords, OAuth tokens) in the PostgreSQL database as part of the `ConnectedAccount` entity (in the `config_encrypted` field). These credentials would completely compromise user privacy if exposed in plaintext. The database itself may be compromised (e.g., stolen backup, unauthorized access to filesystem), so credentials must be encrypted before being persisted. Additionally, if an attacker modifies the encrypted ciphertext (flips bits), the decryption should fail loudly (integrity checking required).

## Decision

Use AES-256-GCM (Advanced Encryption Standard with 256-bit key in Galois/Counter Mode) to encrypt sensitive credentials before persisting to the database. The encryption key is a 256-bit random value loaded from the `TROVE_ENCRYPTION_KEY` environment variable. Each encryption call generates a fresh 12-byte IV (initialization vector) using `SecureRandom.nextBytes()`. The output format is `Base64(12-byte IV || ciphertext || 16-byte GCM authentication tag)`. The GCM mode provides authenticated encryption: if the ciphertext is modified, the authentication tag verification fails and decryption throws `AEADBadTagException`. Plaintext credentials exist only within the JVM heap during the encrypt/decrypt calls; they are never logged or persisted.

## Alternatives Considered

### Alternative 1: AES-256-CBC with HMAC
- **Pros**: Two separate algorithms provide explicit authentication; widely understood
- **Cons**: Requires two keys; more complex to implement correctly (HMAC-then-encrypt vs. encrypt-then-HMAC); slower than GCM
- **Why not**: GCM combines encryption and authentication in a single operation; easier to implement securely

### Alternative 2: Store Credentials in a Separate Secrets Management Service
- **Pros**: Credentials never touch the database; central key management; easier rotation
- **Cons**: Requires additional service dependency; network latency on credential retrieval; more complex deployment
- **Why not**: Field-level encryption in the database is sufficient for Trove's current scale; secrets service is a future optimization

### Alternative 3: Asymmetric Encryption (RSA-2048)
- **Pros**: Credentials can be encrypted without needing the decryption key (public key encryption)
- **Cons**: RSA is slow; requires key pair management; keys are larger (4 KB vs. 32 bytes for symmetric key)
- **Why not**: Symmetric encryption is sufficient; only SyncOrchestrator needs to decrypt, so no benefit to asymmetric approach

## Consequences

### Positive
- Credentials are encrypted at rest in the database (confidentiality)
- GCM authentication tag ensures integrity (detects tampering)
- Fresh IV per encryption ensures identical plaintexts produce different ciphertexts (semantic security)
- No external dependency; JCE provides GCM support natively
- Encryption/decryption are fast (AES is hardware-accelerated on modern CPUs)
- ~~Key rotation is simple (redeploy with new `TROVE_ENCRYPTION_KEY`)~~ (corrected 2026-10-16: there is no rotation. Redeploying with a new `TROVE_ENCRYPTION_KEY` makes every stored credential, TOTP secret and other encrypted value undecryptable, and users must re-enter their bank credentials and re-enrol TOTP. A re-encrypt migration is future work; the `v1:` prefix only prepares for it)

### Negative
- Encryption key must be stored in environment variables (shared across all services; larger blast radius if exposed)
- Plaintext credentials exist in JVM heap during decrypt (vulnerable to memory dumps, but mitigated by short lifetime)
- Cannot decrypt credentials without the encryption key (key loss means credential loss)
- IV must be stored in plaintext alongside ciphertext (but IV reuse is prevented by random generation per call)

### Risks
- **Key exposure**: If `TROVE_ENCRYPTION_KEY` environment variable is compromised, all encrypted credentials are compromised. Mitigated by: (1) strict access controls on environment variables in CI/CD pipeline, (2) ~~regular key rotation~~ (corrected 2026-10-16: not available, see Positive above; rotate only on suspected exposure and accept that stored values must be re-entered), (3) audit logs of who accessed environment variables
- **IV reuse with same key**: If IV is reused for two encryptions with the same key, GCM security is broken. Mitigated by: (1) using `SecureRandom.nextBytes()` to generate fresh IV per call, (2) never reusing IV even across different processes
- **Key derivation from password**: If key is derived from a user password, weak passwords weaken encryption. Mitigated by: (1) using externally-generated random keys (not password-derived), (2) validating key format and length at startup
- **Decryption without verification**: If authentication tag is ignored during decryption, tampering is undetected. Mitigated by: (1) using JCE's built-in GCM verification (always verified), (2) catching `AEADBadTagException` and failing loudly
