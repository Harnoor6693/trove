package finance.trove.auth.jwt;

import java.time.Instant;
import java.util.UUID;

/**
 * The claims of a token that has already passed signature, issuer, {@code kid}, expiry and
 * {@code typ} verification.
 *
 * <p>Only {@link JwtVerifier} constructs one, which is the point: a component holding a
 * {@code VerifiedToken} cannot have skipped a check, whereas a component holding a
 * {@code Claims} map might have.
 *
 * @param type        the verified {@code typ}
 * @param userId      the {@code sub}
 * @param householdId the {@code hid}; {@code null} on a {@code totp_challenge}, which is issued
 *                    before the session exists and deliberately carries no tenancy
 * @param familyId    the {@code fid} — the refresh-token family this session belongs to;
 *                    {@code null} on any token type that is not a session
 * @param email       the {@code email} claim
 * @param role        the {@code role} claim, {@code null} when the type carries none
 * @param tokenId     the {@code jti}
 * @param steppedUpAt the {@code sua} claim — when this session last completed a step-up
 * @param expiresAt   the {@code exp}
 */
public record VerifiedToken(
    TokenType type,
    UUID userId,
    UUID householdId,
    UUID familyId,
    String email,
    String role,
    String tokenId,
    Instant steppedUpAt,
    Instant expiresAt) {
}
