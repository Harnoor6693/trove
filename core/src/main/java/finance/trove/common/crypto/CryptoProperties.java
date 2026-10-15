package finance.trove.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code trove.crypto.*} — the field-encryption key of
 * {@code docs/recovery/06-COLLAPSE-PLAN.md} §1.6.
 *
 * <p>Bound as a property rather than read through {@code System.getenv} (which is what the
 * source {@code FieldEncryptionService} did) so that the value is visible to the relaxed binder,
 * overridable in a test with {@code @DynamicPropertySource}, and validated during context
 * refresh instead of on first use.
 *
 * @param key the 256-bit key, as 64 hex characters or as base64 of exactly 32 bytes; no default
 */
@ConfigurationProperties(prefix = "trove.crypto")
public record CryptoProperties(String key) {
}
