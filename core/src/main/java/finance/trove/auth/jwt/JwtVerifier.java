package finance.trove.auth.jwt;

import finance.trove.common.exception.UnauthorizedException;
import finance.trove.common.time.UserClock;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Verifies a Trove token and refuses anything that is not exactly the type the caller asked for
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, §9 risk 28).
 *
 * <p>The pre-collapse {@code JwtAuthenticationProvider} accepted any HS256 token with a
 * {@code sub}. Four checks are added here, and each of them rejects a token the old verifier
 * would have accepted:
 *
 * <ol>
 *   <li><strong>{@code typ} must equal the expected type.</strong> Without it a 60-second
 *       {@code totp_challenge} — handed to a caller who has proven only the password — is a full
 *       session, and the second factor is decorative. This is the blocker of §9 risk 28.</li>
 *   <li><strong>{@code iss} must be this install's issuer.</strong> Two Trove installs sharing a
 *       secret (a restored backup, a staging copy of prod's `.env`) would otherwise accept each
 *       other's sessions.</li>
 *   <li><strong>{@code kid} must name the configured key.</strong> Today there is one key, so
 *       this only rejects tokens minted by something that is not this issuer; it exists so that
 *       introducing a second key later is a configuration change rather than a code change.</li>
 *   <li><strong>Clock skew is bounded, not unbounded.</strong> JJWT's default allows none, which
 *       on a Raspberry Pi that boots before NTP converges rejects every freshly issued token as
 *       "not yet valid". {@link UserClock#CLOCK_SKEW_TOLERANCE} is the single number the TOTP
 *       verifier also uses (gap P2-15).</li>
 * </ol>
 *
 * <p>Every failure raises {@link UnauthorizedException} with a deliberately uninformative message.
 * A verifier that says "expired" versus "bad signature" versus "wrong type" tells an attacker
 * which half of a forgery attempt worked.
 */
@Service
public class JwtVerifier {

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtVerifier(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param token    the compact JWT, with no {@code Bearer } prefix
     * @param expected the only type this call site accepts
     * @throws UnauthorizedException on any failure — signature, issuer, key id, expiry or type
     */
    public VerifiedToken verify(String token, TokenType expected) {
        Jws<Claims> jws;
        try {
            jws = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .clockSkewSeconds(UserClock.CLOCK_SKEW_TOLERANCE.toSeconds())
                .build()
                .parseSignedClaims(token);
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException("Invalid or expired token");
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid or expired token");
        }

        String keyId = jws.getHeader().getKeyId();
        if (!properties.keyId().equals(keyId)) {
            throw new UnauthorizedException("Invalid or expired token");
        }

        Claims claims = jws.getPayload();
        TokenType actual = TokenType.fromClaim(claims.get(JwtIssuerService.CLAIM_TYPE, String.class))
            .orElseThrow(() -> new UnauthorizedException("Invalid or expired token"));
        if (actual != expected) {
            throw new UnauthorizedException("Invalid or expired token");
        }

        UUID userId = uuid(claims.getSubject());
        UUID householdId = claims.get(JwtIssuerService.CLAIM_HOUSEHOLD) == null
            ? null
            : uuid(claims.get(JwtIssuerService.CLAIM_HOUSEHOLD, String.class));
        UUID familyId = claims.get(JwtIssuerService.CLAIM_FAMILY) == null
            ? null
            : uuid(claims.get(JwtIssuerService.CLAIM_FAMILY, String.class));
        Instant steppedUpAt = claims.get(JwtIssuerService.CLAIM_STEPPED_UP_AT) == null
            ? null
            : Instant.ofEpochSecond(claims.get(JwtIssuerService.CLAIM_STEPPED_UP_AT, Number.class)
                .longValue());

        return new VerifiedToken(
            actual,
            userId,
            householdId,
            familyId,
            claims.get(JwtIssuerService.CLAIM_EMAIL, String.class),
            claims.get(JwtIssuerService.CLAIM_ROLE, String.class),
            claims.getId(),
            steppedUpAt,
            claims.getExpiration().toInstant());
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new UnauthorizedException("Invalid or expired token");
        }
    }
}
