package finance.trove.auth.jwt;

import java.time.Instant;

/**
 * A freshly minted token and the two facts a caller needs about it: when it expires, and its
 * {@code jti}.
 *
 * <p>The {@code jti} is what {@code RefreshTokenService} keys a stored row on, and what a
 * {@code security_event} row records so an operator can tie "this session was revoked" to "this
 * token was issued" without either row containing the token itself.
 *
 * @param value     the compact JWT
 * @param tokenId   the {@code jti} claim
 * @param expiresAt the {@code exp} claim as an instant
 */
public record IssuedToken(String value, String tokenId, Instant expiresAt) {

    /** Seconds until expiry, as the login/refresh response bodies report it. */
    public long expiresInSeconds(Instant now) {
        long seconds = expiresAt.getEpochSecond() - now.getEpochSecond();
        return Math.max(seconds, 0L);
    }
}
