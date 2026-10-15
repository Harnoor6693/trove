package finance.trove.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code trove.jwt.*} and refuses to let the application start with a weak or shipped
 * signing key ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.6, §9 risk 16).
 *
 * <p>This is the fail-fast the step-1 report flagged as "specified but not yet enforced": at
 * step 1 no bean read {@code trove.jwt.secret}, so an unset {@code TROVE_JWT_SECRET} booted
 * happily and only surfaced when someone tried to log in. Because this is a record registered by
 * {@code @ConfigurationPropertiesScan}, the canonical constructor below runs during context
 * refresh — a bad secret is a failed boot, not a failed login.
 *
 * <p>Three rejections, each for a different real failure:
 * <ul>
 *   <li><strong>Absent.</strong> {@code application.yml} writes {@code ${TROVE_JWT_SECRET}} with
 *       no default, and Boot's binder hands the unresolved literal through rather than failing.
 *       That literal is 20 characters, so the length check catches it too, but the message
 *       differs and the message is the whole point of failing early.</li>
 *   <li><strong>Shorter than 32 characters.</strong> HS256 is HMAC-SHA-256; a key shorter than
 *       the 256-bit block is a brute-forceable key, and JJWT would reject it at first use — at
 *       runtime, in front of a user, rather than at boot in front of an operator.</li>
 *   <li><strong>The shipped placeholder.</strong> A known signing key is a complete
 *       authentication bypass: anyone who has read the repository can mint an admin token for any
 *       install that kept it (§9 risk 16).</li>
 * </ul>
 *
 * @param secret               HS256 signing key; at least 32 characters, never the placeholder
 * @param keyId                the {@code kid} header stamped on every issued token, so a second
 *                             key can be introduced later without invalidating the first
 * @param issuer               the {@code iss} claim; tokens from another issuer are rejected
 * @param expirySeconds        access-token lifetime (§1.5: 15 minutes)
 * @param refreshExpirySeconds refresh-token <em>absolute</em> lifetime ceiling
 * @param totpChallengeSeconds lifetime of the {@code typ=totp_challenge} token (§1.5: 60 s)
 * @param stepUpSeconds        how long a step-up (password + TOTP) re-authentication stays valid
 */
@ConfigurationProperties(prefix = "trove.jwt")
public record JwtProperties(
    String secret,
    String keyId,
    String issuer,
    int expirySeconds,
    int refreshExpirySeconds,
    int totpChallengeSeconds,
    int stepUpSeconds) {

    /** The literal shipped in the pre-collapse `.env.example` files; never acceptable. */
    public static final String SHIPPED_PLACEHOLDER = "change-me-minimum-32-chars-in-production!!";

    /** HMAC-SHA-256's block size in bytes; a shorter key weakens the signature (§1.6). */
    public static final int MINIMUM_SECRET_LENGTH = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "TROVE_JWT_SECRET is not set. Generate one with `openssl rand -base64 48` and put "
                    + "it in deploy/.env; there is no default and the application will not start "
                    + "without it.");
        }
        if (secret.startsWith("${")) {
            throw new IllegalStateException(
                "TROVE_JWT_SECRET resolved to the unsubstituted placeholder '" + secret
                    + "'. The environment variable is missing from the container.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_LENGTH) {
            throw new IllegalStateException(
                "TROVE_JWT_SECRET must be at least " + MINIMUM_SECRET_LENGTH
                    + " bytes for HS256; got " + secret.getBytes(StandardCharsets.UTF_8).length
                    + ". Generate one with `openssl rand -base64 48`.");
        }
        if (SHIPPED_PLACEHOLDER.equals(secret)) {
            throw new IllegalStateException(
                "TROVE_JWT_SECRET is still the placeholder shipped with the repository. Anyone "
                    + "who has read the source can forge an admin token against this install. "
                    + "Generate one with `openssl rand -base64 48`.");
        }
        keyId = keyId == null || keyId.isBlank() ? "k1" : keyId;
        issuer = issuer == null || issuer.isBlank() ? "trove-core" : issuer;
        expirySeconds = expirySeconds <= 0 ? 900 : expirySeconds;
        refreshExpirySeconds = refreshExpirySeconds <= 0 ? 86_400 : refreshExpirySeconds;
        totpChallengeSeconds = totpChallengeSeconds <= 0 ? 60 : totpChallengeSeconds;
        stepUpSeconds = stepUpSeconds <= 0 ? 300 : stepUpSeconds;
    }

    public Duration accessTtl() {
        return Duration.ofSeconds(expirySeconds);
    }

    public Duration refreshTtl() {
        return Duration.ofSeconds(refreshExpirySeconds);
    }

    public Duration totpChallengeTtl() {
        return Duration.ofSeconds(totpChallengeSeconds);
    }

    public Duration stepUpWindow() {
        return Duration.ofSeconds(stepUpSeconds);
    }
}
